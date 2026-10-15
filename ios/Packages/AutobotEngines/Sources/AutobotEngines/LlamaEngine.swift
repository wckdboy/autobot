import AutobotLlama
import Foundation
import os

/// Kinds of streamed text (AUTOBOT_LLAMA_DELTA_*).
public enum DeltaKind: Int32, Sendable {
    case content = 0
    case reasoning = 1
}

public struct ToolCall: Sendable, Codable, Hashable {
    public let id: String
    public let name: String
    /// JSON-encoded arguments, as the model wrote them.
    public let arguments: String
}

public struct LlamaUsage: Sendable, Codable, Hashable {
    public let promptTokens: Int
    public let cachedTokens: Int
    public let completionTokens: Int
    /// Where the model actually ran: "cpu", "gpu" or "npu".
    public let backend: String
    public let promptMs: Double
    public let generationMs: Double

    enum CodingKeys: String, CodingKey {
        case promptTokens = "prompt_tokens"
        case cachedTokens = "cached_tokens"
        case completionTokens = "completion_tokens"
        case backend
        case promptMs = "prompt_ms"
        case generationMs = "generation_ms"
    }
}

/// The final result of one completion, decoded from the engine core's JSON.
public struct LlamaResult: Sendable, Codable, Hashable {
    public let content: String
    public let reasoning: String
    public let toolCalls: [ToolCall]
    /// "stop", "length", "tool_calls", "cancelled" or "error".
    public let finishReason: String
    public let usage: LlamaUsage
    /// The raw JSON, as Android's `LocalLlmEvent.Result` carries it.
    public var json: String = ""

    /// Where the model actually ran (after any fallback).
    public var backend: ComputeBackend { ComputeBackend(rawValue: usage.backend) ?? .cpu }

    enum CodingKeys: String, CodingKey {
        case content, reasoning, usage
        case toolCalls = "tool_calls"
        case finishReason = "finish_reason"
    }

    private struct Failure: Decodable {
        struct Body: Decodable {
            let code: String
            let message: String
        }
        let error: Body
    }

    /// Decodes a result, throwing [EngineError] for `{error:{code,message}}`.
    public static func decode(_ json: String) throws -> LlamaResult {
        let data = Data(json.utf8)
        let decoder = JSONDecoder()
        if let failure = try? decoder.decode(Failure.self, from: data) {
            throw EngineError(failure.error.code, failure.error.message)
        }
        do {
            var result = try decoder.decode(LlamaResult.self, from: data)
            result.json = json
            return result
        } catch {
            throw EngineError("BAD_RESULT", "unreadable engine result: \(error.localizedDescription)")
        }
    }
}

/**
 * llama.cpp on this device (Metal GPU or CPU), through the shared engine core that Android uses
 * too. One model is resident at a time; requests run serially on a dedicated thread so the KV
 * cache is reused between agent steps. Nothing here touches the network.
 */
public actor LlamaEngine {
    public static let shared = LlamaEngine()

    private let worker = NativeWorker("autobot.llama")
    private let session = Session()

    public init() {}

    /// Compute devices llama.cpp can use (Metal GPU, CPU, BLAS).
    public func devices() async throws -> [ComputeDevice] {
        let session = session
        let json = try await worker.run {
            session.initialize()
            return takeString(autobot_llama_devices_json(), free: autobot_llama_string_free)
        }
        return try JSONDecoder().decode([ComputeDevice].self, from: Data(json.utf8))
    }

    public func systemInfo() async throws -> String {
        let session = session
        return try await worker.run {
            session.initialize()
            return String(cString: autobot_llama_system_info())
        }
    }

    /// Info about the resident model (JSON: description, n_params, size_bytes, n_ctx, backend…), or nil.
    public func modelInfo() async throws -> String? {
        let session = session
        return try await worker.run {
            guard let handle = session.handle.pointer else { return nil }
            return takeString(autobot_llama_model_info(handle), free: autobot_llama_string_free)
        }
    }

    /**
     * Runs one completion. [requestJSON] is OpenAI-shaped ({messages, tools?, temperature?, top_p?,
     * max_tokens?, seed?, enable_thinking?}); image parts are `{type:"image", path}` and need
     * [mmprojPath]. In the Simulator everything runs on the CPU. Deltas arrive on the engine thread. Cancelling the calling task stops
     * generation; the partial result then finishes "cancelled".
     */
    public func chat(
        modelPath: String,
        mmprojPath: String? = nil,
        contextLength: Int,
        backend: ComputeBackend = .auto,
        requestJSON: Data,
        onDelta: @escaping @Sendable (DeltaKind, String) -> Void
    ) async throws -> LlamaResult {
        try Task.checkCancellation()
        let request = try Self.withMmproj(requestJSON, mmprojPath)
        let session = session
        let json = try await worker.run({
            try session.chat(path: modelPath, nCtx: contextLength, backend: metalUsable ? backend.rawValue : "cpu", request: request, onDelta: onDelta)
        }, onCancel: {
            session.cancel()
        })
        return try LlamaResult.decode(json)
    }

    /**
     * Embeds [texts] with the GGUF embedding model at [modelPath] (the private vault), kept loaded
     * next to the chat model: one L2-normalised vector per text, pooled the way the model was
     * trained. Runs after any running completion; nothing leaves the device.
     */
    public func embed(modelPath: String, texts: [String]) async throws -> [[Float]] {
        let session = session
        return try await worker.run { try session.embed(path: modelPath, texts: texts, backend: metalUsable ? "auto" : "cpu") }
    }

    /// Stops the running completion, if any.
    public nonisolated func cancel() {
        session.cancel()
    }

    /// Frees the resident models now (e.g. before image generation needs the memory).
    public func unload() async {
        let session = session
        _ = try? await worker.run {
            session.release()
            session.releaseEmbedder()
        }
    }

    static func withMmproj(_ request: Data, _ mmproj: String?) throws -> Data {
        guard let mmproj, !mmproj.isEmpty else { return request }
        guard var object = try JSONSerialization.jsonObject(with: request) as? [String: Any] else {
            throw EngineError("BAD_REQUEST", "request must be a JSON object")
        }
        if object["mmproj"] == nil { object["mmproj"] = mmproj }
        return try JSONSerialization.data(withJSONObject: object)
    }
}

