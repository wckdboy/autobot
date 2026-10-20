import AutobotShared
import SwiftUI

// The basic catalog's display and layout components. Inputs are in A2uiInputs.swift.

extension RenderValue {
    /// The items of a list value (`options`, `tabs`).
    var listItems: [RenderValue] { (self as? RenderValue.ListValue)?.items ?? [] }
    /// The fields of an object value (one option, one tab).
    var objectFields: [String: RenderValue] { (self as? RenderValue.ObjectValue)?.fields ?? [:] }
}

extension RenderNode {
    func variant(_ fallback: String, prop: String = "variant") -> String { stringOr(name: prop, fallback: fallback) }
}

/// http(s) URLs with a host only; everything else is not loaded or opened.
func a2uiWebURL(_ s: String?) -> URL? {
    guard let s, let url = URL(string: s.trimmingCharacters(in: .whitespacesAndNewlines)),
          let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http", url.host() != nil else { return nil }
    return url
}

// MARK: Text

struct A2uiTextView: View {
    let node: RenderNode

    var body: some View {
        A2uiMarkdownText(text: node.string(name: "text") ?? "", caption: node.variant("body") == "caption")
            .fixedSize(horizontal: false, vertical: true)
            .a2uiLeaf()
    }
}

// MARK: Image

struct A2uiImageView: View {
    let node: RenderNode

    var body: some View {
        let variant = node.variant("mediumFeature")
        let fit = node.stringOr(name: "fit", fallback: variant == "header" ? "cover" : "fill")
        let image = A2uiRemoteImage(url: a2uiWebURL(node.string(name: "url")), fit: fit)
        Group {
            switch variant {
            case "icon": image.frame(width: 24, height: 24).clipShape(RoundedRectangle(cornerRadius: 4))
            case "avatar": image.frame(width: 40, height: 40).clipShape(Circle())
            case "smallFeature": image.frame(width: 100, height: 100).clipShape(RoundedRectangle(cornerRadius: 10))
            case "largeFeature": image.frame(maxWidth: .infinity).frame(height: 320).clipShape(RoundedRectangle(cornerRadius: 14))
            case "header": image.frame(maxWidth: .infinity).frame(height: 200).clipShape(RoundedRectangle(cornerRadius: 14))
            default: image.frame(maxWidth: 300).frame(height: 200).clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
        .accessibilityElement()
        .accessibilityLabel(node.string(name: "description") ?? "Image")
        .accessibilityAddTraits(.isImage)
        .a2uiLeaf()
    }
}

/// An image fetched through [a2uiImageLoader]; a neutral placeholder while loading, on failure
/// and when there is no loader (nothing leaves the device then).
struct A2uiRemoteImage: View {
    let url: URL?
    let fit: String

    @Environment(\.a2uiImageLoader) private var loader
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        ZStack {
            if let image {
                switch fit {
                case "none": Image(uiImage: image)
                case "fill": Image(uiImage: image).resizable()
                case "cover": Image(uiImage: image).resizable().scaledToFill()
                default: Image(uiImage: image).resizable().scaledToFit()
                }
            } else {
                Rectangle().fill(.quaternary)
                Image(systemName: failed || loader == nil || url == nil ? "photo" : "photo.badge.arrow.down")
                    .font(.title2)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .task(id: url) {
            image = nil
            failed = false
            guard let url, let loader else { return }
            if let data = await loader.load(url), let ui = UIImage(data: data) { image = ui } else { failed = true }
        }
    }
}

// MARK: Icon

struct A2uiIconView: View {
    let node: RenderNode

    var body: some View {
        // `name` is a catalog name or {svgPath}; custom SVG paths are not drawn (yet): a neutral symbol.
        let name = (node.value(name: "name") as? RenderValue.Text)?.value
        Image(systemName: name.map(A2uiIcons.symbol) ?? "square.dashed")
            .font(.title2)
            .imageScale(.medium)
            .rotationEffect(name == "moreVert" || name == "more_vert" ? .degrees(90) : .zero)
            .frame(minWidth: 24, minHeight: 24)
            .accessibilityLabel(name.map(A2uiIcons.label) ?? "Icon")
            .a2uiLeaf()
    }
}

// MARK: Video / AudioPlayer (link card)

/// Video and AudioPlayer as a link card: AVKit would stream outside the app's network policy,
/// so the media opens in the system (after the same confirmation as any link).
struct A2uiMediaLinkView: View {
    let node: RenderNode
    let video: Bool
    @Environment(\.a2uiContext) private var context

    var body: some View {
        let url = a2uiWebURL(node.string(name: "url"))
        let title = node.string(name: "description") ?? (video ? "Video" : "Audio")
        Button {
            if let url { context?.openMedia(url) }
        } label: {
            VStack(alignment: .leading, spacing: 0) {
                if video, let poster = a2uiWebURL(node.string(name: "posterUrl")) {
                    A2uiRemoteImage(url: poster, fit: "cover")
                        .frame(height: 180)
                        .overlay { Image(systemName: "play.circle.fill").font(.largeTitle).foregroundStyle(.white).shadow(radius: 4) }
                }
                HStack(spacing: 12) {
                    Image(systemName: video ? "play.rectangle.fill" : "waveform")
                        .font(.title2)
                        .foregroundStyle(.tint)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.body).foregroundStyle(.primary).lineLimit(2)
                        Text(url?.host() ?? "No playable link").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "arrow.up.forward").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                }
                .padding(12)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.background.secondary, in: RoundedRectangle(cornerRadius: 14))
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(.separator, lineWidth: 0.5))
        }
        .buttonStyle(.plain)
        .disabled(url == nil)
        .accessibilityLabel("\(video ? "Video" : "Audio"): \(title)")
        .accessibilityHint(url.map { "Opens \($0.host() ?? "") after you confirm" } ?? "")
        .a2uiLeaf()
    }
}

