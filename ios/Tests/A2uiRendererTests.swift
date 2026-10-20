import AutobotShared
import Foundation
import SwiftUI
import Testing
import UIKit
@testable import Autobot

/// The A2UI renderer over the shared host: the spec's contact form and the bundled gallery.
@MainActor
struct A2uiRendererTests {
    private func contact() throws -> A2uiSurfaceModel {
        let model = A2uiSurfaceModel(surfaceId: "contact_form_1")
        let text = try #require(A2uiPreviewScreen.sampleText("a2ui-contact-form"))
        let errors = model.receive(text)
        #expect(errors.isEmpty, "\(errors.map { $0.forModel() })")
        return model
    }

    private func gallery() throws -> A2uiSurfaceModel {
        let model = A2uiSurfaceModel(surfaceId: "gallery")
        let text = try #require(A2uiPreviewScreen.sampleText("a2ui-gallery"))
        let errors = model.receive(text)
        #expect(errors.isEmpty, "\(errors.map { $0.forModel() })")
        #expect(model.host.surface(surfaceId: "gallery")?.integrityErrors().isEmpty == true)
        return model
    }

    @Test func contactFormBuildsTheTree() throws {
        let model = try contact()
        let root = try #require(model.root)
        #expect(root.type == "Card")
        let column = try #require(root.node(name: "child"))
        #expect(column.type == "Column")
        #expect(column.nodes(name: "children").map(\.id) == ["header_row", "name_row", "email_group", "phone_group", "pref_group", "divider_1", "newsletter_checkbox", "submit_button"])
        let first = try #require(model.node(id: "first_name_field"))
        #expect(first.string(name: "value") == "John")
        #expect(first.binding(name: "value") == "/contact/firstName")
        #expect(model.node(id: "first_name_group")?.number(name: "weight")?.doubleValue == 1)
        #expect(model.node(id: "pref_picker")?.strings(name: "value") == ["email"])
        #expect(model.node(id: "newsletter_checkbox")?.boolOr(name: "value", fallback: false) == true)
        #expect(model.node(id: "header_icon")?.string(name: "name") == "mail")
        // Keys are unique across the tree (ForEach identity).
        var keys: [String] = []
        func walk(_ n: RenderNode) { keys.append(n.key); n.children.forEach(walk) }
        walk(root)
        #expect(Set(keys).count == keys.count)
    }

