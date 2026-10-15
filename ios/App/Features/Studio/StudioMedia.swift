import AutobotShared
import AVFoundation
import AVKit
import ImageIO
import Observation
import Photos
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Video and audio helpers for the Studio (posters, frames, playback of decrypted temp copies).
enum StudioMedia {
    /// The first frame of a video file.
    static func poster(_ url: URL) -> CGImage? { frame(url, at: 0) }

    static func frame(_ url: URL, at seconds: Double) -> CGImage? {
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        return try? generator.copyCGImage(at: CMTime(seconds: seconds, preferredTimescale: 600), actualTime: nil)
    }

    /// Saves [data] (an image or a video) to Photos, unencrypted, after the user confirmed.
    static func exportToPhotos(_ data: Data, video: Bool, ext: String) async -> String {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { return "Photo library access was not granted" }
        do {
            if video {
                let url = AppPaths.plainCache.appendingPathComponent("\(UUID().uuidString).\(ext)")
                try AppPaths.write(data, to: url)
                defer { try? FileManager.default.removeItem(at: url) }
                try await PHPhotoLibrary.shared().performChanges {
                    PHAssetCreationRequest.forAsset().addResource(with: .video, fileURL: url, options: nil)
                }
            } else {
                try await PHPhotoLibrary.shared().performChanges {
                    PHAssetCreationRequest.forAsset().addResource(with: .photo, data: data, options: nil)
                }
            }
            return "Exported to Photos"
        } catch {
            return "Export failed"
        }
    }
}

/// An exported audio file for the share sheet / Files (a plain copy made only after the user confirmed).
struct AudioFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.audio] }
    let data: Data

    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

/**
 Plays Studio audio from memory (no plain file): loop, a playhead for the waveform, and A/B
 switching that keeps the position (compare two clips). One per screen.
 */
@MainActor
@Observable
final class StudioAudioPlayer {
    private(set) var playingId: String?
    private(set) var position: Double = 0
    private(set) var duration: Double = 0
    var loop = true
    @ObservationIgnored private var player: AVAudioPlayer?
    @ObservationIgnored private var ticker: Task<Void, Never>?

    var isPlaying: Bool { player?.isPlaying == true }

    /// Plays [data] as [id]; [keepPosition] continues from where the last clip was (A/B).
    func play(_ id: String, data: Data, keepPosition: Bool = false) {
        let at = keepPosition ? (player?.currentTime ?? 0) : 0
        stop()
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        guard let p = try? AVAudioPlayer(data: data) else { return }
        p.numberOfLoops = loop ? -1 : 0
        p.currentTime = min(at, p.duration)
        p.play()
        player = p
        playingId = id
        duration = p.duration
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(50))
                guard let self, let p = self.player else { return }
                self.position = p.currentTime
                if !p.isPlaying && !self.loop && p.currentTime == 0 { self.playingId = nil; return }
            }
        }
    }

    func setLoop(_ on: Bool) {
        loop = on
        player?.numberOfLoops = on ? -1 : 0
    }

    func seek(_ fraction: Double) {
        guard let p = player else { return }
        p.currentTime = max(0, min(1, fraction)) * p.duration
        position = p.currentTime
    }

    func stop() {
        ticker?.cancel()
        player?.stop()
        player = nil
        playingId = nil
        position = 0
    }
}

/// Bars of a waveform with the playhead; a drag scrubs (or sets the trim handles when [trim] is given).
struct WaveformView: View {
    let peaks: [Float]
    var progress: Double = 0
    var trim: Binding<ClosedRange<Double>>? = nil
    var onScrub: ((Double) -> Void)? = nil
    @Environment(\.scheme) private var c

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            Canvas { context, size in
                let n = max(peaks.count, 1)
                let bar = size.width / CGFloat(n)
                var played = Path(), rest = Path()
                for (i, p) in peaks.enumerated() {
                    let height = max(2, CGFloat(p) * size.height)
                    let rect = CGRect(x: CGFloat(i) * bar + bar * 0.15, y: (size.height - height) / 2, width: max(1, bar * 0.7), height: height)
                    if Double(i) / Double(n) < progress { played.addRect(rect) } else { rest.addRect(rect) }
                }
                context.fill(rest, with: .color(c.outline))
                context.fill(played, with: .color(c.primary))
                if let trim {
                    let r = trim.wrappedValue
                    context.fill(Path(CGRect(x: 0, y: 0, width: size.width * r.lowerBound, height: size.height)), with: .color(c.background.opacity(0.7)))
                    context.fill(Path(CGRect(x: size.width * r.upperBound, y: 0, width: size.width * (1 - r.upperBound), height: size.height)), with: .color(c.background.opacity(0.7)))
                    for x in [r.lowerBound, r.upperBound] {
                        context.fill(Path(CGRect(x: size.width * x - 1.5, y: 0, width: 3, height: size.height)), with: .color(c.primary))
                    }
                }
                let head = size.width * progress
                context.fill(Path(CGRect(x: head - 0.5, y: 0, width: 1, height: size.height)), with: .color(c.onSurface))
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                let f = min(max(g.location.x / max(w, 1), 0), 1)
                if let trim {
                    var r = trim.wrappedValue
                    if abs(f - r.lowerBound) < abs(f - r.upperBound) { r = min(f, r.upperBound - 0.02) ... r.upperBound } else { r = r.lowerBound ... max(f, r.lowerBound + 0.02) }
                    trim.wrappedValue = r
                } else {
                    onScrub?(f)
                }
            })
            .frame(width: w, height: h)
        }
        .accessibilityElement()
        .accessibilityLabel("Waveform")
        .accessibilityValue("\(Int(progress * 100)) percent played")
    }
}

