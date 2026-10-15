import AutobotShared
import AVFoundation
import Foundation
import Testing
import UIKit
@testable import Autobot

/// The iOS Studio: records and files encrypted at rest, recipes ↔ requests, audio helpers, outpaint, share routing.
@MainActor
struct StudioTests {
    private func tempDir() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("studio-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @Test func storeHostRoundTripsRecordsAndReportsChanges() throws {
        let dir = tempDir()
        let store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager())
        let changes = Changes()
        let host = StudioStoreHost(store: store) { kind, id, json in changes.add("\(kind):\(id):\(json == nil ? "-" : "+")") }
        let project = StudioProject(id: "p-1", name: "Sketches", coverAssetId: nil, createdAt: 1, updatedAt: 2)
        host.put(kind: "projects", id: "p-1", json: StudioCodec.shared.encodeProject(value: project))
        let rows = host.records(kind: "projects")
        #expect(rows.count == 1)
        #expect(try StudioCodec.shared.decodeProject(json: rows[0]).name == "Sketches")
        host.remove(kind: "projects", id: "p-1")
        #expect(host.records(kind: "projects").isEmpty)
        #expect(changes.list == ["projects:p-1:+", "projects:p-1:-"])
        // Rows are sealed: the plain name is nowhere in the database file.
        host.put(kind: "projects", id: "p-2", json: StudioCodec.shared.encodeProject(value: project))
        let raw = try Data(contentsOf: dir.appendingPathComponent("db.sqlite"))
        #expect(raw.range(of: Data("Sketches".utf8)) == nil)
    }

    @Test func libraryMirrorsChangesNewestFirst() {
        let library = StudioLibrary()
        let codec = StudioCodec.shared
        func asset(_ id: String, _ t: Int64) -> String {
            codec.encodeAsset(value: StudioAsset(id: id, projectId: "p", type: .image, recipe: Recipe(engine: "diffusion", backendId: nil, model: nil, params: [:], inputs: [],
                                                                                                         workflowId: nil, workflowVersion: nil, seed: nil, output: .image),
                                                 createdAt: t, mimeType: "image/png", sizeBytes: 1, durationMs: 0, width: 1, height: 1, favorite: false, parentId: nil, label: nil))
        }
        library.load(.init(assets: [asset("a-1", 1)]))
        library.apply(kind: "assets", id: "a-2", json: asset("a-2", 5))
        #expect(library.assets.map(\.id) == ["a-2", "a-1"])
        library.apply(kind: "assets", id: "a-1", json: nil)
        #expect(library.assets.map(\.id) == ["a-2"])
    }

    @Test func filesAreSealedWithThumbnailsAndDeleted() throws {
        let dir = tempDir()
        let files = StudioFilesHost(keys: KeyManager(), root: dir)
        let png = try #require(Self.image(64, 48).pngData())
        try files.save("a-0123456789ab", png, mimeType: "image/png")
        #expect(files.bytes("a-0123456789ab") == png)
        #expect(files.thumbnail("a-0123456789ab") != nil)
        let sealed = try Data(contentsOf: dir.appendingPathComponent("a-0123456789ab.bin"))
        #expect(sealed != png)
        files.putWaveform("a-0123456789ab", [0, 0.5, 1])
        #expect(files.waveform("a-0123456789ab")?.count == 3)
        files.delete("a-0123456789ab")
        #expect(files.bytes("a-0123456789ab") == nil)
        // Ids that are not plain file names never reach the disk.
        #expect(throws: (any Error).self) { try files.save("../x", png, mimeType: "image/png") }
    }

    @Test func recipeAndRequestRoundTrip() {
        var q = DiffusionRequest()
        q.prompt = "a lighthouse, {dawn|dusk}"
        q.negativePrompt = "blurry"
        q.steps = 28
        q.cfgScale = 6.5
        q.width = 832
        q.height = 1216
        q.seed = 42
        q.loras = [LoraRef(name: "film", weight: 0.75), LoraRef(name: "a:b", weight: 0.5)]
        q.scheduler = "Karras"
        let r = StudioRecipes.recipe(q, backendId: "pc")
        #expect(r.backendId == "pc")
        #expect(r.seedValue == 42)
        let back = StudioRecipes.request(r)
        #expect(back.prompt == q.prompt)
        #expect(back.negativePrompt == "blurry")
        #expect(back.steps == 28 && back.cfgScale == 6.5 && back.width == 832 && back.height == 1216)
        #expect(back.loras.sorted { $0.name < $1.name } == q.loras.sorted { $0.name < $1.name })
        #expect(back.mode == .txt2img)
        let inpaint = r.withInput(asset: "a-1", role: "image").with(name: "mask", value: .text("AAAA"))
        #expect(StudioRecipes.request(inpaint).mode == .inpaint)
        let outpaint = r.withInput(asset: "a-1", role: "image").with(name: "outpaint", value: .text("0,0,256,0"))
        #expect(StudioRecipes.request(outpaint).mode == .inpaint)
        #expect(StudioRecipes.request(outpaint).denoiseStrength == 1)
        #expect(StudioRecipes.request(r.withInput(asset: "a-1", role: "image")).mode == .img2img)
        // Android's vocabulary: one `lora.<name>` per LoRA, the mask as an input asset.
        #expect(r.params["lora.film"]?.asDouble()?.doubleValue == 0.75)
        #expect(StudioRecipes.request(r.withInput(asset: "a-1", role: "image").withInput(asset: "a-2", role: "mask")).mode == .inpaint)
        #expect(StudioRecipes.Margins("1, 2,3,4") == StudioRecipes.Margins(left: 1, top: 2, right: 3, bottom: 4))
        #expect(StudioRecipes.Margins("1,2") == nil)
    }

    @Test func outpaintCanvasAndMaskCoverTheNewBorder() throws {
        let source = try #require(Self.image(100, 100).cgImage)
        let m = StudioRecipes.Margins(right: 64)
        let canvas = try #require(Outpaint.canvas(source, width: 192, height: 128, margins: m))
        #expect(canvas.width == 192 && canvas.height == 128)
        let mask = try #require(Outpaint.mask(width: 192, height: 128, margins: m, encoding: .opaqueBlackWhite))
        let png = try #require(ImageCodec.png(mask))
        let decoded = try #require(UIImage(data: png)?.cgImage)
        func luma(_ x: Int, _ y: Int) -> UInt8 {
            let data = decoded.dataProvider!.data! as Data
            return data[y * decoded.bytesPerRow + x * (decoded.bitsPerPixel / 8)]
        }
        #expect(luma(10, 64) < 30) // kept
        #expect(luma(180, 64) > 220) // repainted
    }

    @Test func wavAndPeaks() throws {
        let samples = (0 ..< 24000).map { i in Float(sin(Double(i) * 0.05)) * Float(i) / 24000 }
        let wav = StudioAudio.wav(mono: samples, sampleRate: 24000)
        #expect(wav.count == 44 + samples.count * 2)
        #expect(String(decoding: wav.prefix(4), as: UTF8.self) == "RIFF")
        let url = tempDir().appendingPathComponent("t.wav")
        try wav.write(to: url)
        let peaks = try #require(StudioAudio.peaks(file: url, count: 32))
        #expect(peaks.count == 32)
        #expect(peaks.last! > peaks.first!) // the ramp grows
        let trimmed = try #require(StudioAudio.trim(file: url, start: 0.25, end: 0.75))
        #expect(abs(trimmed.durationMs - 500) <= 2)
    }

    /// The queue reports from Kotlin's threads; the hub mirrors it on main (no isolation trap).
    @Test func queueReachesTheHubFromKotlinThreads() async throws {
        let app = AppContainer()
        await app.unlockData()
        let studio = app.studio
        #expect(studio.project != nil)
        let recipe = Recipe(engine: "no-such-engine", backendId: nil, model: nil, params: [:], inputs: [], workflowId: nil, workflowVersion: nil, seed: nil, output: .image)
        let job = try #require(studio.submit(recipe, label: "test"))
        for _ in 0 ..< 100 where studio.job(job.id)?.state != .failed { try await Task.sleep(for: .milliseconds(20)) }
        #expect(studio.job(job.id)?.state == .failed)
        #expect(studio.job(job.id)?.error?.contains("no-such-engine") == true)
        studio.clearFinished()
    }

    @Test func sharedWorkflowTextOpensTheImport() {
        let api = #"{"3": {"class_type": "KSampler", "inputs": {"seed": 1}}}"#
        #expect(StudioWorkflowText.isWorkflow(api))
        #expect(!StudioWorkflowText.isWorkflow("look at {this}"))
        let plan = EntryRouting.plan(.shared(id: "x"), canChat: true) { _ in SharedInbox.Item(text: api) }
        #expect(plan == .workflow(json: api))
    }

    static func image(_ w: Int, _ h: Int, hue: CGFloat = 0.02) -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: { let f = UIGraphicsImageRendererFormat(); f.scale = 1; return f }()).image { ctx in
            UIColor(hue: hue, saturation: 0.6, brightness: 0.9, alpha: 1).setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
            UIColor(white: 0.1, alpha: 1).setFill()
            ctx.cgContext.fillEllipse(in: CGRect(x: w / 4, y: h / 4, width: w / 2, height: h / 2))
        }
    }
}

private final class Changes: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String] = []
    func add(_ s: String) { lock.withLock { items.append(s) } }
    var list: [String] { lock.withLock { items } }
}