    @Test func inputsWriteBack() throws {
        let model = try contact()
        let before = model.revision
        let field = try #require(model.node(id: "first_name_field"))
        #expect(model.set(field, string: "Ada"))
        #expect(model.revision > before)
        #expect(model.node(id: "first_name_field")?.string(name: "value") == "Ada")
        #expect(model.dataModelJson?.contains("\"firstName\":\"Ada\"") == true)

        let box = try #require(model.node(id: "newsletter_checkbox"))
        #expect(model.set(box, bool: false))
        #expect(model.node(id: "newsletter_checkbox")?.boolOr(name: "value", fallback: true) == false)

        let picker = try #require(model.node(id: "pref_picker"))
        #expect(model.set(picker, strings: ["sms"]))
        #expect(model.node(id: "pref_picker")?.strings(name: "value") == ["sms"])

        // Checks follow the data: an empty email fails `required` with the agent's message.
        let email = try #require(model.node(id: "email_field"))
        #expect(email.isValid)
        model.set(email, string: "")
        #expect(model.node(id: "email_field")?.isValid == false)
        #expect(model.node(id: "email_field")?.firstError == "Email is required.")
        model.set(try #require(model.node(id: "email_field")), string: "not-an-email")
        #expect(model.node(id: "email_field")?.firstError == "Please enter a valid email address.")
    }

    @Test func dispatchSendsTheEvent() throws {
        let model = try contact()
        let button = try #require(model.node(id: "submit_button"))
        guard case let .send(surfaceId, name, json, dataModel, _) = model.dispatch(button) else {
            Issue.record("expected an event"); return
        }
        #expect(surfaceId == "contact_form_1")
        #expect(name == "submitContactForm")
        #expect(dataModel == nil)
        let message = try #require(try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        #expect(message["version"] as? String == "v1.0")
        let action = try #require(message["action"] as? [String: Any])
        #expect(action["sourceComponentId"] as? String == "submit_button")
        let context = try #require(action["context"] as? [String: Any])
        #expect(context["formId"] as? String == "contact_form_1")
        #expect(context["isNewsletterSubscribed"] as? Bool == true)
    }

    @Test func failingChecksDisableTheButton() throws {
        let model = try gallery()
        let submit = try #require(model.node(id: "b_submit"))
        #expect(!submit.isValid)
        #expect(submit.firstError == "Enter your name to submit.")
        #expect(model.dispatch(submit) == .refused("Enter your name to submit."))

        model.set(try #require(model.node(id: "f_name")), string: "Ada")
        let enabled = try #require(model.node(id: "b_submit"))
        #expect(enabled.isValid)
        guard case let .send(_, name, json, _, _) = model.dispatch(enabled) else {
            Issue.record("expected an event"); return
        }
        #expect(name == "submitGallery")
        #expect(json.contains("\"name\":\"Ada\""))
        #expect(json.contains("\"rating\":4"))
    }

    @Test func openUrlIsReturnedForConfirmation() throws {
        let model = try gallery()
        let link = try #require(model.node(id: "b_link"))
        #expect(model.dispatch(link) == .openUrl(URL(string: "https://a2ui.org")!))
        #expect(A2uiAction(A2uiActionResult.Done.shared) == .done)
    }

    @Test func galleryCoversTheCatalog() throws {
        let model = try gallery()
        let root = try #require(model.root)
        var types = Set<String>()
        func walk(_ n: RenderNode) { types.insert(n.type); n.children.forEach(walk) }
        walk(root)
        let catalog = Set(A2uiCatalog.companion.basic.componentNames)
        #expect(catalog.count == 18)
        #expect(catalog.subtracting(types).isEmpty, "missing: \(catalog.subtracting(types))")
        // The template list expands one row per item, each with its own key.
        let items = try #require(model.node(id: "list")).nodes(name: "children")
        #expect(items.count == 3)
        #expect(Set(items.map(\.key)).count == 3)
        #expect(items[1].children.last?.string(name: "text") == "Book the room")
        // Slider writes numbers; formatString follows.
        model.set(try #require(model.node(id: "f_rating")), number: 2)
        #expect(model.node(id: "role")?.string(name: "text") == "Analyst · rating 2")
    }

    @Test func deleteSurfaceClearsTheModel() throws {
        let model = try contact()
        model.receive(#"{"version": "v1.0", "deleteSurface": {"surfaceId": "contact_form_1"}}"#)
        #expect(model.isDeleted)
        #expect(model.root == nil)
    }

    @Test func rejectedMessagesSurfaceErrors() throws {
        let model = try contact()
        let errors = model.receive(#"{"version": "v1.0", "updateComponents": {"surfaceId": "contact_form_1", "components": [{"id": "x", "component": "Blink"}]}}"#)
        #expect(!errors.isEmpty)
        #expect(!model.errors.isEmpty)
        #expect(model.root?.type == "Card")
    }

    @Test func everyCatalogIconHasASymbol() {
        #expect(A2uiIcons.symbols.count == 59)
        for (name, symbol) in A2uiIcons.symbols {
            #expect(UIImage(systemName: symbol) != nil, "\(name) → \(symbol)")
        }
        #expect(A2uiIcons.symbol("arrow_back") == "arrow.left")
        #expect(A2uiIcons.label("calendarToday") == "calendar today")
    }

    @Test func brandPurpleResolvesPerAppearance() {
        let ui = UIColor(Color.a2uiBrand)
        func hex(_ style: UIUserInterfaceStyle) -> String {
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            ui.resolvedColor(with: UITraitCollection(userInterfaceStyle: style)).getRed(&r, green: &g, blue: &b, alpha: &a)
            return String(format: "%02X%02X%02X", Int((r * 255).rounded()), Int((g * 255).rounded()), Int((b * 255).rounded()))
        }
        #expect(hex(.light) == "6A4BEB")
        #expect(hex(.dark) == "9C85FF")
    }

    @Test func markdownBlocksAndLinks() {
        #expect(A2uiMarkdown.blocks("# Contact Us") == [.heading(level: 1, text: "Contact Us")])
        #expect(A2uiMarkdown.blocks("a\nb\n\n- c\n2. d") == [.paragraph("a\nb"), .bullet(marker: "•", text: "c"), .bullet(marker: "2.", text: "d")])
        #expect(A2uiMarkdown.blocks("#hashtag") == [.paragraph("#hashtag")])
        let unsafe = A2uiMarkdown.inline("[x](javascript:alert(1)) and [y](https://a2ui.org)")
        let links = unsafe.runs.compactMap(\.link)
        #expect(links == [URL(string: "https://a2ui.org")!])
        #expect(A2uiMarkdown.stripped("## **Bold** `code`") == "Bold code")
    }

    @Test func datesRoundTrip() throws {
        let day = try #require(A2uiDates.parse("2026-10-21"))
        #expect(A2uiDates.format(day, .init(date: true, time: false)) == "2026-10-21")
        let time = try #require(A2uiDates.parse("09:30"))
        #expect(A2uiDates.format(time, .init(date: false, time: true)) == "09:30")
        let instant = try #require(A2uiDates.parse("2026-02-02T15:17:00Z"))
        let back = try #require(A2uiDates.parse(A2uiDates.format(instant, .init(date: true, time: true))))
        #expect(back == instant)
        #expect(A2uiDates.parse("") == nil)
        #expect(a2uiWebURL("javascript:alert(1)") == nil)
        #expect(a2uiWebURL("https://example.com/a.png") != nil)
    }

    /// Lays both samples out in a hosting controller (all component views build and size), and
    /// writes PNGs when `AUTOBOT_SNAPSHOT_DIR` is set.
    @Test(.timeLimit(.minutes(3))) func rendersBothSamples() async throws {
        let dir = ProcessInfo.processInfo.environment["AUTOBOT_SNAPSHOT_DIR"]
        for (name, model) in [("contact", try contact()), ("gallery", try gallery())] {
            for style in [UIUserInterfaceStyle.light, .dark] {
                let view = ScrollView { A2uiSurfaceView(model: model, tint: .a2uiBrand).padding(16) }
                    .background(Color(uiColor: .systemBackground))
                let host = UIHostingController(rootView: view)
                let fit = host.sizeThatFits(in: CGSize(width: 440, height: CGFloat.greatestFiniteMagnitude))
                #expect(fit.height > 300, "\(name) laid out at \(fit)")
                guard let dir else { continue }
                let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
                let window = UIWindow(windowScene: scene)
                window.frame = CGRect(x: 0, y: 0, width: 440, height: min(fit.height + 40, 3200))
                window.overrideUserInterfaceStyle = style
                window.rootViewController = host
                window.makeKeyAndVisible()
                try await Task.sleep(for: .milliseconds(800))
                let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                    window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
                }
                try #require(image.pngData()).write(to: URL(fileURLWithPath: "\(dir)/a2ui-\(name)-\(style == .dark ? "dark" : "light").png"))
                window.isHidden = true
            }
        }
    }
}
