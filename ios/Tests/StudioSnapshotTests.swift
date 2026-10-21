import AutobotShared
import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 Renders every Studio room and the library (dark and light) with sample projects, assets, jobs and
 workflows into PNGs for visual review. Skipped unless `AUTOBOT_SNAPSHOT_DIR` is set (pass it as
 `TEST_RUNNER_AUTOBOT_SNAPSHOT_DIR=… xcodebuild test`). Sample files are removed afterwards.
 */
@MainActor
struct StudioSnapshotTests {
    private var dir: String? { ProcessInfo.processInfo.environment["AUTOBOT_SNAPSHOT_DIR"] }

    @Test(.timeLimit(.minutes(5))) func renderStudioRooms() async throws {
        guard let dir else { return }
        let app = AppContainer()
        let nav = Navigator()
        let studio = app.studio
        let codec = StudioCodec.shared
        let project = StudioProject(id: "p-snap00000001", name: "Lighthouse", coverAssetId: nil, createdAt: 1, updatedAt: 2)
        func recipe(_ engine: String, _ params: [String: ParamValue], output: AssetType, seed: Int64 = 41_822) -> Recipe {
            Recipe(engine: engine, backendId: nil, model: nil, params: params, inputs: [], workflowId: nil, workflowVersion: nil, seed: KotlinLong(value: seed), output: output)
        }
        var assets: [StudioAsset] = []
        let prompts = ["a lighthouse at dusk, risograph", "storm over the cliffs", "fog bank, sodium lamps", "harbour at dawn", "night ferry", "keeper's cottage"]
        for (i, prompt) in prompts.enumerated() {
            let id = String(format: "a-snap%07d", i)
            let png = try #require(Self.scene(hue: [0.02, 0.58, 0.1, 0.95, 0.66, 0.08][i], seed: i).pngData())
            try studio.files.save(id, png, mimeType: "image/png")
            assets.append(StudioAsset(id: id, projectId: project.id, type: .image,
                                      recipe: recipe("diffusion", ["prompt": .text(prompt), "steps": .int(28), "cfg": .double(6.5)], output: .image, seed: Int64(41_822 + i)),
                                      createdAt: Int64(100 + i), mimeType: "image/png", sizeBytes: Int64(png.count), durationMs: 0, width: 768, height: 768,
                                      favorite: i == 1, parentId: nil, label: nil))
        }
        for (i, prompt) in ["rain on a tin roof, distant thunder", "gulls over a harbour"].enumerated() {
            let id = String(format: "a-snapa%06d", i)
            let wav = StudioAudio.wav(mono: Self.tone(seconds: 4, seed: i), sampleRate: 24000)
            try studio.files.save(id, wav, mimeType: "audio/wav")
            assets.append(StudioAsset(id: id, projectId: project.id, type: .audio,
                                      recipe: recipe("audio", ["prompt": .text(prompt), "seconds": .double(4), "steps": .int(8)], output: .audio),
                                      createdAt: Int64(200 + i), mimeType: "audio/wav", sizeBytes: Int64(wav.count), durationMs: 4000, width: 0, height: 0,
                                      favorite: false, parentId: nil, label: nil))
        }
        var workflows: [StudioWorkflow] = []
        if let video = try? WorkflowImport.shared.create(name: "LTX-Video 0.9 · t2v", json: Self.videoGraph, info: nil, id: "w-snapvideo001", now: 1, backendId: "pc") {
            workflows.append(video)
        }
        if let image = try? WorkflowImport.shared.create(name: "SDXL portrait", json: Self.imageGraph, info: nil, id: "w-snapimage001", now: 1, backendId: "pc") {
            workflows.append(image)
        }
        studio.library.load(.init(
            projects: [codec.encodeProject(value: project)],
            assets: assets.map { codec.encodeAsset(value: $0) },
            workflows: workflows.map { codec.encodeWorkflow(value: $0) },
            snippets: [],
        ))
        func job(_ id: String, _ r: Recipe, _ state: JobState, progress: Float = 0, step: Int32 = 0, steps: Int32 = 0, status: String? = nil, error: String? = nil, outputs: [String] = []) -> StudioJob {
            StudioJob(id: id, projectId: project.id, recipe: r, label: nil, state: state, progress: progress, step: step, steps: steps, status: status, outputs: outputs,
                      error: error, parentAssetId: nil, createdAt: 1, startedAt: nil, finishedAt: nil, preview: nil)
        }
        let jobs = [
            job("j-1", recipe("diffusion", ["prompt": .text(prompts[0])], output: .image), .done, progress: 1, outputs: [assets[0].id]),
            job("j-2", recipe("diffusion", ["prompt": .text("a lighthouse, {dawn|dusk}")], output: .image), .running, progress: 0.45, step: 9, steps: 20),
            job("j-3", recipe("audio", ["prompt": .text("rain on a tin roof")], output: .audio), .queued),
            job("j-4", recipe("comfyui", ["prompt": .text("slow dolly through fog")], output: .video), .failed, error: "Missing models: ltx-video-2b.safetensors"),
        ]
        let servers = [ComfyServer(id: "pc", name: "Studio PC", baseUrl: "https://studio-pc.tail0.ts.net")]
        studio.preview(jobs: jobs, engines: ["diffusion": ["This iPhone"], "audio": ["This iPhone"], "speech": ["This iPhone"], "comfyui": ["Studio PC"]],
                       servers: servers, projectId: project.id)
        app.backends.save(DiffusionBackend(id: "snap-forge", kind: .sdApi, name: "Forge", baseUrl: "https://forge.tail0.ts.net"))
        app.backends.setDefault("snap-forge")
        defer {
            app.backends.delete("snap-forge")
            for a in assets { studio.files.delete(a.id) }
        }

        for mode in [Autobot.ThemeMode.dark, .light] {
            let suffix = mode == .dark ? "dark" : "light"
            let only = ProcessInfo.processInfo.environment["AUTOBOT_SNAPSHOT_ROOMS"].map { $0.split(separator: ",").map(String.init) }
            for room in StudioRoom.allCases where only?.contains(room.rawValue) ?? true {
                nav.studio = room
                try await snapshot(StudioScreen(), app, nav, mode, "\(dir)/studio-\(room.rawValue)-\(suffix).png")
            }
            nav.studio = .library
            try await snapshot(CompareView(a: assets[0], b: assets[1], player: StudioAudioPlayer()) {}, app, nav, mode, "\(dir)/studio-compare-\(suffix).png")
            try await snapshot(GridSample(run: GridRun(id: "g", title: "steps × cfg", columns: ["10", "20", "30"], rows: ["4", "7"], xLabel: "steps", yLabel: "cfg",
                                                       jobIds: [["j-1", "j-2", "j-3"], ["j-1", "j-4", "j-1"]], truncated: false)),
                               app, nav, mode, "\(dir)/studio-grid-\(suffix).png")
        }
    }

