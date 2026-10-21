import SwiftUI
import UIKit

/// Renders [mask] over its image (red tint), honouring erase strokes and invert.
struct MaskOverlay: View {
    let mask: MaskState

    var body: some View {
        Canvas { context, size in
            let tint = Color.brandPurple.opacity(0.5)
            if mask.inverted { context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(tint)) }
            for stroke in mask.strokes {
                var path = Path()
                for (i, p) in stroke.points.enumerated() {
                    let point = CGPoint(x: p.x * size.width, y: p.y * size.height)
                    if i == 0 { path.move(to: point) } else { path.addLine(to: point) }
                }
                if stroke.points.count == 1, let p = stroke.points.first {
                    path.addLine(to: CGPoint(x: p.x * size.width + 0.1, y: p.y * size.height))
                }
                let removes = stroke.erase != mask.inverted
                var layer = context
                layer.blendMode = removes ? .clear : .copy
                layer.stroke(path, with: .color(removes ? .black : tint), style: StrokeStyle(lineWidth: stroke.radius * size.width * 2, lineCap: .round, lineJoin: .round))
            }
        }
        .compositingGroup()
        .allowsHitTesting(false)
    }
}

/// What the mask editor edits: Imagine's and the Studio image room's mask.
@MainActor
protocol MaskEditing: AnyObject {
    var mask: MaskState { get }
    func addStroke(_ stroke: MaskStroke)
    func undoStroke()
    func redoStroke()
    func clearMask()
    func invertMask()
}

extension ImagineModel: MaskEditing {}

/**
 Full-screen inpaint mask editor (port of `MaskEditor.kt`). One finger paints (or erases); a
 second finger cancels the stroke in progress and switches to pinch-zoom / pan for the rest of
 that gesture. Strokes are kept in image-normalized coordinates so the overlay matches the
 exported mask exactly.
 */
struct MaskEditor: View {
    let image: UIImage
    let model: any MaskEditing
    let onDone: () -> Void
    @State private var erase = false
    @State private var brush: Double = 28
    @State private var scale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var live: [CGPoint] = []

