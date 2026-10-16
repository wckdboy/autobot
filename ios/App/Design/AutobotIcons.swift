import SwiftUI

/**
 Hand-drawn 24pt stroke icons (1.75 pt, square caps), ported path for path from
 `AutobotIcons.kt`. Generic glyphs (back, close, star…) use SF Symbols instead of Material icons.
 */
enum AutobotIcon: String, CaseIterable, Sendable {
    case terminal, spark, chip, sliders, brush, eraser, dice, layers, image, mic, speaker, wrench, shield, undo, invert, download, copy

    /// The path in the 24×24 viewport.
    var path: Path {
        var p = Path()
        func m(_ x: CGFloat, _ y: CGFloat) { p.move(to: CGPoint(x: x, y: y)) }
        func l(_ x: CGFloat, _ y: CGFloat) { p.addLine(to: CGPoint(x: x, y: y)) }
        func c(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat, _ x: CGFloat, _ y: CGFloat) {
            p.addCurve(to: CGPoint(x: x, y: y), control1: CGPoint(x: x1, y: y1), control2: CGPoint(x: x2, y: y2))
        }
        func z() { p.closeSubpath() }

        switch self {
        case .terminal:
            m(3, 4); l(21, 4); l(21, 20); l(3, 20); z()
            m(7, 9); l(10, 12); l(7, 15)
            m(12, 15); l(17, 15)
        case .spark:
            m(12, 2.5); l(14, 10); l(21.5, 12); l(14, 14)
            l(12, 21.5); l(10, 14); l(2.5, 12); l(10, 10); z()
        case .chip:
            m(6, 6); l(18, 6); l(18, 18); l(6, 18); z()
            m(10, 10); l(14, 10); l(14, 14); l(10, 14); z()
            m(9, 2); l(9, 6); m(15, 2); l(15, 6)
            m(9, 18); l(9, 22); m(15, 18); l(15, 22)
            m(2, 9); l(6, 9); m(2, 15); l(6, 15)
            m(18, 9); l(22, 9); m(18, 15); l(22, 15)
        case .sliders:
            m(3, 6); l(21, 6); m(3, 12); l(21, 12); m(3, 18); l(21, 18)
            m(8, 4); l(8, 8); m(16, 10); l(16, 14); m(11, 16); l(11, 20)
        case .brush:
            m(20, 3); l(10, 13); m(13, 13); l(10, 10)
            m(9, 14); c(6, 14, 5, 16, 5, 18); c(5, 19.5, 4, 20.5, 3, 21)
            c(7, 21, 10, 20, 10, 16.5); z()
        case .eraser:
            m(8, 20); l(3.5, 15.5); l(14, 5); l(20, 11); l(11, 20); z()
            m(9, 10); l(15, 16); m(11, 20); l(21, 20)
        case .dice:
            m(4, 4); l(20, 4); l(20, 20); l(4, 20); z()
            m(8.5, 8.5); l(8.6, 8.6); m(15.5, 15.5); l(15.6, 15.6)
            m(12, 12); l(12.1, 12.1)
        case .layers:
            m(12, 3); l(21, 8); l(12, 13); l(3, 8); z()
            m(3, 12.5); l(12, 17.5); l(21, 12.5)
            m(3, 17); l(12, 22); l(21, 17)
        case .image:
            m(3, 4); l(21, 4); l(21, 20); l(3, 20); z()
            m(3, 16); l(9, 11); l(14, 15); l(17, 12.5); l(21, 16)
            m(15.5, 8.5); l(15.6, 8.6)
        case .mic:
            m(9, 5); c(9, 2.5, 15, 2.5, 15, 5); l(15, 11); c(15, 13.5, 9, 13.5, 9, 11); z()
            m(5.5, 10.5); c(5.5, 18.5, 18.5, 18.5, 18.5, 10.5)
            m(12, 16.5); l(12, 21)
            m(8.5, 21); l(15.5, 21)
        case .speaker:
            m(4, 9); l(8, 9); l(13, 4.5); l(13, 19.5); l(8, 15); l(4, 15); z()
            m(16.5, 9); c(18, 10.5, 18, 13.5, 16.5, 15)
            m(19, 6.5); c(22, 9.5, 22, 14.5, 19, 17.5)
        case .wrench:
            m(4, 20); l(12.5, 11.5)
            c(11, 8, 13, 4, 17, 4); l(15, 7); l(17, 9); l(20, 7)
            c(20, 11, 16, 13, 12.5, 11.5)
        case .shield:
            m(12, 2.5); l(20, 5.5); l(20, 11); c(20, 16, 16.5, 19.5, 12, 21.5)
            c(7.5, 19.5, 4, 16, 4, 11); l(4, 5.5); z()
        case .undo:
            m(4, 9); l(15, 9); c(18.5, 9, 20.5, 11.5, 20.5, 14.5)
            c(20.5, 17.5, 18.5, 20, 15, 20); l(9, 20)
            m(8, 5); l(4, 9); l(8, 13)
        case .invert:
            m(12, 3); c(17, 3, 21, 7, 21, 12); c(21, 17, 17, 21, 12, 21)
            c(7, 21, 3, 17, 3, 12); c(3, 7, 7, 3, 12, 3); z()
            m(12, 3); l(12, 21)
            m(12, 7); l(17, 7); m(12, 11); l(20, 11); m(12, 15); l(19, 15)
        case .download:
            m(12, 3); l(12, 15); m(7, 10); l(12, 15); l(17, 10)
            m(4, 17); l(4, 21); l(20, 21); l(20, 17)
        case .copy:
            m(9, 9); l(20, 9); l(20, 20); l(9, 20); z()
            m(15, 9); l(15, 4); l(4, 4); l(4, 15); l(9, 15)
        }
        return p
    }
}

/// A stroke icon scaled to [size]; the stroke width scales with it (1.75 at 24 pt).
struct IconShape: Shape {
    let icon: AutobotIcon

    func path(in rect: CGRect) -> Path {
        let scale = min(rect.width, rect.height) / 24
        return icon.path.applying(CGAffineTransform(scaleX: scale, y: scale).translatedBy(x: rect.minX / scale, y: rect.minY / scale))
    }
}

struct AutobotIconView: View {
    let icon: AutobotIcon
    var size: CGFloat = 24
    var color: Color? = nil
    @Environment(\.scheme) private var scheme

    var body: some View {
        IconShape(icon: icon)
            .stroke(color ?? scheme.onSurface, style: StrokeStyle(lineWidth: Tokens.Stroke.icon * size / 24, lineCap: .square, lineJoin: .miter))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}
