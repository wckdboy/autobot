import SwiftUI

/// A child's `weight` in a Row or Column (CSS `flex-grow`).
struct A2uiWeight: LayoutValueKey {
    static let defaultValue: Double = 0
}

/**
 A2UI's Row and Column: a flex line along [axis] with `justify` (main axis) and `align` (cross
 axis), no gaps of its own (leaves carry the margin), weighted children sharing the free space
 in proportion to their weight. HStack/VStack cannot express weights or `spaceEvenly`, hence a
 Layout.
 */
struct A2uiFlexLayout: Layout {
    enum Axis { case horizontal, vertical }

    var axis: Axis
    /// start, center, end, spaceBetween, spaceAround, spaceEvenly, stretch.
    var justify: String
    /// start, center, end, stretch.
    var align: String

    private func main(_ s: CGSize) -> CGFloat { axis == .horizontal ? s.width : s.height }
    private func cross(_ s: CGSize) -> CGFloat { axis == .horizontal ? s.height : s.width }
    private func size(main: CGFloat, cross: CGFloat) -> CGSize {
        axis == .horizontal ? CGSize(width: main, height: cross) : CGSize(width: cross, height: main)
    }
    private func proposal(main: CGFloat?, cross: CGFloat?) -> ProposedViewSize {
        axis == .horizontal ? ProposedViewSize(width: main, height: cross) : ProposedViewSize(width: cross, height: main)
    }

    struct Cache {
        var mains: [CGFloat] = []
        var crosses: [CGFloat] = []
        var key: ProposedViewSize?
    }

    func makeCache(subviews: Subviews) -> Cache { Cache() }

    /// Main-axis sizes for the children within [available] (nil: ideal).
    private func measure(_ subviews: Subviews, available: CGFloat?, crossProposal: CGFloat?) -> (mains: [CGFloat], crosses: [CGFloat]) {
        let weights = subviews.map { max(0, $0[A2uiWeight.self]) }
        // `stretch` without weights: every child grows equally.
        let effective = (justify == "stretch" && weights.allSatisfy { $0 == 0 }) ? weights.map { _ in 1.0 } : weights
        var mains = Array(repeating: CGFloat(0), count: subviews.count)
        let fixed = subviews.indices.filter { effective[$0] == 0 }
        let ideals = fixed.map { main(subviews[$0].sizeThatFits(proposal(main: nil, cross: crossProposal))) }
        let idealSum = ideals.reduce(0, +)
        if let available, idealSum > available, idealSum > 0 {
            // Too wide: share the line in proportion to what each child would like.
            for (n, i) in fixed.enumerated() {
                let share = available * ideals[n] / idealSum
                mains[i] = min(main(subviews[i].sizeThatFits(proposal(main: share, cross: crossProposal))), share)
            }
        } else {
            for (n, i) in fixed.enumerated() { mains[i] = ideals[n] }
        }
        let used = mains.reduce(0, +)
        let totalWeight = effective.reduce(0, +)
        for i in subviews.indices where effective[i] > 0 {
            if let available {
                mains[i] = max(0, available - used) * effective[i] / totalWeight
            } else {
                mains[i] = main(subviews[i].sizeThatFits(proposal(main: nil, cross: crossProposal)))
            }
        }
        let crosses = subviews.indices.map { cross(subviews[$0].sizeThatFits(proposal(main: mains[$0], cross: crossProposal))) }
        return (mains, crosses)
    }

    func sizeThatFits(proposal p: ProposedViewSize, subviews: Subviews, cache: inout Cache) -> CGSize {
        let available = axis == .horizontal ? p.width : p.height
        let crossProposal = axis == .horizontal ? p.height : p.width
        let m = measure(subviews, available: available.map { $0.isFinite ? $0 : nil } ?? nil, crossProposal: crossProposal.flatMap { $0.isFinite ? $0 : nil })
        cache.mains = m.mains
        cache.crosses = m.crosses
        cache.key = p
        let total = m.mains.reduce(0, +)
        // A Row fills its width; a Column takes the height it needs.
        let mainSize: CGFloat = axis == .horizontal ? max(total, available.flatMap { $0.isFinite ? $0 : nil } ?? total) : total
        let crossSize: CGFloat = {
            let biggest = m.crosses.max() ?? 0
            if axis == .vertical, let w = crossProposal, w.isFinite { return max(w, biggest) }
            return biggest
        }()
        return size(main: mainSize, cross: crossSize)
    }

    func placeSubviews(in bounds: CGRect, proposal p: ProposedViewSize, subviews: Subviews, cache: inout Cache) {
        guard !subviews.isEmpty else { return }
        let available = main(bounds.size)
        let crossSize = cross(bounds.size)
        let m = measure(subviews, available: available, crossProposal: crossSize)
        let total = m.mains.reduce(0, +)
        let free = max(0, available - total)
        let n = CGFloat(subviews.count)
        var offset: CGFloat
        var gap: CGFloat = 0
        switch justify {
        case "center": offset = free / 2
        case "end": offset = free
        case "spaceBetween": offset = 0; gap = n > 1 ? free / (n - 1) : 0
        case "spaceAround": gap = free / n; offset = gap / 2
        case "spaceEvenly": gap = free / (n + 1); offset = gap
        default: offset = 0
        }
        let origin = axis == .horizontal ? bounds.minX : bounds.minY
        let crossOrigin = axis == .horizontal ? bounds.minY : bounds.minX
        for (i, sub) in subviews.enumerated() {
            let childCross: CGFloat = align == "stretch" ? crossSize : min(m.crosses[i], crossSize)
            let placed = sub.dimensions(in: proposal(main: m.mains[i], cross: childCross))
            let actualCross = cross(CGSize(width: placed.width, height: placed.height))
            let c: CGFloat = switch align {
            case "center": crossOrigin + (crossSize - actualCross) / 2
            case "end": crossOrigin + crossSize - actualCross
            default: crossOrigin
            }
            let point = axis == .horizontal ? CGPoint(x: origin + offset, y: c) : CGPoint(x: c, y: origin + offset)
            sub.place(at: point, anchor: .topLeading, proposal: proposal(main: m.mains[i], cross: childCross))
            offset += m.mains[i] + gap
        }
    }
}
