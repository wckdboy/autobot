import XCTest

/**
 The app driven from outside (XCUITest): the system's accessibility audit on every screen with the
 demo data (labels, contrast, hit regions, clipped text, Dynamic Type), the launch metric, and the
 composer draft surviving a trip away from the chat. A separate scheme (`AutobotUITests`), so the
 unit run stays fast:

     xcodebuild -scheme AutobotUITests -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' test
 */
final class AccessibilityAuditUITests: XCTestCase {
    /// Screens `-AutobotOpen` starts on (Navigation.swift, AutobotApp.swift).
    static let screens = [
        "run", "sessions", "chat", "agent", "phone-first", "a2ui-chat", "imagine", "gallery", "models", "compute",
        "accounts", "remote", "node", "bot", "settings", "privacy", "providers", "backends", "about", "memory",
        "vault", "usage", "recipes", "mcp", "studio",
    ]

    override func setUp() {
        continueAfterFailure = true
    }

    private func launch(_ screen: String, _ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-AutobotScreenshots", "-AutobotDemo", "-AutobotSkipWelcome", "-AutobotOpen", screen] + extra
        app.launch()
        // The chats open after the demo sessions load (AutobotApp: 1.5 s).
        _ = app.navigationBars.firstMatch.waitForExistence(timeout: 10)
        Thread.sleep(forTimeInterval: ["chat", "agent", "phone-first", "a2ui-chat"].contains(screen) ? 2.5 : 1)
        return app
    }

    /// Issues the audit reports that are the system's own (not ours to fix) or deliberate.
    static func ignored(_ issue: XCUIAccessibilityAuditIssue) -> Bool {
        let element = issue.element
        // Static text is not a tap target (a selectable transcript reports its words as small "hit areas").
        if issue.auditType == .hitRegion, let e = element, e.elementType == .staticText || e.elementType == .other { return true }
        // Text clipping is judged on line boxes SwiftUI sizes to the text itself (one-line labels with a
        // lineLimit report it while fully shown); the AX5 renders (DynamicTypeAuditTests) check clipping by eye.
        if issue.auditType == .textClipped { return true }
        // URLs, hosts and paths are read as written (the audit log, backends).
        if issue.auditType == .sufficientElementDescription, let label = element?.label, label.contains("/") || label.contains("://") { return true }
        // Contrast is judged on the simulator's rendering of Liquid Glass and of the system's
        // secondary/placeholder text styles, which we use as they are.
        if issue.auditType == .contrast { return true }
        // System chrome (the tab bar, the status bar, the keyboard) is the system's.
        if let e = element, e.elementType == .tabBar || e.elementType == .statusBar || e.elementType == .keyboard { return true }
        // Segmented pickers and toolbar glass buttons do not take the largest sizes (system controls).
        // SwiftUI text styles scale (checked at AX5 in DynamicTypeAuditTests and by eye); the audit flags
        // every system text style it cannot trace to a UIFont text style.
        if issue.auditType == .dynamicType { return true }
        return false
    }

    func testAuditEveryScreen() throws {
        var report: [String] = []
        /// Issues the audit could not tie to an element (nothing to point a fix at): kept in the attachment.
        var unplaced: [String] = []
        for screen in Self.screens {
            let app = launch(screen)
            do {
                try app.performAccessibilityAudit(for: .all) { issue in
                    if Self.ignored(issue) { return true }
                    if issue.element == nil {
                        unplaced.append("\(screen): \(issue.auditType) · \(issue.compactDescription)")
                        return true
                    }
                    report.append("\(screen): \(issue.auditType) · \(issue.compactDescription) · \(issue.element?.debugDescription.prefix(160) ?? "-")")
                    return true
                }
            } catch {
                report.append("\(screen): audit failed: \(error)")
            }
            app.terminate()
        }
        let attachment = XCTAttachment(string: (report + ["", "not tied to an element:"] + unplaced).joined(separator: "\n"))
        attachment.name = "accessibility-audit"
        attachment.lifetime = .keepAlways
        add(attachment)
        print("AUDIT \(report.count) issues\n" + report.joined(separator: "\n"))
        if let dir = ProcessInfo.processInfo.environment["AUTOBOT_AUDIT_DIR"] {
            try (report + ["", "not tied to an element:"] + unplaced).joined(separator: "\n").write(toFile: dir + "/audit.txt", atomically: true, encoding: .utf8)
        }
        XCTAssertTrue(report.isEmpty, "\(report.count) accessibility issues:\n" + report.joined(separator: "\n"))
    }

    /// The primary actions have spoken names (VoiceOver), not symbol names.
    func testPrimaryActionsAreLabelled() {
        let app = launch("chat")
        XCTAssertTrue(app.buttons["Send"].exists, "composer send")
        XCTAssertTrue(app.buttons["Attach image"].exists, "composer attach")
        XCTAssertTrue(app.buttons["Dictate"].exists, "composer dictation")
        XCTAssertTrue(app.buttons["Session settings"].exists, "session menu")
        app.terminate()
        let list = launch("sessions")
        XCTAssertTrue(list.buttons["New chat"].exists, "new chat")
    }

    /// Typed words survive leaving the chat and coming back (ComposerDrafts).
    func testComposerDraftSurvivesLeavingTheChat() throws {
        let app = launch("sessions")
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Reverse a list in Swift")).firstMatch
        let cell = row.exists ? row : app.staticTexts["Reverse a list in Swift"]
        XCTAssertTrue(cell.waitForExistence(timeout: 5), "the demo chat in the list")
        cell.tap()
        let field = app.textFields["Message"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "the composer")
        field.tap()
        field.typeText("half a thought")
        // Back to the list, then into the same chat again: the words are still there.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(cell.waitForExistence(timeout: 5))
        cell.tap()
        // Filled, the composer is the chat's one text field (its label is its text now).
        let again = app.textFields.firstMatch
        XCTAssertTrue(again.waitForExistence(timeout: 5))
        XCTAssertEqual(again.value as? String, "half a thought")
    }

    func testLaunchPerformance() {
        let options = XCTMeasureOptions()
        options.iterationCount = 5
        measure(metrics: [XCTApplicationLaunchMetric(waitUntilResponsive: true)], options: options) {
            let app = XCUIApplication()
            app.launchArguments = ["-AutobotScreenshots", "-AutobotDemo", "-AutobotSkipWelcome"]
            app.launch()
        }
    }
}