/// Native session state, confined to the engine thread except for [handle] (read by cancel).
private final class Session: @unchecked Sendable {
    let handle = NativeHandle()
    private var initialized = false
    private var devices: Int32 = 0
    private var loadedPath: String?
    private var loadedCtx = 0
    private var loadedBackend: String?
    private let embedder = NativeHandle()
    private var embedderPath: String?

    /// Passages are about 512 tokens plus the file name; queries carry an instruction.
    static let embedContext: Int32 = 1024

    private static let log = Logger(subsystem: "ai.wckd.autobot", category: "llama")

    func initialize() {
        if initialized { return }
        autobot_llama_set_log({ level, text, _ in
            guard let text else { return }
            let line = String(cString: text).trimmingCharacters(in: .newlines)
            if line.isEmpty { return }
            switch level {
            case Int32(AUTOBOT_LLAMA_LOG_ERROR): Session.log.error("\(line, privacy: .public)")
            case Int32(AUTOBOT_LLAMA_LOG_WARN): Session.log.warning("\(line, privacy: .public)")
            case Int32(AUTOBOT_LLAMA_LOG_INFO): Session.log.info("\(line, privacy: .public)")
            default: Session.log.debug("\(line, privacy: .public)")
            }
        }, nil)
        devices = autobot_llama_init(nil)
        initialized = true
    }

    func chat(path: String, nCtx: Int, backend: String, request: Data, onDelta: @escaping @Sendable (DeltaKind, String) -> Void) throws -> String {
        initialize()
        if devices == 0 {
            throw EngineError("LOAD_FAILED", "no compute backend available")
        }
        if handle.pointer == nil || loadedPath != path || loadedCtx != nCtx || loadedBackend != backend {
            release()
            guard let loaded = autobot_llama_load(path, Int32(nCtx), inferenceThreads(), backend) else {
                throw EngineError("LOAD_FAILED", "could not load model (corrupt file or not enough memory)")
            }
            handle.set(loaded)
            loadedPath = path
            loadedCtx = nCtx
            loadedBackend = backend
        }
        let sink = DeltaSink(onDelta)
        let user = Unmanaged.passRetained(sink)
        defer { user.release() }
        let result = request.withUnsafeBytes { raw -> UnsafeMutablePointer<CChar>? in
            autobot_llama_chat(handle.pointer, raw.baseAddress?.assumingMemoryBound(to: CChar.self), raw.count, { kind, bytes, len, user in
                guard let bytes, let user else { return 0 }
                let sink = Unmanaged<DeltaSink>.fromOpaque(user).takeUnretainedValue()
                let text = String(decoding: UnsafeRawBufferPointer(start: bytes, count: len), as: UTF8.self)
                sink.emit(DeltaKind(rawValue: kind) ?? .content, text)
                return 0
            }, user.toOpaque())
        }
        return takeString(result, free: autobot_llama_string_free)
    }

    func embed(path: String, texts: [String], backend: String) throws -> [[Float]] {
        initialize()
        if devices == 0 {
            throw EngineError("LOAD_FAILED", "no compute backend available")
        }
        if embedder.pointer == nil || embedderPath != path {
            releaseEmbedder()
            guard let loaded = autobot_llama_embed_load(path, Self.embedContext, inferenceThreads(), backend) else {
                throw EngineError("LOAD_FAILED", "could not load the embedding model (corrupt file or not enough memory)")
            }
            embedder.set(loaded)
            embedderPath = path
        }
        guard !texts.isEmpty else { return [] }
        let dims = Int(autobot_llama_embed_dims(embedder.pointer))
        guard dims > 0 else { throw EngineError("EMBED_FAILED", "the model has no embedding output") }
        let strings = texts.map { strdup($0) }
        defer { strings.forEach { free($0) } }
        let pointers: [UnsafePointer<CChar>?] = strings.map { $0.map { UnsafePointer($0) } }
        let lengths = texts.map { $0.utf8.count }
        var out = [Float](repeating: 0, count: dims * texts.count)
        let rc = out.withUnsafeMutableBufferPointer { buffer in
            autobot_llama_embed(embedder.pointer, pointers, lengths, Int32(texts.count), buffer.baseAddress)
        }
        guard rc == 0 else { throw EngineError("EMBED_FAILED", "embedding failed (\(rc))") }
        return (0 ..< texts.count).map { Array(out[($0 * dims) ..< (($0 + 1) * dims)]) }
    }

    func cancel() {
        handle.withPointer { autobot_llama_cancel($0) }
    }

    func releaseEmbedder() {
        guard let pointer = embedder.take() else { return }
        autobot_llama_embed_free(pointer)
        embedderPath = nil
    }

    func release() {
        guard let pointer = handle.take() else { return }
        autobot_llama_free(pointer)
        loadedPath = nil
    }
}

private final class DeltaSink {
    let emit: @Sendable (DeltaKind, String) -> Void

    init(_ emit: @escaping @Sendable (DeltaKind, String) -> Void) {
        self.emit = emit
    }
}
