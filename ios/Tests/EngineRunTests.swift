#if canImport(AutobotShared)
import AutobotShared
#endif
import AVFoundation
import Foundation
import Synchronization
import Testing
import UIKit
@testable import Autobot

/**
 Running models: a missing or corrupt model gives a clear error (never a hang), image generation
 picks the benchmarked backend, cancelling stops LLM and SD work, engines make room for each
 other and go on a memory warning, and a voice interrupted by the audio system says so.
 */
@Suite(.serialized, .timeLimit(.minutes(2)))
@MainActor
struct EngineRunTests {
    private let dir: URL
    private let store: EncryptedStore
    private let library: ModelLibrary
    private let run = UUID().uuidString.prefix(8).lowercased()

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("engines-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager())
        library = ModelLibrary(store: store)
    }

    /// A ready Core ML image model whose folder holds [files] (relative paths, empty contents).
    private func imageModel(_ name: String, files: [String]) throws -> String {
        let id = "test:\(run)-\(name)"
        let manifest = ModelManifest(files: files.map { ModelFile(role: .other, name: $0, url: "https://huggingface.co/x/\($0)", sizeBytes: 1, keepName: true) })
        library.add(ModelPlan(id: id, kind: .image, source: .huggingFace, title: name, subtitle: "", format: .coreML, engine: .coreML, manifest: manifest))
        library.progress(id, downloaded: 1, status: .ready)
        for f in files {
            let url = ModelLibrary.dir(of: id).appendingPathComponent(f)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data("x".utf8).write(to: url)
        }
        return id
    }

    private func failure(_ engine: OnDeviceEngine, _ request: DiffusionRequest) async -> DiffusionError? {
        do {
            for try await _ in engine.generate(request, inputs: DiffusionInputs()) {}
            return nil
        } catch {
            return error as? DiffusionError
        }
    }

    // MARK: Missing and corrupt models

    @Test func imageGenerationWithoutAUsableModelSaysWhatToDo() async throws {
        let engine = OnDeviceEngine(library: library, llm: LocalLlm(), diffusion: LocalDiffusion())
        var request = DiffusionRequest()
        request.prompt = "a lighthouse"

        let none = await failure(engine, request)
        #expect(none?.message.contains("Models tab") == true)
        #expect(none?.code == .unsupported)

        // Downloaded, then its files went away (or never finished): download it again.
        let gone = try imageModel("gone", files: ["merges.txt"])
        request.model = gone
        let incomplete = await failure(engine, request)
        #expect(incomplete?.message == "The model files are incomplete; download it again")
        library.remove(gone)

        // Files that are not a Stable Diffusion bundle.
        let wrong = try imageModel("wrong", files: ["VAEDecoder.mlmodelc/model.mil"])
        request.model = wrong
        let corrupt = await failure(engine, request)
        #expect(corrupt?.code == .unsupported)
        #expect(corrupt?.message.contains("is not a Core ML Stable Diffusion model") == true)
        library.remove(wrong)
    }

    @Test func imageGenerationRunsWhereTheBenchmarkWasFastest() throws {
        let id = try imageModel("bench", files: ["VAEDecoder.mlmodelc/model.mil"])
        let compute = ComputeProfile(store: store, library: library)
        let factory = DiffusionEngineFactory(clients: HttpClientFactory(policy: NetworkPolicy(), auditLog: AuditLog()), llm: LocalLlm()) { compute.resolve($0) }
        let backend = DiffusionBackend(id: DiffusionBackendRepository.onDeviceId, kind: .onDevice, name: "This phone", baseUrl: "")
        var request = DiffusionRequest()
        request.model = id
        let engine = try #require(factory.create(backend, library: library) as? OnDeviceEngine)

        #expect(engine.target(request)?.backend == .auto)
        compute.record(Benchmark(modelId: id, backend: .npu, stepMs: 900))
        compute.record(Benchmark(modelId: id, backend: .gpu, stepMs: 300))
        #expect(engine.target(request)?.backend == .gpu)
        // The user's pick wins over the measurement.
        compute.setBackend(id, .npu)
        #expect(engine.target(request)?.backend == .npu)
        library.remove(id)
    }