    private struct GridSample: View {
        let run: GridRun

        var body: some View {
            VStack(alignment: .leading) {
                GridResultView(run: run)
                Spacer()
            }
            .padding(16)
            .background(Color(.systemBackground))
        }
    }

    private func snapshot(_ view: some View, _ app: AppContainer, _ nav: Navigator, _ mode: Autobot.ThemeMode, _ path: String) async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.overrideUserInterfaceStyle = mode == .dark ? .dark : .light
        let root = view.environment(app).environment(nav).autobotTheme(mode)
        window.rootViewController = UIHostingController(rootView: root)
        window.makeKeyAndVisible()
        try await Task.sleep(for: .milliseconds(1800))
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        try #require(image.pngData()).write(to: URL(fileURLWithPath: path))
        window.isHidden = true
    }

    /// A sample "photo": sky gradient, sea, a lighthouse.
    static func scene(hue: CGFloat, seed: Int) -> UIImage {
        let side = 768
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { ctx in
            let g = ctx.cgContext
            let colors = [UIColor(hue: hue, saturation: 0.55, brightness: 0.95, alpha: 1).cgColor, UIColor(hue: hue + 0.1, saturation: 0.7, brightness: 0.35, alpha: 1).cgColor]
            let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors as CFArray, locations: [0, 1])!
            g.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 0, y: side), options: [])
            UIColor(white: 0.08, alpha: 1).setFill()
            g.fill(CGRect(x: 0, y: side * 2 / 3, width: side, height: side / 3))
            let x = CGFloat(200 + seed * 60)
            g.fill(CGRect(x: x, y: 230, width: 60, height: 300))
            UIColor(white: 0.95, alpha: 1).setFill()
            g.fillEllipse(in: CGRect(x: x - 10, y: 200, width: 80, height: 50))
        }
    }

    static func tone(seconds: Double, seed: Int) -> [Float] {
        let rate = 24000.0
        return (0 ..< Int(seconds * rate)).map { i in
            let t = Double(i) / rate
            let env = Float(0.5 + 0.5 * sin(t * Double(2 + seed) * 1.7))
            return Float(sin(t * 2 * .pi * 220)) * env * 0.6 + Float.random(in: -0.15 ... 0.15) * env
        }
    }

    static let imageGraph = """
    {"4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "sd_xl_base_1.0.safetensors"}},
     "5": {"class_type": "EmptyLatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
     "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "portrait, rim light", "clip": ["4", 1]}},
     "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "blurry", "clip": ["4", 1]}},
     "3": {"class_type": "KSampler", "inputs": {"seed": 5, "steps": 30, "cfg": 6.0, "sampler_name": "dpmpp_2m", "scheduler": "karras", "denoise": 1.0,
           "model": ["4", 0], "positive": ["6", 0], "negative": ["7", 0], "latent_image": ["5", 0]}},
     "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
     "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "autobot", "images": ["8", 0]}}}
    """

    static let videoGraph = """
    {"1": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "ltx-video-2b-v0.9.safetensors"}},
     "2": {"class_type": "CLIPTextEncode", "inputs": {"text": "a slow dolly through fog", "clip": ["1", 1]}},
     "3": {"class_type": "CLIPTextEncode", "inputs": {"text": "static, flicker", "clip": ["1", 1]}},
     "4": {"class_type": "EmptyLTXVLatentVideo", "inputs": {"width": 768, "height": 512, "length": 97, "batch_size": 1}},
     "5": {"class_type": "LTXVConditioning", "inputs": {"positive": ["2", 0], "negative": ["3", 0], "frame_rate": 25}},
     "6": {"class_type": "KSampler", "inputs": {"seed": 7, "steps": 30, "cfg": 3.0, "sampler_name": "euler", "scheduler": "simple", "denoise": 1.0,
           "model": ["1", 0], "positive": ["5", 0], "negative": ["5", 1], "latent_image": ["4", 0]}},
     "7": {"class_type": "VAEDecode", "inputs": {"samples": ["6", 0], "vae": ["1", 2]}},
     "8": {"class_type": "SaveAnimatedWEBP", "inputs": {"filename_prefix": "autobot", "fps": 25, "lossless": false, "quality": 85, "method": "default", "images": ["7", 0]}}}
    """
}
