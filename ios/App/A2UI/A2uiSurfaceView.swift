import AutobotShared
import SwiftUI
import UIKit

/**
 Renders one A2UI v1.0 surface (basic catalog) with standard SwiftUI controls: Liquid Glass
 buttons on iOS 26 (bordered ones before), system text styles (Dynamic Type). Accents follow the
 environment's tint (the app shell's `.tint`); pass [tint] (e.g. `.a2uiBrand`) to force one.
 The tree comes from [A2uiSurfaceModel]; inputs write back through the shared host and a tap
 runs the host's dispatch.

 [onAction] hears every action result: `.send` is for the agent (the chat sends its
 `messageJson`); `.openUrl` arrives after the user confirmed the link and the system opened
 it; `.done` and `.refused` are informational. Links are never opened without the confirmation.

 Media go through [a2uiImageLoader] (the app's policy-bound HTTP client) — without a loader,
 images show a placeholder and nothing is fetched. Video and audio show a link card instead of a
 player: AVKit would fetch outside the app's network policy (kill switch, Tor/SOCKS).
 */
struct A2uiSurfaceView: View {
    let model: A2uiSurfaceModel
    /// Nil: inherit the environment's tint (the app shell's); otherwise this tint.
    var tint: Color?
    var onAction: (A2uiAction) -> Void = { _ in }

    @State private var pendingURL: URL?
    @Environment(\.openURL) private var openURL

    init(model: A2uiSurfaceModel, tint: Color? = nil, onAction: @escaping (A2uiAction) -> Void = { _ in }) {
        self.model = model
        self.tint = tint
        self.onAction = onAction
    }

    var body: some View {
        let context = A2uiContext(model: model, onAction: onAction, open: { pendingURL = $0 })
        Group {
            if let root = model.root {
                A2uiNodeView(node: root)
            } else if !model.isDeleted {
                ProgressView().frame(maxWidth: .infinity).padding().accessibilityLabel("Loading interface")
            }
        }
        .environment(\.a2uiContext, context)
        .modifier(A2uiOptionalTint(tint: tint))
        .alert(
            "Open this link?",
            isPresented: Binding(get: { pendingURL != nil }, set: { if !$0 { pendingURL = nil } }),
            presenting: pendingURL,
        ) { url in
            Button("Open") {
                openURL(url)
                onAction(.openUrl(url))
                pendingURL = nil
            }
            Button("Cancel", role: .cancel) { pendingURL = nil }
        } message: { url in
            Text(url.absoluteString)
        }
    }
}

extension Color {
    /// The brand purple (#7C5CFF family): `BrandPurple` in the asset catalog, #6A4BEB on light
    /// grounds and #9C85FF on dark ones so text and glyphs keep >= 4.5:1 contrast.
    static let a2uiBrand = Color("BrandPurple")
}

/// Applies [tint] only when set, so the environment's tint (the app's) otherwise wins.
private struct A2uiOptionalTint: ViewModifier {
    let tint: Color?
    func body(content: Content) -> some View {
        if let tint { content.tint(tint) } else { content }
    }
}

/// Loads image bytes for A2UI `Image`s (and Video posters); nil means "not allowed / failed".
struct A2uiImageLoader: Sendable {
    let load: @Sendable (URL) async -> Data?

    /// Through the app's policy-bound client (kill switch, route, audit log), at most [maxBytes].
    static func policyBound(_ clients: HttpClientFactory, maxBytes: Int = 8 << 20) -> A2uiImageLoader {
        A2uiImageLoader { url in
            var request = URLRequest(url: url)
            request.setValue("image/*", forHTTPHeaderField: "Accept")
            guard let response = try? await clients.create(tag: "a2ui:media").send(request), response.isSuccessful,
                  response.body.count <= maxBytes else { return nil }
            return response.body
        }
    }
}