// MARK: Row / Column / List

struct A2uiStackView: View {
    let node: RenderNode
    let axis: A2uiFlexLayout.Axis

    var body: some View {
        A2uiFlexLayout(axis: axis, justify: node.stringOr(name: "justify", fallback: "start"), align: node.stringOr(name: "align", fallback: "stretch")) {
            A2uiChildren(nodes: node.nodes(name: "children"))
        }
        .accessibilityElement(children: .contain)
    }
}

struct A2uiListView: View {
    let node: RenderNode

    var body: some View {
        let children = node.nodes(name: "children")
        let align = node.stringOr(name: "align", fallback: "stretch")
        if node.stringOr(name: "direction", fallback: "vertical") == "horizontal" {
            ScrollView(.horizontal) {
                LazyHStack(alignment: align == "center" ? .center : align == "end" ? .bottom : .top, spacing: 0) {
                    ForEach(children, id: \.key) { A2uiNodeView(node: $0).frame(maxWidth: 280) }
                }
            }
            .scrollIndicators(.hidden)
        } else {
            LazyVStack(alignment: align == "center" ? .center : align == "end" ? .trailing : .leading, spacing: 0) {
                ForEach(children, id: \.key) { child in
                    A2uiNodeView(node: child)
                        .frame(maxWidth: align == "stretch" ? .infinity : nil, alignment: .leading)
                }
            }
        }
    }
}

// MARK: Card

struct A2uiCardView: View {
    let node: RenderNode

    var body: some View {
        Group {
            if let child = node.node(name: "child") { A2uiNodeView(node: child) } else { EmptyView() }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.background.secondary, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(.separator, lineWidth: 0.5))
        .accessibilityElement(children: .contain)
        .a2uiLeaf()
    }
}

// MARK: Tabs

struct A2uiTabsView: View {
    let node: RenderNode
    @State private var selected = 0

    var body: some View {
        let tabs = node.value(name: "tabs").listItems.map(\.objectFields)
        let titles = tabs.enumerated().map { $0.element["title"]?.asText() ?? "Tab \($0.offset + 1)" }
        let index = min(selected, max(0, tabs.count - 1))
        VStack(alignment: .leading, spacing: 0) {
            if titles.count <= 4 {
                Picker("Tabs", selection: $selected) {
                    ForEach(Array(titles.enumerated()), id: \.offset) { Text($0.element).tag($0.offset) }
                }
                .pickerStyle(.segmented)
                .a2uiLeaf()
            } else {
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(Array(titles.enumerated()), id: \.offset) { i, title in
                            A2uiChip(title: title, selected: i == index) { selected = i }
                        }
                    }
                    .padding(4)
                }
                .scrollIndicators(.hidden)
            }
            if tabs.indices.contains(index), let child = (tabs[index]["child"] as? RenderValue.NodeValue)?.node {
                A2uiNodeView(node: child).id(child.key)
            }
        }
        .accessibilityElement(children: .contain)
    }
}

// MARK: Modal

/// The trigger shows inline; tapping it (its own action is intercepted) opens the content as a sheet.
struct A2uiModalView: View {
    let node: RenderNode
    @State private var open = false

