import CoreML
import Foundation

/**
 A Kokoro voice pack (`<voice>.bin`): 510 style vectors of 256 floats, one per phoneme count. Kokoro
 reads a piece of `n` phonemes with row `n - 1`; the first 128 floats steer the decoder, the last
 128 the prosody predictor.
 */
public struct KokoroVoicePack: Sendable {
    public static let rows = 510
    public static let dim = 256

    let values: [Float]

    public init(values: [Float]) throws {
        guard values.count == Self.rows * Self.dim else { throw EngineError("BAD_VOICE", "not a Kokoro voice pack") }
        self.values = values
    }

    /// Reads a little-endian float32 `.bin` voice pack.
    public init(contentsOf url: URL) throws {
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        guard data.count == Self.rows * Self.dim * 4 else { throw EngineError("BAD_VOICE", "\(url.lastPathComponent) is not a Kokoro voice pack") }
        try self.init(values: data.withUnsafeBytes { raw in
            (0 ..< Self.rows * Self.dim).map { Float(bitPattern: UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: $0 * 4, as: UInt32.self))) }
        })
    }

    /// The style vector for [row] (clamped to the pack).
    public func style(row: Int) -> ArraySlice<Float> {
        let r = min(max(row, 0), Self.rows - 1)
        return values[r * Self.dim ..< (r + 1) * Self.dim]
    }
}

/**
 Kokoro-82M on Core ML (`mlboydaisuke/Kokoro-82M-CoreML`, float32, CPU + GPU as converted): a
 predictor (token ids → durations and features, up to 256 ids with the two pads) and a decoder per
 fixed length (`Kokoro_Decoder_<frames>`; 600 samples per frame). The alignment between them
 (each phoneme repeated for its frames) runs here. Text → token ids is the shared Kotlin frontend
 (`KokoroFrontend`); this engine only sees ids. Packages compile on first use and stay loaded
 until [unload]. Nothing is fetched or sent.
 */