    var body: some View {
        let mask = model.mask
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Mask").font(.headline)
                    Text(mask.inverted ? "Inverted · \(counted(mask.strokes.count, "stroke"))" : counted(mask.strokes.count, "stroke").sentenceCased)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button("Fit") {
                    scale = 1
                    offset = .zero
                }
                .glassButton()
                Button("Done", action: onDone)
                    .glassProminentButton()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            Divider()
            GeometryReader { geo in
                let box = geo.size
                let fit = min(box.width / image.size.width, box.height / image.size.height)
                let draw = CGSize(width: image.size.width * fit, height: image.size.height * fit)
                ZStack {
                    ZStack {
                        Image(uiImage: image).resizable()
                        MaskOverlay(mask: mask)
                    }
                    .frame(width: draw.width, height: draw.height)
                    .scaleEffect(scale)
                    .offset(offset)
                    Canvas { context, _ in
                        guard let first = live.first else { return }
                        var path = Path()
                        path.move(to: first)
                        live.dropFirst().forEach { path.addLine(to: $0) }
                        if live.count == 1 { path.addLine(to: CGPoint(x: first.x + 0.1, y: first.y)) }
                        context.stroke(path, with: .color(erase ? Color.white.opacity(0.5) : Color.brandPurple.opacity(0.6)),
                                       style: StrokeStyle(lineWidth: brush, lineCap: .round, lineJoin: .round))
                    }
                    .allowsHitTesting(false)
                    PaintSurface(
                        onPaint: { live.append($0) },
                        onPaintEnd: {
                            guard !live.isEmpty else { return }
                            let points = live.map { toImage($0, box: box, draw: draw) }
                            model.addStroke(MaskStroke(points: points, radius: brush / 2 / (draw.width * scale), erase: erase))
                            live = []
                        },
                        onCancelPaint: { live = [] },
                        onTransform: { zoom, pan, centroid in
                            let newScale = min(max(scale * zoom, 1), 8)
                            let center = CGPoint(x: box.width / 2, y: box.height / 2)
                            var o = CGSize(width: offset.width + pan.x, height: offset.height + pan.y)
                            o.width += (centroid.x - center.x - o.width) * (1 - newScale / scale)
                            o.height += (centroid.y - center.y - o.height) * (1 - newScale / scale)
                            offset = o
                            scale = newScale
                        },
                    )
                }
                .frame(width: box.width, height: box.height)
                .clipped()
            }
            Divider()
            VStack(alignment: .leading, spacing: 10) {
                LabeledSlider("Brush", value: $brush, in: 6 ... 90, format: { "\(Int($0)) pt" })
                HStack(spacing: 16) {
                    Picker("Tool", selection: $erase) {
                        Label("Brush", systemImage: "paintbrush.pointed").tag(false)
                        Label("Eraser", systemImage: "eraser").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .labelStyle(.iconOnly)
                    .fixedSize()
                    Button(action: model.undoStroke) { Image(systemName: "arrow.uturn.backward") }
                        .disabled(mask.strokes.isEmpty)
                        .accessibilityLabel("Undo")
                    Button(action: model.redoStroke) { Image(systemName: "arrow.uturn.forward") }
                        .disabled(mask.redo.isEmpty)
                        .accessibilityLabel("Redo")
                    Button(action: model.invertMask) {
                        Image(systemName: "circle.lefthalf.filled").foregroundStyle(mask.inverted ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                    }
                    .accessibilityLabel("Invert")
                    .accessibilityAddTraits(mask.inverted ? .isSelected : [])
                    Spacer()
                    Button("Clear", role: .destructive, action: model.clearMask)
                        .disabled(mask.strokes.isEmpty && !mask.inverted)
                }
                .buttonStyle(.borderless)
                .font(.title3)
                Text("1 finger paints · 2 fingers zoom and pan · painted areas are repainted")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .background(Color(.systemBackground).ignoresSafeArea())
    }

    /// Maps a point in the box to normalized image coordinates.
    private func toImage(_ p: CGPoint, box: CGSize, draw: CGSize) -> CGPoint {
        let cx = box.width / 2 + offset.width
        let cy = box.height / 2 + offset.height
        return CGPoint(x: ((p.x - cx) / scale + draw.width / 2) / draw.width, y: ((p.y - cy) / scale + draw.height / 2) / draw.height)
    }
}

/// UIKit touch surface: one-finger pan paints, pinch / two-finger pan transform.
private struct PaintSurface: UIViewRepresentable {
    let onPaint: (CGPoint) -> Void
    let onPaintEnd: () -> Void
    let onCancelPaint: () -> Void
    let onTransform: (CGFloat, CGPoint, CGPoint) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        let paint = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.paint(_:)))
        paint.maximumNumberOfTouches = 1
        let pinch = UIPinchGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.pinch(_:)))
        let pan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.pan(_:)))
        pan.minimumNumberOfTouches = 2
        [paint, pinch, pan].forEach {
            $0.delegate = context.coordinator
            view.addGestureRecognizer($0)
        }
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) { context.coordinator.parent = self }

    @MainActor
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var parent: PaintSurface?
        private var transforming = false

        @objc func paint(_ g: UIPanGestureRecognizer) {
            guard let parent else { return }
            switch g.state {
            case .began, .changed: if !transforming { parent.onPaint(g.location(in: g.view)) }
            case .ended: if !transforming { parent.onPaintEnd() }
            default: parent.onCancelPaint()
            }
        }

        @objc func pinch(_ g: UIPinchGestureRecognizer) {
            transformBegan(g.state)
            parent?.onTransform(g.scale, .zero, g.location(in: g.view))
            g.scale = 1
        }

        @objc func pan(_ g: UIPanGestureRecognizer) {
            transformBegan(g.state)
            let t = g.translation(in: g.view)
            parent?.onTransform(1, t, g.location(in: g.view))
            g.setTranslation(.zero, in: g.view)
        }

        private func transformBegan(_ state: UIGestureRecognizer.State) {
            if state == .began {
                transforming = true
                parent?.onCancelPaint()
            }
            if state == .ended || state == .cancelled { transforming = false }
        }

        nonisolated func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    }
}