    var body: some View {
        Group {
            if let trigger = node.node(name: "trigger") {
                A2uiNodeView(node: trigger).environment(\.a2uiTapOverride, A2uiTapOverride { open = true })
            }
        }
        .sheet(isPresented: $open) {
            NavigationStack {
                ScrollView {
                    if let content = node.node(name: "content") {
                        A2uiNodeView(node: content).padding()
                    }
                }
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close", systemImage: "xmark") { open = false }
                    }
                }
            }
            .environment(\.a2uiTapOverride, nil)
            .presentationDetents([.medium, .large])
        }
    }
}

// MARK: Divider

struct A2uiDividerView: View {
    let node: RenderNode

    var body: some View {
        if node.stringOr(name: "axis", fallback: "horizontal") == "vertical" {
            Rectangle().fill(.separator).frame(width: 1).frame(minHeight: 24).a2uiLeaf().accessibilityHidden(true)
        } else {
            Divider().a2uiLeaf().accessibilityHidden(true)
        }
    }
}

// MARK: Button

struct A2uiButtonView: View {
    let node: RenderNode
    @Environment(\.a2uiContext) private var context
    @Environment(\.a2uiTapOverride) private var tapOverride

    var body: some View {
        let error = node.firstError
        VStack(alignment: .leading, spacing: 4) {
            Button {
                if let tapOverride { tapOverride.tap() } else { context?.fire(node) }
            } label: {
                Group {
                    if let child = node.node(name: "child") { A2uiNodeView(node: child) } else { Text("Button") }
                }
                .environment(\.a2uiMargin, 0)
                .frame(minHeight: 22)
            }
            .a2uiButtonStyle(node.variant("default"))
            .disabled(!node.isValid && tapOverride == nil)
            .accessibilityHint(error ?? "")
            if let error, tapOverride == nil {
                Text(error).font(.caption).foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
        }
        .a2uiLeaf()
    }
}

extension View {
    /// Button variants: primary → glass prominent (brand tint), default → glass, borderless → link-like.
    @ViewBuilder func a2uiButtonStyle(_ variant: String) -> some View {
        switch variant {
        case "primary":
            if #available(iOS 26, *) { buttonStyle(.glassProminent).controlSize(.large) } else { buttonStyle(.borderedProminent).controlSize(.large) }
        case "borderless":
            buttonStyle(.borderless)
        default:
            if #available(iOS 26, *) { buttonStyle(.glass).controlSize(.large) } else { buttonStyle(.bordered).controlSize(.large) }
        }
    }
}

/// A selectable capsule (ChoicePicker chips, many Tabs).
struct A2uiChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label { Text(title) } icon: { if selected { Image(systemName: "checkmark") } }
                .labelStyle(A2uiChipLabelStyle(selected: selected))
        }
        .a2uiChipStyle(selected)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct A2uiChipLabelStyle: LabelStyle {
    let selected: Bool
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) {
            if selected { configuration.icon.font(.caption.weight(.bold)) }
            configuration.title
        }
    }
}

extension View {
    @ViewBuilder func a2uiChipStyle(_ selected: Bool) -> some View {
        if #available(iOS 26, *) {
            if selected { buttonStyle(.glassProminent).buttonBorderShape(.capsule) } else { buttonStyle(.glass).buttonBorderShape(.capsule) }
        } else {
            if selected { buttonStyle(.borderedProminent).buttonBorderShape(.capsule) } else { buttonStyle(.bordered).buttonBorderShape(.capsule) }
        }
    }
}

/// Wraps chips onto lines.
struct A2uiFlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let rows = arrange(width: width, subviews: subviews)
        let height = rows.last.map { $0.y + $0.height } ?? 0
        let used = rows.map(\.width).max() ?? 0
        return CGSize(width: proposal.width.map { $0.isFinite ? $0 : used } ?? used, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for i in row.items {
                let s = subviews[i].sizeThatFits(.unspecified)
                subviews[i].place(at: CGPoint(x: x, y: bounds.minY + row.y), proposal: ProposedViewSize(width: min(s.width, bounds.width), height: s.height))
                x += min(s.width, bounds.width) + spacing
            }
        }
    }

    private struct Row { var items: [Int] = []; var y: CGFloat = 0; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var row = Row()
        var y: CGFloat = 0
        for i in subviews.indices {
            let s = subviews[i].sizeThatFits(.unspecified)
            let w = min(s.width, width)
            if !row.items.isEmpty, row.width + spacing + w > width {
                rows.append(row)
                y += row.height + spacing
                row = Row(y: y)
            }
            row.width += (row.items.isEmpty ? 0 : spacing) + w
            row.height = max(row.height, s.height)
            row.items.append(i)
        }
        if !row.items.isEmpty { rows.append(row) }
        return rows
    }
}