public actor TtsEngine {
    public static let shared = TtsEngine()

    /// Output: 24 kHz mono float PCM.
    public static let sampleRate = 24_000
    /// Audio samples per predicted frame.
    public static let samplesPerFrame = 600
    /// Phoneme ids per piece the predictor takes (256 with the two pads).
    public static let maxTokens = 254
    static let spaceId: Int32 = 16
    /// Most frames one phoneme may take (Kokoro predicts a few dozen at most).
    static let maxFramesPerToken = 1000

    private var folder: URL?
    private var predictor: MLModel?
    private var decoders: [Int: MLModel] = [:]
    private var buckets: [Int] = []
    private var voices: [String: KokoroVoicePack] = [:]
    /// Decoder lengths in use order, least recent first (the cache keeps [decoderLimit]).
    private var decoderOrder: [Int] = []
    /// The one load in flight: concurrent callers (speak and prepare-next) wait for it instead of
    /// compiling and moving the same packages twice.
    private var loading: (folder: URL, task: Task<Void, Error>)?
    private var decoderLoads: [Int: Task<LoadedModel, Error>] = [:]
    /// Bumped by [unload]: a load that finishes afterwards keeps nothing.
    private var epoch = 0

    public init() {}

    /// Voice ids (`af_heart`, …) installed in [folder], sorted.
    public static func voices(in folder: URL) -> [String] {
        let items = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        return items.filter { $0.hasSuffix(".bin") }.map { String($0.dropLast(4)) }.sorted()
    }

    /// Loads the predictor (and finds the decoders) now; synthesis otherwise does it on first use.
    public func prepare(folder: URL) async throws {
        try await load(folder)
    }

    /**
     Speech for one piece: [tokens] are Kokoro ids without the pads (at most [maxTokens]; longer
     pieces are split at a word), [styleRow] the voice-pack row (phoneme count - 1), [speed] 1
     natural. Returns 24 kHz mono float PCM.
     */
    public func synthesize(folder: URL, tokens: [Int32], styleRow: Int, voice: String, speed: Float = 1) async throws -> [Float] {
        try await load(folder)
        let pack = try voicePack(voice)
        return try await run(tokens, style: Array(pack.style(row: styleRow)), speed: speed)
    }

    /// Frees the models and voices.
    public func unload() {
        epoch += 1
        predictor = nil
        decoders = [:]
        decoderOrder = []
        voices = [:]
        folder = nil
        loading = nil
        decoderLoads = [:]
    }

    // MARK: Pipeline

    private func run(_ tokens: [Int32], style: [Float], speed: Float) async throws -> [Float] {
        try Task.checkCancellation()
        guard !tokens.isEmpty else { return [] }
        if tokens.count > Self.maxTokens {
            let cut = Self.splitPoint(tokens)
            let first = try await run(Array(tokens[..<cut]), style: style, speed: speed)
            return try await first + run(Array(tokens[cut...]), style: style, speed: speed)
        }
        guard let predictor, let largest = buckets.last else { throw EngineError("UNSUPPORTED", "the Kokoro models are missing") }
        let ids = [0] + tokens + [0]
        let input = try MLMultiArray(shape: [1, NSNumber(value: ids.count)], dataType: .int32)
        for (i, id) in ids.enumerated() { input[i] = NSNumber(value: id) }
        let out = try Self.predict(predictor, MLDictionaryFeatureProvider(dictionary: [
            "input_ids": input,
            "ref_s_style": try Self.array(style[128...], shape: [1, 128]),
        ]))
        guard let duration = out.featureValue(for: "duration")?.multiArrayValue,
              let d = out.featureValue(for: "d_for_align")?.multiArrayValue,
              let tEn = out.featureValue(for: "t_en")?.multiArrayValue
        else { throw EngineError("BAD_RESULT", "the voice predictor returned nothing") }
        let frames = Self.frames(Self.floats(duration), speed: speed)
        let total = frames.reduce(0, +)
        // Too long for the largest decoder: read it in two halves, split at a word.
        if total > largest && tokens.count > 1 {
            let cut = Self.splitPoint(tokens)
            let first = try await run(Array(tokens[..<cut]), style: style, speed: speed)
            return try await first + run(Array(tokens[cut...]), style: style, speed: speed)
        }
        try Task.checkCancellation()
        let bucket = buckets.first { $0 >= total } ?? largest
        let decoder = try await decoder(bucket)
        let en = try Self.aligned(d, frames: frames, length: bucket)
        let asr = try Self.aligned(tEn, frames: frames, length: bucket)
        let result = try Self.predict(decoder, MLDictionaryFeatureProvider(dictionary: [
            "en_aligned": en,
            "asr_aligned": asr,
            "ref_s": try Self.array(style[...], shape: [1, 256]),
        ]))
        guard let audio = result.featureValue(for: "audio")?.multiArrayValue else { throw EngineError("BAD_RESULT", "the voice decoder returned nothing") }
        let samples = Self.floats(audio)
        return Array(samples.prefix(min(total, bucket) * Self.samplesPerFrame))
    }

    /// Synchronous prediction on the actor (the models never leave it).
    private static func predict(_ model: MLModel, _ input: MLFeatureProvider) throws -> MLFeatureProvider {
        try model.prediction(from: input)
    }

    /// Frames per id: Kokoro's `round(duration / speed)` (round half to even, as torch), between 1
    /// and [maxFramesPerToken]; a NaN or infinite prediction (a broken model) counts as 1.
    static func frames(_ duration: [Float], speed: Float) -> [Int] {
        let s = speed.isFinite && speed > 0 ? speed : 1
        return duration.map { d in
            let f = (d / s).rounded(.toNearestOrEven)
            guard f.isFinite else { return 1 }
            return Int(min(max(f, 1), Float(maxFramesPerToken)))
        }
    }

    /// Where to split an over-long piece: the space nearest its middle (else the middle).
    static func splitPoint(_ tokens: [Int32]) -> Int {
        let mid = tokens.count / 2
        let spaces = tokens.indices.filter { tokens[$0] == spaceId && $0 > 0 && $0 < tokens.count - 1 }
        guard let best = spaces.min(by: { abs($0 - mid) < abs($1 - mid) }) else { return max(1, mid) }
        return best + 1
    }

    /// Features `[1, C, T]` repeated per frame into `[1, C, length]`, zero-padded (Kokoro's alignment matrix product).
    static func aligned(_ features: MLMultiArray, frames: [Int], length: Int) throws -> MLMultiArray {
        let channels = features.shape[1].intValue, count = features.shape[2].intValue
        let cs = features.strides[1].intValue, ts = features.strides[2].intValue
        let source = floats(features, raw: true)
        let out = try MLMultiArray(shape: [1, NSNumber(value: channels), NSNumber(value: length)], dataType: .float32)
        let os = out.strides[1].intValue
        let dst = out.dataPointer.assumingMemoryBound(to: Float.self)
        dst.update(repeating: 0, count: out.strides[0].intValue)
        var column = 0
        for t in 0 ..< min(count, frames.count) {
            for _ in 0 ..< frames[t] where column < length {
                for c in 0 ..< channels { dst[c * os + column] = source[c * cs + t * ts] }
                column += 1
            }
        }
        return out
    }

    /// The values of [a] in order (or, with [raw], its storage as laid out, for strided reads).
    static func floats(_ a: MLMultiArray, raw: Bool = false) -> [Float] {
        let n = raw ? a.shape[0].intValue * a.strides[0].intValue : a.count
        switch a.dataType {
        case .float16:
            let p = a.dataPointer.assumingMemoryBound(to: Float16.self)
            return (0 ..< n).map { Float(p[$0]) }
        case .double:
            let p = a.dataPointer.assumingMemoryBound(to: Double.self)
            return (0 ..< n).map { Float(p[$0]) }
        default:
            let p = a.dataPointer.assumingMemoryBound(to: Float.self)
            return Array(UnsafeBufferPointer(start: p, count: n))
        }
    }

    static func array(_ values: ArraySlice<Float>, shape: [Int]) throws -> MLMultiArray {
        let a = try MLMultiArray(shape: shape.map { NSNumber(value: $0) }, dataType: .float32)
        let p = a.dataPointer.assumingMemoryBound(to: Float.self)
        for (i, v) in values.enumerated() { p[i] = v }
        return a
    }

    // MARK: Loading

    private func load(_ folder: URL) async throws {
        if self.folder == folder, predictor != nil { return }
        if let loading, loading.folder == folder { return try await loading.task.value }
        unload()
        let started = epoch
        let task = Task {
            let model = try await Self.model(folder, "Kokoro_Predictor")
            let buckets = Self.decoderBuckets(in: folder)
            guard !buckets.isEmpty else { throw EngineError("UNSUPPORTED", "no Kokoro decoder in \(folder.lastPathComponent)") }
            self.loaded(folder, model, buckets, epoch: started)
        }
        loading = (folder, task)
        defer { if loading?.task == task { loading = nil } }
        try await task.value
        guard epoch == started else { throw EngineError.cancelled }
    }

    private func loaded(_ folder: URL, _ model: MLModel, _ buckets: [Int], epoch: Int) {
        guard epoch == self.epoch else { return }
        predictor = model
        self.buckets = buckets
        self.folder = folder
    }

    /// Each decoder holds ~250 MB of float32 weights: two stay loaded, one on phones with less memory.
    private static var decoderLimit: Int { ProcessInfo.processInfo.physicalMemory < 7_000_000_000 ? 1 : 2 }

    private func decoder(_ frames: Int) async throws -> MLModel {
        if let m = decoders[frames] {
            decoderOrder.removeAll { $0 == frames }
            decoderOrder.append(frames)
            return m
        }
        if let pending = decoderLoads[frames] { return try await pending.value.model }
        guard let folder else { throw EngineError("UNSUPPORTED", "the voice is not loaded") }
        while decoders.count >= Self.decoderLimit, let oldest = decoderOrder.first {
            decoderOrder.removeFirst()
            decoders[oldest] = nil
        }
        let started = epoch
        let task = Task { LoadedModel(model: try await Self.model(folder, "Kokoro_Decoder_\(frames)")) }
        decoderLoads[frames] = task
        defer { if decoderLoads[frames] == task { decoderLoads[frames] = nil } }
        let m = try await task.value.model
        guard epoch == started else { throw EngineError.cancelled }
        decoders[frames] = m
        decoderOrder.removeAll { $0 == frames }
        decoderOrder.append(frames)
        return m
    }

    private func voicePack(_ voice: String) throws -> KokoroVoicePack {
        if let v = voices[voice] { return v }
        guard let folder else { throw EngineError("UNSUPPORTED", "the voice is not loaded") }
        let safe = String(voice.filter { $0.isLetter || $0.isNumber || $0 == "_" })
        let url = folder.appending(path: "\(safe).bin")
        guard FileManager.default.fileExists(atPath: url.path) else { throw EngineError("NO_VOICE", "voice \(voice) is not installed") }
        let pack = try KokoroVoicePack(contentsOf: url)
        voices[voice] = pack
        return pack
    }

    /// A loaded model handed from its loading task to the actor (Core ML models are not Sendable;
    /// this one is only ever used on the actor).
    struct LoadedModel: @unchecked Sendable {
        let model: MLModel
    }

    /// Decoder lengths (frames) whose package is in [folder], ascending.
    static func decoderBuckets(in folder: URL) -> [Int] {
        let items = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        return Set(items.compactMap { name -> Int? in
            guard name.hasPrefix("Kokoro_Decoder_") else { return nil }
            return Int(name.dropFirst("Kokoro_Decoder_".count).split(separator: ".").first ?? "")
        }).sorted()
    }

    /// The compiled model [name] in [folder]: its `.mlmodelc`, or the `.mlpackage` compiled once and kept next to it.
    static func model(_ folder: URL, _ name: String) async throws -> MLModel {
        let fm = FileManager.default
        let compiled = folder.appending(path: "\(name).mlmodelc")
        if !fm.fileExists(atPath: compiled.path) {
            let package = folder.appending(path: "\(name).mlpackage")
            guard fm.fileExists(atPath: package.path) else { throw EngineError("UNSUPPORTED", "\(name) is missing") }
            let temp = try await MLModel.compileModel(at: package)
            try? fm.removeItem(at: compiled)
            try fm.moveItem(at: temp, to: compiled)
        }
        let config = MLModelConfiguration()
        // As converted: float16 on the Neural Engine corrupts the audio.
        #if targetEnvironment(simulator)
        // The simulator's Metal returns garbage for these float32 graphs.
        config.computeUnits = .cpuOnly
        #else
        config.computeUnits = .cpuAndGPU
        #endif
        return try await MLModel.load(contentsOf: compiled, configuration: config)
    }
}