    #if canImport(AutobotShared)
    private final class Sink: NSObject, LocalSink, @unchecked Sendable {
        let result = Mutex<String?>(nil)
        let failure = Mutex<String?>(nil)
        func onContent(text: String) {}
        func onReasoning(text: String) {}
        func onReasoningStatus(json: String) {}
        func onResult(json: String) { result.withLock { $0 = json } }
        func onFailure(message: String) { failure.withLock { $0 = message } }
        var done: Bool { result.withLock { $0 != nil } || failure.withLock { $0 != nil } }
    }

    private func errorCode(_ json: String?) -> (code: String?, message: String?) {
        let error = (json.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) } as? [String: Any])?["error"] as? [String: Any]
        return (error?["code"] as? String, error?["message"] as? String)
    }

    @Test func aMissingOrCorruptChatModelFailsWithAClearError() async throws {
        let corrupt = dir.appendingPathComponent("corrupt.gguf")
        try Data((0 ..< 4096).map { UInt8($0 % 7) }).write(to: corrupt)
        let snapshots = HostSnapshots()
        snapshots.setModels([
            "missing": .init(path: dir.appendingPathComponent("nope.gguf").path, mmproj: nil, contextLength: 2048, backend: .cpu),
            "corrupt": .init(path: corrupt.path, mmproj: nil, contextLength: 2048, backend: .cpu),
        ])
        let host = LocalEngineHost(snapshots: snapshots, llm: LocalLlm())
        let request = #"{"messages":[{"role":"user","content":"hi"}],"max_tokens":8}"#

        #expect(host.chat(modelId: "unknown", requestJson: request, sink: Sink()) == nil)
        #expect(host.modelState(modelId: "unknown") == .notInstalled)
        for id in ["missing", "corrupt"] {
            let sink = Sink()
            let handle = host.chat(modelId: id, requestJson: request, sink: sink)
            #expect(handle != nil)
            #expect(await waitFor(.seconds(30)) { sink.done })
            let (code, message) = errorCode(sink.result.withLock { $0 })
            #expect(code == "LOAD_FAILED", "\(id)")
            #expect(message?.contains("could not load model") == true, "\(id): \(message ?? "")")
        }
    }

    @Test func cancellingAnOnDeviceChatEndsItAtOnce() async throws {
        let corrupt = dir.appendingPathComponent("slow.gguf")
        try Data(repeating: 1, count: 1024).write(to: corrupt)
        let snapshots = HostSnapshots()
        snapshots.setModels(["m": .init(path: corrupt.path, mmproj: nil, contextLength: 2048, backend: .cpu)])
        let host = LocalEngineHost(snapshots: snapshots, llm: LocalLlm())
        let sink = Sink()
        let handle = try #require(host.chat(modelId: "m", requestJson: #"{"messages":[]}"#, sink: sink))
        handle.cancel()
        #expect(await waitFor(.seconds(30)) { sink.done })
        // Cancelled before it ran, or failed loading the file: either way the caller hears back.
        let code = errorCode(sink.result.withLock { $0 }).code
        #expect(code == "CANCELLED" || code == "LOAD_FAILED" || sink.failure.withLock { $0 } != nil)
    }
    #endif

    // MARK: Cancel image generation

    @Test func cancellingAnImageJobInterruptsTheServerAndStoresNothing() async throws {
        let base = "/\(run)/sdapi/v1/"
        ServeStub.on(base + "txt2img") { _ in .stall(Data()) }
        ServeStub.on(base + "progress") { _ in .body(status: 200, headers: [:], data: Data(#"{"state":{"sampling_step":3,"sampling_steps":20,"job_no":0}}"#.utf8)) }
        ServeStub.on(base + "interrupt") { _ in .body(status: 200, headers: [:], data: Data()) }
        let clients = HttpClientFactory(policy: NetworkPolicy(), auditLog: AuditLog(), protocolClasses: [ServeStub.self])
        let gallery = GalleryRepository(store: store, keys: KeyManager())
        let generator = ImageGenerator(engines: DiffusionEngineFactory(clients: clients, llm: LocalLlm()), gallery: gallery,
                                       backends: DiffusionBackendRepository(preferences: Preferences(url: dir.appendingPathComponent("p.json"))), library: library)
        let backend = DiffusionBackend(id: "pc", kind: .sdApi, name: "PC", baseUrl: "http://127.0.0.1/\(run)")
        var request = DiffusionRequest()
        request.prompt = "a lighthouse"
        generator.start(backend, request, DiffusionInputs())
        #expect(await eventually { generator.isBusy && !ServeStub.requests(base + "txt2img").isEmpty })

        generator.cancel()
        #expect(await eventually { !generator.isBusy })
        #expect(generator.state == .idle)
        #expect(generator.lastError == nil)
        #expect(gallery.items.isEmpty)
        #expect(await eventually { !ServeStub.requests(base + "interrupt").isEmpty })
    }

    // MARK: Engine memory

    private final class Unloads: Sendable {
        let list = Mutex<[ResidentEngine]>([])
        var all: [ResidentEngine] { list.withLock { $0 } }
    }

    private func memory(_ log: Unloads) -> EngineMemory {
        var unloaders: [ResidentEngine: @Sendable () async -> Void] = [:]
        for kind in ResidentEngine.allCases { unloaders[kind] = { log.list.withLock { $0.append(kind) } } }
        return EngineMemory(unloaders: unloaders)
    }

    @Test func aFailedOrCancelledRunStillGivesItsMemoryBack() async throws {
        let log = Unloads()
        let memory = memory(log)
        await #expect(throws: CancellationError.self) {
            try await EngineMemory.using(.diffusion, on: memory) { throw CancellationError() }
        }
        // Not busy any more: a memory warning unloads it at once.
        await memory.releaseAll()
        #expect(log.all == [.diffusion])
        #expect(await memory.resident.isEmpty)

        // Audio runs alone: the chat and the voice make room first.
        try await EngineMemory.using(.llm, on: memory) {}
        try await EngineMemory.using(.tts, on: memory) {}
        try await EngineMemory.using(.audio, on: memory) {}
        #expect(Set(log.all.dropFirst()) == [.llm, .tts])
        #expect(await memory.resident == [.audio])
    }

    @Test func aMemoryWarningReleasesNowAndBackgroundAfterTheGrace() async throws {
        let releases = Mutex(0)
        let ended = Mutex(0)
        let scheduler = EngineReleaseScheduler(
            grace: .milliseconds(150),
            release: { releases.withLock { $0 += 1 } },
            begin: { _, _ in UIBackgroundTaskIdentifier(rawValue: 7) },
            end: { _ in ended.withLock { $0 += 1 } },
        )
        scheduler.memoryWarning()
        #expect(await eventually { releases.withLock { $0 } == 1 })

        // A quick app switch keeps the engines.
        scheduler.enteredBackground()
        #expect(scheduler.pending)
        scheduler.becameActive()
        #expect(!scheduler.pending)
        try await Task.sleep(for: .milliseconds(300))
        #expect(releases.withLock { $0 } == 1)
        #expect(ended.withLock { $0 } == 1)

        // Staying away releases them after the grace and hands the background time back.
        scheduler.enteredBackground()
        #expect(await eventually { releases.withLock { $0 } == 2 })
        #expect(await eventually { ended.withLock { $0 } == 2 })
    }

    // MARK: Voice and audio

    @Test func voiceAndAudioWithoutTheirModelsSayWhereToGetThem() async throws {
        let empty = dir.appendingPathComponent("empty-model")
        try FileManager.default.createDirectory(at: empty, withIntermediateDirectories: true)
        do {
            _ = try await LocalVoice.shared.pieces(folder: empty, text: "hello", voice: LocalVoice.defaultVoice)
            Issue.record("expected the missing voice to fail")
        } catch let e as LocalEngineError {
            #expect(e.code == "NO_MODEL")
            #expect(e.message.contains("Models"))
        }
        do {
            _ = try await LocalAudioGen.shared.ids(folder: empty, prompt: "drums")
            Issue.record("expected the missing audio model to fail")
        } catch let e as LocalEngineError {
            #expect(e.code == "NO_MODEL")
            #expect(e.message.contains("Models"))
        }
        // The stream form ends with the error instead of hanging the player.
        await #expect(throws: (any Error).self) {
            for try await _ in LocalVoice.shared.synthesize(folder: empty, text: "hello") {}
        }
    }

    /// An output that never plays anything back (no audio hardware in a test).
    private final class SilentOutput: VoiceOutput {
        var started = 0, halted = 0, stopped = 0, scheduled = 0
        var engine: AVAudioEngine? { nil }
        func start() throws { started += 1 }
        func halt() { halted += 1 }
        func stop() { stopped += 1 }
        func schedule(_ buffer: AVAudioPCMBuffer, completion: @escaping @Sendable (AVAudioPlayerNodeCompletionCallbackType) -> Void) { scheduled += 1 }
    }

    @Test func playbackInterruptedByTheAudioSystemThrowsInsteadOfWaiting() async throws {
        let output = SilentOutput()
        let player = VoicePlayer(output: output)
        let (stream, feed) = AsyncThrowingStream<[Float], Error>.makeStream()
        // A second of audio, then the synthesizer is still working on the next sentence.
        feed.yield([Float](repeating: 0, count: LocalVoice.sampleRate))
        let playing = Task { @MainActor in try await player.play(stream) }
        #expect(await eventually { output.scheduled == 1 })
        NotificationCenter.default.post(name: AVAudioSession.interruptionNotification, object: nil,
                                        userInfo: [AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.began.rawValue])
        #expect(await eventually { output.halted == 1 })
        feed.yield([Float](repeating: 0, count: 2400))
        feed.finish()
        do {
            try await playing.value
            Issue.record("expected the interruption to end playback with an error")
        } catch let e as VoiceInterrupted {
            #expect(e.message == "Playback was interrupted")
        }
        #expect(output.scheduled == 1)
        #expect(output.stopped == 1)

        // A reset of the media services ends the next playback the same way.
        let (again, more) = AsyncThrowingStream<[Float], Error>.makeStream()
        more.yield([Float](repeating: 0, count: 2400))
        let second = Task { @MainActor in try await player.play(again) }
        #expect(await eventually { output.scheduled == 2 })
        NotificationCenter.default.post(name: AVAudioSession.mediaServicesWereResetNotification, object: nil)
        more.finish()
        await #expect(throws: VoiceInterrupted(message: "The audio system restarted")) { try await second.value }
    }

    @Test func playbackStopsWaitingWhenTheAudioNeverComesBack() async throws {
        // Buffers whose completion never comes (the output died silently): play still returns.
        let output = SilentOutput()
        let player = VoicePlayer(output: output)
        let (stream, feed) = AsyncThrowingStream<[Float], Error>.makeStream()
        feed.yield([Float](repeating: 0, count: LocalVoice.sampleRate / 10))
        feed.finish()
        let started = ContinuousClock.now
        try await player.play(stream)
        let waited = ContinuousClock.now - started
        #expect(waited >= VoicePlayer.drainGrace && waited < VoicePlayer.drainGrace + .seconds(5))
        #expect(output.stopped == 1)
    }
}