/**
 The video player: a decrypted temp copy (deleted when it goes), scrubbing, loop, and "export
 frame" (the current frame as a new image asset).
 */
struct StudioVideoPlayer: View {
    let url: URL
    var onExportFrame: ((CGImage) -> Void)? = nil
    @Environment(\.scheme) private var c
    @State private var player = AVPlayer()
    @State private var position: Double = 0
    @State private var duration: Double = 0
    @State private var loop = true
    @State private var playing = false
    @State private var observer: Any?
    @State private var looper: (any NSObjectProtocol)?

    var body: some View {
        VStack(spacing: 8) {
            VideoPlayer(player: player)
                .aspectRatio(contentMode: .fit)
                .frame(maxWidth: .infinity)
                .background(Color.black)
                .accessibilityLabel("Video")
            HStack(spacing: 10) {
                IconAction(symbol: playing ? "pause.fill" : "play.fill", label: playing ? "Pause" : "Play") {
                    if playing { player.pause() } else { player.play() }
                    playing.toggle()
                }
                RisoSlider(value: Binding(get: { position }, set: { v in
                    position = v
                    player.seek(to: CMTime(seconds: v, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
                }), range: 0 ... max(duration, 0.01))
                .accessibilityLabel("Scrub")
                Text(String(format: "%.1f s", position)).textStyle(.readout).foregroundStyle(c.onSurfaceVariant).frame(width: 52, alignment: .trailing)
            }
            HStack(spacing: 8) {
                Tag(loop ? "loop on" : "loop off", accent: loop ? c.primary : c.onSurfaceVariant, filled: loop) { loop.toggle() }
                if let onExportFrame {
                    Tag("export frame", accent: c.primary) {
                        let at = position
                        let source = url
                        Task {
                            let frame = await Task.detached { StudioMedia.frame(source, at: at) }.value
                            if let frame { onExportFrame(frame) }
                        }
                    }
                }
                Spacer()
            }
        }
        .onAppear {
            player.replaceCurrentItem(with: AVPlayerItem(url: url))
            observer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.05, preferredTimescale: 600), queue: .main) { time in
                MainActor.assumeIsolated {
                    position = time.seconds
                    if let d = player.currentItem?.duration.seconds, d.isFinite { duration = d }
                }
            }
            looper = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated {
                    if loop {
                        player.seek(to: .zero)
                        player.play()
                    } else {
                        playing = false
                    }
                }
            }
        }
        .onDisappear {
            player.pause()
            if let observer { player.removeTimeObserver(observer) }
            if let looper { NotificationCenter.default.removeObserver(looper) }
        }
    }
}

/**
 An animated WEBP / GIF (ComfyUI's SaveAnimatedWEBP: a "video" AVPlayer cannot read), played from
 memory with ImageIO and looping; the first frame, still, with Reduce Motion.
 */
struct AnimatedImageView: View {
    let data: Data
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var frame: UIImage?
    @State private var player: AnimatedFrames?

    var body: some View {
        ZStack {
            if let frame { Image(uiImage: frame).resizable().scaledToFit() }
        }
        .frame(maxWidth: .infinity)
        .onAppear {
            if reduceMotion {
                frame = UIImage(data: data)
            } else {
                let p = AnimatedFrames(data: data) { image in frame = image }
                player = p
                p.start()
            }
        }
        .onDisappear { player?.stop() }
        .accessibilityLabel("Animation")
    }
}

/// Drives `CGAnimateImageDataWithBlock` until stopped.
@MainActor
final class AnimatedFrames {
    private let data: Data
    private let onFrame: (UIImage) -> Void
    private var stopped = false

    init(data: Data, onFrame: @escaping (UIImage) -> Void) {
        self.data = data
        self.onFrame = onFrame
    }

    func start() {
        CGAnimateImageDataWithBlock(data as CFData, nil) { [weak self] _, image, stop in
            MainActor.assumeIsolated {
                guard let self, !self.stopped else { stop.pointee = true; return }
                self.onFrame(UIImage(cgImage: image))
            }
        }
    }

    func stop() { stopped = true }
}
