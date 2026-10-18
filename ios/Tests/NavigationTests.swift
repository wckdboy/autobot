import Testing
@testable import Autobot

@MainActor
struct NavigationTests {
    /// A chat tool card's image opens that exact item in the gallery detail.
    @Test func toolImageDeepLinksToGalleryItem() {
        let nav = Navigator()
        nav.reset(.run, [.chat(conversationId: "c1")])
        nav.push(.gallery(openId: "img-42"))
        #expect(nav.path == [.chat(conversationId: "c1"), .gallery(openId: "img-42")])
        nav.back()
        #expect(nav.path.last == .chat(conversationId: "c1"))
    }

    /// An installed image model opens the studio with that model, on top of the Models tab.
    @Test func modelOpensImageStudio() {
        let nav = Navigator()
        nav.switchTo(.models)
        nav.push(.imagine(modelId: "catalog:sd"))
        #expect(nav.tab == .models && nav.path == [.imagine(handoffId: nil, intent: .reuse, modelId: "catalog:sd")])
        #expect(nav.path != [.imagine()])
    }
}