/// What the components need from their surface: the model, the action sink, the link confirmation.
@MainActor
final class A2uiContext {
    let model: A2uiSurfaceModel
    private let onAction: (A2uiAction) -> Void
    private let open: (URL) -> Void

    init(model: A2uiSurfaceModel, onAction: @escaping (A2uiAction) -> Void, open: @escaping (URL) -> Void) {
        self.model = model
        self.onAction = onAction
        self.open = open
    }

    /// A tap on [node]: the host resolves it; links wait for the user's confirmation.
    func fire(_ node: RenderNode) {
        let action = model.dispatch(node)
        if case .openUrl(let url) = action { open(url) } else { onAction(action) }
    }

    /// A media link (Video/AudioPlayer fallback): confirmed like any other link.
    func openMedia(_ url: URL) { open(url) }
}

/// Taps a Modal intercepts on its trigger.
@MainActor
final class A2uiTapOverride {
    let tap: () -> Void
    init(_ tap: @escaping () -> Void) { self.tap = tap }
}

extension EnvironmentValues {
    @Entry var a2uiContext: A2uiContext? = nil
    @Entry var a2uiImageLoader: A2uiImageLoader? = nil
    /// Images wait for the user's tap before anything is fetched (the chat; Android does the same).
    @Entry var a2uiImagesOnTap = false
    /// The leaf margin (catalog guide §3); 0 inside a Button's label.
    @Entry var a2uiMargin: CGFloat = 4
    @Entry var a2uiTapOverride: A2uiTapOverride? = nil
}

extension View {
    /// The catalog guide's leaf margin: leaves and outlined containers carry it, layout containers do not.
    func a2uiLeaf() -> some View { modifier(A2uiLeafMargin()) }
}

private struct A2uiLeafMargin: ViewModifier {
    @Environment(\.a2uiMargin) private var margin
    func body(content: Content) -> some View { content.padding(margin) }
}

/// One node of the tree, by component type.
struct A2uiNodeView: View {
    let node: RenderNode

    var body: some View {
        if node.isPlaceholder {
            A2uiPlaceholder()
        } else {
            switch node.type {
            case "Text": A2uiTextView(node: node)
            case "Image": A2uiImageView(node: node)
            case "Icon": A2uiIconView(node: node)
            case "Video": A2uiMediaLinkView(node: node, video: true)
            case "AudioPlayer": A2uiMediaLinkView(node: node, video: false)
            case "Row": A2uiStackView(node: node, axis: .horizontal)
            case "Column": A2uiStackView(node: node, axis: .vertical)
            case "List": A2uiListView(node: node)
            case "Card": A2uiCardView(node: node)
            case "Tabs": A2uiTabsView(node: node)
            case "Modal": A2uiModalView(node: node)
            case "Divider": A2uiDividerView(node: node)
            case "Button": A2uiButtonView(node: node)
            case "TextField": A2uiTextFieldView(node: node)
            case "CheckBox": A2uiCheckBoxView(node: node)
            case "ChoicePicker": A2uiChoicePickerView(node: node)
            case "Slider": A2uiSliderView(node: node)
            case "DateTimeInput": A2uiDateTimeView(node: node)
            default:
                Text("Unsupported component: \(node.type)").font(.caption).foregroundStyle(.secondary).a2uiLeaf()
            }
        }
    }
}

/// A referenced component that has not arrived yet (streaming).
struct A2uiPlaceholder: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 8)
            .fill(.quaternary)
            .frame(height: 20)
            .frame(maxWidth: 160)
            .a2uiLeaf()
            .accessibilityLabel("Loading")
    }
}

/// The ForEach every container uses: identity is [RenderNode.key] (unique per template item).
struct A2uiChildren: View {
    let nodes: [RenderNode]

    var body: some View {
        ForEach(nodes, id: \.key) { child in
            A2uiNodeView(node: child)
                .layoutValue(key: A2uiWeight.self, value: child.number(name: "weight")?.doubleValue ?? 0)
        }
    }
}
