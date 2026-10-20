import AutobotShared
import SwiftUI

/**
 Debug preview of the A2UI renderer (`-AutobotOpen a2ui`): the spec's contact form example and
 a gallery of all 18 basic-catalog components, loaded from bundled JSONL through the shared
 host. The outgoing action message appears below the surface when an action fires.
 */
struct A2uiPreviewScreen: View {
    enum Sample: String, CaseIterable, Identifiable {
        case contact = "Contact form"
        case gallery = "Gallery"
        var id: String { rawValue }
        var file: String { self == .contact ? "a2ui-contact-form" : "a2ui-gallery" }
        var surfaceId: String { self == .contact ? "contact_form_1" : "gallery" }
    }

    @Environment(Navigator.self) private var nav
    @Environment(AppContainer.self) private var app
    @State private var sample: Sample = .gallery
    @State private var models: [Sample: A2uiSurfaceModel] = [:]
    @State private var outgoing: String?

    var body: some View {
        VStack(spacing: 0) {
            AutobotTopBar(title: "A2UI preview", onBack: { nav.back() })
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Picker("Sample", selection: $sample) {
                        ForEach(Sample.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    // This branch's shell still tints riso red; the preview shows the brand purple.
                    if let model = models[sample] {
                        A2uiSurfaceView(model: model, tint: .a2uiBrand) { action in
                            outgoing = Self.describe(action)
                        }
                        .id(sample)
                        if !model.errors.isEmpty {
                            Text(model.errors.map { $0.forModel() }.joined(separator: "\n"))
                                .font(.caption.monospaced())
                                .foregroundStyle(.red)
                                .textSelection(.enabled)
                        }
                    }
                    if let outgoing {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Outgoing action").font(.headline)
                            Text(outgoing)
                                .font(.caption.monospaced())
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(12)
                                .background(.background.secondary, in: RoundedRectangle(cornerRadius: 12))
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
                .padding(16)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .environment(\.a2uiImageLoader, .policyBound(app.clients))
        .onAppear(perform: load)
        .onChange(of: sample) { outgoing = nil }
    }

    private func load() {
        guard models.isEmpty else { return }
        for s in Sample.allCases {
            let model = A2uiSurfaceModel(surfaceId: s.surfaceId)
            if let text = Self.sampleText(s.file) { model.receive(text) }
            models[s] = model
        }
    }

    static func sampleText(_ name: String) -> String? {
        guard let url = Bundle.main.url(forResource: name, withExtension: "jsonl") else { return nil }
        return try? String(contentsOf: url, encoding: .utf8)
    }

    /// The action as the chat would send it (pretty-printed JSON), or what happened instead.
    static func describe(_ action: A2uiAction) -> String {
        switch action {
        case .send(_, _, let json, let dataModel, _):
            return pretty(json) + (dataModel.map { "\n\n// a2uiRendererDataModel\n" + pretty($0) } ?? "")
        case .openUrl(let url): return "opened \(url.absoluteString)"
        case .done: return "done (local function)"
        case .refused(let reason): return "refused: \(reason)"
        }
    }

    static func pretty(_ json: String) -> String {
        guard let data = json.data(using: .utf8), let obj = try? JSONSerialization.jsonObject(with: data),
              let out = try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]),
              let s = String(data: out, encoding: .utf8) else { return json }
        return s
    }
}
