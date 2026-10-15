import CoreML
import Foundation

/**
 Stable Audio Open Small (Stability AI, Stability AI Community License — "Powered by Stability AI")
 on Core ML: text to up to 11.9 s of 44.1 kHz stereo. `mlboydaisuke/Stable-Audio-Open-Small-CoreML`
 ships four stateless packages driven from here: T5-base encoder (8-bit, CPU + GPU), the
 `seconds_total` embedder, the 341M DiT (float32, CPU only — as converted; float16 attention
 overflows) and the Oobleck VAE decoder (CPU + GPU). The sampler is Stability's `stable-audio-tools`
 ping-pong sampler for the distilled model (8 steps, no guidance by default) and mirrors the shared
 Kotlin pipeline (`dev.wckdboy.autobot.shared.audiogen.StableAudio`) number for number, noise
 included, so a seed means the same on both apps. Text → token ids is the shared `T5Tokenizer`;
 this engine only sees ids. Stages load one at a time and, unless [keepLoaded], are released after
 each generation (two large Core ML models at once can exhaust a phone's memory). Packages compile
 on first use and are kept next to them. Nothing is fetched or sent.
 */
public actor AudioGenEngine {
    public static let shared = AudioGenEngine()

    public static let sampleRate = 44_100
    public static let channels = 2
    public static let latentChannels = 64
    public static let latentLength = 256
    public static let latentSize = latentChannels * latentLength
    /// Samples per channel the decoder returns (11.89 s).
    public static let samples = latentLength * 2048
    public static let maxSeconds = Double(samples) / Double(sampleRate)
    public static let textTokens = 64
    public static let condDim = 768
    public static let condTokens = textTokens + 1
    public static let defaultSteps = 8
    public static let maxSteps = 50

    static let textEncoder = "StableAudioT5Encoder"
    static let secondsEmbedder = "StableAudioNumberEmbedder"
    static let dit = "StableAudioDiT"
    static let decoder = "StableAudioVAEDecoder"

    /// A generation. [tokens] are T5 ids closed with `</s>` (1), without padding (longer lists keep their first 63 and `</s>`).
    public struct Request: Sendable {
        public var tokens: [Int32]
        public var seconds: Double
        public var steps: Int
        public var cfg: Float
        public var seed: UInt64

        public init(tokens: [Int32], seconds: Double = 10, steps: Int = AudioGenEngine.defaultSteps, cfg: Float = 1, seed: UInt64 = 0) {
            self.tokens = tokens
            self.seconds = seconds
            self.steps = steps
            self.cfg = cfg
            self.seed = seed
        }
    }

    /// Where a generation is; [step] counts finished DiT steps.
    public struct Progress: Sendable, Equatable {
        public enum Stage: String, Sendable { case encoding, sampling, decoding, done }
        public let stage: Stage
        public let step: Int
        public let steps: Int

        /// 0…1 overall (encoding ~5 %, sampling ~75 %, decoding ~20 %), as the shared `AudioGenProgress`.
        public var fraction: Double {
            switch stage {
            case .encoding: 0
            case .sampling: 0.05 + 0.75 * Double(step) / Double(max(steps, 1))
            case .decoding: 0.8
            case .done: 1
            }
        }
    }

    /// 44.1 kHz stereo float PCM, one array per channel.
    public struct Audio: Sendable {
        public let left: [Float]
        public let right: [Float]
        public var sampleRate: Int { AudioGenEngine.sampleRate }
        public var frames: Int { left.count }
        public var seconds: Double { Double(frames) / Double(sampleRate) }

        /// Left/right interleaved.
        public var interleaved: [Float] {
            var out = [Float](repeating: 0, count: frames * 2)
            for i in 0 ..< frames {
                out[2 * i] = left[i]
                out[2 * i + 1] = right[i]
            }
            return out
        }

        /// A 16-bit stereo WAV file (as the shared `Wav.encode16`).
        public func wav() -> Data {
            var d = Data(capacity: 44 + frames * 4)
            func le32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
            func le16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
            let bytes = UInt32(frames * 4)
            d.append(contentsOf: Array("RIFF".utf8)); le32(36 + bytes); d.append(contentsOf: Array("WAVEfmt ".utf8))
            le32(16); le16(1); le16(2); le32(UInt32(sampleRate)); le32(UInt32(sampleRate * 4)); le16(4); le16(16)
            d.append(contentsOf: Array("data".utf8)); le32(bytes)
            var pcm = [Int16](repeating: 0, count: frames * 2)
            for i in 0 ..< frames {
                pcm[2 * i] = AudioGenEngine.sample16(left[i])
                pcm[2 * i + 1] = AudioGenEngine.sample16(right[i])
            }
            // Apple platforms are little-endian, as WAV.
            pcm.withUnsafeBytes { d.append(contentsOf: $0) }
            return d
        }
    }

    private var folder: URL?
    private var models: [String: MLModel] = [:]
    /// Keep every stage loaded between generations (faster repeats; ~1.7 GB resident).
    public var keepLoaded: Bool

    public init(keepLoaded: Bool = false) {
        self.keepLoaded = keepLoaded
    }

    public func setKeepLoaded(_ keep: Bool) {
        keepLoaded = keep
        if !keep { models = [:] }
    }

    /// Whether [folder] holds the four packages (or their compiled forms).
    public static func installed(in folder: URL) -> Bool {
        let fm = FileManager.default
        return [textEncoder, secondsEmbedder, dit, decoder].allSatisfy { name in
            fm.fileExists(atPath: folder.appending(path: "\(name).mlmodelc").path) || fm.fileExists(atPath: folder.appending(path: "\(name).mlpackage").path)
        }
    }

    /// Compiles the packages now (first use otherwise pays for it: a minute or more for the DiT).
    public func prepare(folder: URL) async throws {
        for name in [Self.textEncoder, Self.secondsEmbedder, Self.dit, Self.decoder] {
            _ = try await Self.compiled(folder, name)
        }
    }

    /// Frees every model.
    public func unload() {
        models = [:]
        folder = nil
    }

    /// Audio for [request]; [onProgress] runs on this actor per stage and DiT step. Cancellation stops between model calls.
    public func generate(folder: URL, request: Request, onProgress: @Sendable (Progress) -> Void = { _ in }) async throws -> Audio {
        guard (1 ... Self.maxSteps).contains(request.steps) else { throw EngineError("BAD_REQUEST", "steps must be 1…\(Self.maxSteps)") }
        guard request.cfg >= 0, request.cfg <= 20 else { throw EngineError("BAD_REQUEST", "cfg must be 0…20") }
        guard Self.installed(in: folder) else { throw EngineError("UNSUPPORTED", "Stable Audio Open Small is not installed") }
        if self.folder != folder { models = [:] }
        self.folder = folder
        defer { if !keepLoaded { models = [:] } }
        let steps = request.steps
        let seconds = Self.clampSeconds(request.seconds)
        onProgress(Progress(stage: .encoding, step: 0, steps: steps))

        // Text and seconds conditioning.
        let (ids, mask) = Self.padded(request.tokens)
        let t5 = try await model(Self.textEncoder, units: .cpuAndGPU)
        let text = try Self.output(t5, ["input_ids": Self.ints(ids, shape: [1, Self.textTokens], like: t5, "input_ids")], "text_embeddings")
        release(Self.textEncoder)
        try Task.checkCancellation()
        let embedder = try await model(Self.secondsEmbedder, units: .cpuAndGPU)
        let secondsEmbedding = try Self.output(embedder, [
            "normalized_seconds": Self.array([Self.normalizedSeconds(seconds)], shape: [1], like: embedder, "normalized_seconds"),
        ], "seconds_embedding")
        let cross = Self.crossConditioning(text, mask: mask, seconds: secondsEmbedding)
        try Task.checkCancellation()

        // Ping-pong sampling.
        let dit = try await model(Self.dit, units: .cpuOnly)
        let crossArray = try Self.array(cross, shape: [1, Self.condTokens, Self.condDim], like: dit, "cross_attn_cond")
        let nullCross = request.cfg != 1 ? try Self.array([Float](repeating: 0, count: cross.count), shape: [1, Self.condTokens, Self.condDim], like: dit, "cross_attn_cond") : nil
        let global = try Self.array(secondsEmbedding, shape: [1, Self.condDim], like: dit, "global_embed")
        let schedule = Self.schedule(steps)
        var noise = GaussianNoise(seed: request.seed)
        var x = noise.fill(Self.latentSize)
        onProgress(Progress(stage: .sampling, step: 0, steps: steps))
        for i in 0 ..< steps {
            let t = schedule[i]
            func velocity(_ c: MLMultiArray) throws -> [Float] {
                try Self.output(dit, [
                    "latent": try Self.array(x, shape: [1, Self.latentChannels, Self.latentLength], like: dit, "latent"),
                    "timestep": try Self.array([t], shape: [1], like: dit, "timestep"),
                    "cross_attn_cond": c,
                    "global_embed": global,
                ], "velocity")
            }
            let v = try velocity(crossArray)
            try Task.checkCancellation()
            let vu = try nullCross.map { try velocity($0) }
            try Task.checkCancellation()
            let denoised = Self.denoised(x, t: t, velocity: v, uncond: vu, cfg: request.cfg)
            x = Self.pingPong(denoised, tNext: schedule[i + 1], noise: &noise)
            onProgress(Progress(stage: .sampling, step: i + 1, steps: steps))
        }
        if !keepLoaded { release(Self.dit) }

        // Decode.
        onProgress(Progress(stage: .decoding, step: steps, steps: steps))
        let vae = try await model(Self.decoder, units: .cpuAndGPU)
        let decoded = try Self.output(vae, ["latent": try Self.array(x, shape: [1, Self.latentChannels, Self.latentLength], like: vae, "latent")], "audio")
        guard decoded.count == Self.channels * Self.samples else { throw EngineError("BAD_RESULT", "the decoder returned \(decoded.count) samples") }
        let audio = Self.clip(decoded, seconds: seconds)
        onProgress(Progress(stage: .done, step: steps, steps: steps))
        return audio
    }

    // MARK: Sampler (mirrors shared/.../audiogen/StableAudio.kt)

    /// `steps + 1` noise levels from 1 to 0: `sigmoid(8.2 · u - 2)` over `u = linspace(1, 0)` (LogSNRShift, rate 0), end points exact.
    static func schedule(_ steps: Int) -> [Float] {
        (0 ... steps).map { i in
            if i == 0 { return 1 }
            if i == steps { return 0 }
            let u = 1 - Double(i) / Double(steps)
            return Float(1 / (1 + exp(-(8.2 * u - 2))))
        }
    }

    static func clampSeconds(_ s: Double) -> Double { s.isNaN ? maxSeconds : min(max(s, 0.1), maxSeconds) }

    /// `seconds_total` scaled from the conditioner's 0…256 to 0…1.
    static func normalizedSeconds(_ s: Double) -> Float { Float(min(max(s, 0), 256) / 256) }

    static func frames(_ seconds: Double) -> Int { min(max(Int((clampSeconds(seconds) * Double(sampleRate)).rounded()), 1), samples) }

    /// Ids padded with `<pad>` to 64 (or cut to 63 + `</s>`), and the attention mask.
    static func padded(_ tokens: [Int32]) -> (ids: [Int32], mask: [Int32]) {
        var ids = tokens.isEmpty ? [1] : tokens
        if ids.count > textTokens { ids = Array(ids.prefix(textTokens - 1)) + [1] }
        let n = ids.count
        return (ids + [Int32](repeating: 0, count: textTokens - n), (0 ..< textTokens).map { $0 < n ? 1 : 0 })
    }

    /// `[65, 768]`: the text encoding with padding (and non-finite values) zeroed, then the seconds embedding.
    static func crossConditioning(_ text: [Float], mask: [Int32], seconds: [Float]) -> [Float] {
        var out = [Float](repeating: 0, count: condTokens * condDim)
        for t in 0 ..< textTokens where mask[t] != 0 {
            for c in 0 ..< condDim {
                let v = text[t * condDim + c]
                out[t * condDim + c] = v.isFinite ? v : 0
            }
        }
        for c in 0 ..< condDim { out[textTokens * condDim + c] = seconds[c] }
        return out
    }

    /// `x - t · v`, with APG guidance (the difference's part orthogonal to the conditional prediction) when [cfg] ≠ 1.
    static func denoised(_ x: [Float], t: Float, velocity v: [Float], uncond vu: [Float]?, cfg: Float) -> [Float] {
        let n = x.count
        var cond = [Float](repeating: 0, count: n)
        for i in 0 ..< n { cond[i] = x[i] - t * v[i] }
        guard let vu, cfg != 1 else { return cond }
        var diff = [Double](repeating: 0, count: n)
        var norm = 0.0
        for i in 0 ..< n {
            diff[i] = Double(cond[i] - (x[i] - t * vu[i]))
            norm += Double(cond[i]) * Double(cond[i])
        }
        norm = max(norm.squareRoot(), 1e-12)
        var dot = 0.0
        for i in 0 ..< n { dot += diff[i] * (Double(cond[i]) / norm) }
        let scale = Double(cfg) - 1
        return (0 ..< n).map { i in Float(Double(cond[i]) + scale * (diff[i] - dot * Double(cond[i]) / norm)) }
    }

    /// One ping-pong step: re-noise the denoised latent to [tNext] (the last step returns it as is).
    static func pingPong(_ denoised: [Float], tNext: Float, noise: inout GaussianNoise) -> [Float] {
        if tNext == 0 { return denoised }
        return denoised.map { (1 - tNext) * $0 + tNext * noise.next() }
    }

    /// Planar `[2, 524288]` → [seconds] of audio: trimmed, 5 ms fade-out, peak-normalized unless silent.
    static func clip(_ decoded: [Float], seconds: Double, normalize: Bool = true) -> Audio {
        let n = frames(seconds)
        var left = Array(decoded[0 ..< n])
        var right = Array(decoded[samples ..< samples + n])
        let fade = min(n, sampleRate / 200)
        for i in 0 ..< fade {
            let g = Float(i) / Float(fade)
            left[n - 1 - i] *= g
            right[n - 1 - i] *= g
        }
        if normalize {
            var peak: Float = 0
            for v in left where v.isFinite { peak = max(peak, abs(v)) }
            for v in right where v.isFinite { peak = max(peak, abs(v)) }
            if peak > 1e-4 {
                let g = 1 / peak
                func scaled(_ v: Float) -> Float { let s = v * g; return s.isFinite ? min(max(s, -1), 1) : 0 }
                left = left.map(scaled)
                right = right.map(scaled)
            }
        }
        return Audio(left: left, right: right)
    }

    /// Rounds half up, as Kotlin's `roundToInt`.
    static func sample16(_ v: Float) -> Int16 { v.isFinite ? Int16((min(max(v, -1), 1) * 32767 + 0.5).rounded(.down)) : 0 }

    // MARK: Core ML

    private func model(_ name: String, units: MLComputeUnits) async throws -> MLModel {
        if let m = models[name] { return m }
        guard let folder else { throw EngineError("UNSUPPORTED", "the audio model is not loaded") }
        let url = try await Self.compiled(folder, name)
        let config = MLModelConfiguration()
        #if targetEnvironment(simulator)
        config.computeUnits = .cpuOnly
        #else
        config.computeUnits = units
        #endif
        let m: MLModel
        do { m = try await MLModel.load(contentsOf: url, configuration: config) } catch { throw EngineError("LOAD_FAILED", "\(name): \(error.localizedDescription)") }
        models[name] = m
        return m
    }

    private func release(_ name: String) {
        if !keepLoaded { models[name] = nil }
    }

    /// [name]'s `.mlmodelc` in [folder], compiled once from the `.mlpackage` and kept next to it.
    static func compiled(_ folder: URL, _ name: String) async throws -> URL {
        let fm = FileManager.default
        let compiled = folder.appending(path: "\(name).mlmodelc")
        if fm.fileExists(atPath: compiled.path) { return compiled }
        let package = folder.appending(path: "\(name).mlpackage")
        guard fm.fileExists(atPath: package.path) else { throw EngineError("UNSUPPORTED", "\(name) is missing") }
        let temp = try await MLModel.compileModel(at: package)
        try? fm.removeItem(at: compiled)
        try fm.moveItem(at: temp, to: compiled)
        return compiled
    }

    /// Runs [model] and returns [name]'s values in logical (row-major) order.
    static func output(_ model: MLModel, _ inputs: [String: MLMultiArray], _ name: String) throws -> [Float] {
        let out = try model.prediction(from: MLDictionaryFeatureProvider(dictionary: inputs))
        guard let a = out.featureValue(for: name)?.multiArrayValue else { throw EngineError("BAD_RESULT", "\(name) is missing from the output") }
        return values(a)
    }

    /// The input type [model] declares for [input] (float32 unless it says float16).
    static func dataType(_ model: MLModel, _ input: String) -> MLMultiArrayDataType {
        model.modelDescription.inputDescriptionsByName[input]?.multiArrayConstraint?.dataType ?? .float32
    }

    static func array(_ values: [Float], shape: [Int], like model: MLModel, _ input: String) throws -> MLMultiArray {
        try array(values, shape: shape, type: dataType(model, input))
    }

    static func array(_ values: [Float], shape: [Int], type: MLMultiArrayDataType) throws -> MLMultiArray {
        let a = try MLMultiArray(shape: shape.map { NSNumber(value: $0) }, dataType: type == .float16 ? .float16 : .float32)
        switch a.dataType {
        case .float16:
            let p = a.dataPointer.assumingMemoryBound(to: Float16.self)
            for (i, v) in values.enumerated() { p[i] = Float16(v) }
        default:
            let p = a.dataPointer.assumingMemoryBound(to: Float.self)
            values.withUnsafeBufferPointer { p.update(from: $0.baseAddress!, count: values.count) }
        }
        return a
    }

    static func ints(_ values: [Int32], shape: [Int], like model: MLModel, _ input: String) throws -> MLMultiArray {
        let type = dataType(model, input)
        if type == .float32 || type == .float16 || type == .double { return try array(values.map(Float.init), shape: shape, type: type) }
        let a = try MLMultiArray(shape: shape.map { NSNumber(value: $0) }, dataType: .int32)
        let p = a.dataPointer.assumingMemoryBound(to: Int32.self)
        for (i, v) in values.enumerated() { p[i] = v }
        return a
    }

    /// [a]'s values in row-major order, honouring its strides.
    static func values(_ a: MLMultiArray) -> [Float] {
        let shape = a.shape.map(\.intValue), strides = a.strides.map(\.intValue)
        let count = shape.reduce(1, *)
        func read<T>(_ type: T.Type, _ f: (T) -> Float) -> [Float] {
            let p = a.dataPointer.assumingMemoryBound(to: T.self)
            var contiguous = true
            var expected = 1
            for d in stride(from: shape.count - 1, through: 0, by: -1) {
                if shape[d] > 1 && strides[d] != expected { contiguous = false }
                expected *= shape[d]
            }
            if contiguous { return (0 ..< count).map { f(p[$0]) } }
            var out = [Float](repeating: 0, count: count)
            var index = [Int](repeating: 0, count: shape.count)
            for i in 0 ..< count {
                var offset = 0
                for d in 0 ..< shape.count { offset += index[d] * strides[d] }
                out[i] = f(p[offset])
                var d = shape.count - 1
                while d >= 0 {
                    index[d] += 1
                    if index[d] < shape[d] { break }
                    index[d] = 0
                    d -= 1
                }
            }
            return out
        }
        switch a.dataType {
        case .float16: return read(Float16.self) { Float($0) }
        case .double: return read(Double.self) { Float($0) }
        case .int32: return read(Int32.self) { Float($0) }
        default: return read(Float.self) { $0 }
        }
    }
}

/**
 Seeded standard normal noise, the same stream as the shared Kotlin `GaussianNoise`: SplitMix64 →
 53-bit uniforms in (0, 1] → Box–Muller in double precision (cosine, then sine of each pair).
 */
public struct GaussianNoise: Sendable {
    private var state: UInt64
    private var spare: Double?

    public init(seed: UInt64) { state = seed }

    public mutating func nextUInt64() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /// Uniform in (0, 1].
    public mutating func nextUniform() -> Double { Double((nextUInt64() >> 11) + 1) * (1.0 / 9_007_199_254_740_992.0) }

    public mutating func next() -> Float {
        if let s = spare {
            spare = nil
            return Float(s)
        }
        let r = (-2 * log(nextUniform())).squareRoot()
        let theta = 2 * Double.pi * nextUniform()
        spare = r * sin(theta)
        return Float(r * cos(theta))
    }

    public mutating func fill(_ count: Int) -> [Float] { (0 ..< count).map { _ in next() } }
}
