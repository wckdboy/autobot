import AutobotShared
import Foundation
import Testing
@testable import Autobot

/// The shared search codec as Swift sees it, and the host's settings + Keychain source.
@Suite(.serialized)
@MainActor
struct WebSearchTests {
    private func provider(_ name: String) -> SearchProvider { SettingsSchema.shared.searchProvider(value: name) }

    @Test func buildsRequestsForBothProviders() throws {
        let searx = WebSearchConfig(provider: provider("SEARXNG"), baseUrl: "https://searx.example.org/", apiKey: nil, route: "TOR")
        let s = try #require(WebSearchCodec.shared.request(config: searx, query: "kotlin & café", count: 3))
        #expect(s.url == "https://searx.example.org/search?q=kotlin%20%26%20caf%C3%A9&format=json&pageno=1")
        #expect(s.tag == "agent:web_search")
        #expect(s.route == "TOR")

        let brave = WebSearchConfig(provider: provider("BRAVE"), baseUrl: "", apiKey: "k1", route: "INHERIT")
        let b = try #require(WebSearchCodec.shared.request(config: brave, query: "kotlin", count: 5))
        #expect(b.url == "https://api.search.brave.com/res/v1/web/search?q=kotlin&count=5")
        #expect(b.headers["X-Subscription-Token"] == "k1")

        let noKey = WebSearchConfig(provider: provider("BRAVE"), baseUrl: "", apiKey: nil, route: "INHERIT")
        #expect(WebSearchCodec.shared.request(config: noKey, query: "q", count: 5) == nil)
        #expect(WebSearchCodec.shared.problem(config: noKey) != nil)
    }

    @Test func parsesAndMapsErrors() throws {
        let body = #"{"web":{"results":[{"title":"Kotlin","url":"https://kotlinlang.org/","description":"A <strong>modern</strong> language"}]}}"#
        let ok = try #require(WebSearchCodec.shared.parse(provider: provider("BRAVE"), body: body, count: 5) as? WebSearchCodecResultOk)
        #expect(ok.results.map(\.snippet) == ["A modern language"])
        #expect(WebSearchCodec.shared.parse(provider: provider("SEARXNG"), body: "<html>", count: 5) is WebSearchCodecResultFailed)
        #expect(WebSearchCodec.shared.httpError(provider: provider("BRAVE"), status: 401, body: "").code == "AUTH")
        #expect(WebSearchCodec.shared.httpError(provider: provider("SEARXNG"), status: 403, body: "").message.contains("search.formats"))
    }

    @Test func sourceFollowsSettingsAndReadsTheKeyOnDemand() throws {
        let secrets = SecretStore()
        let ref = AutobotShared.AppSettings.companion.SEARCH_API_KEY_REF
        let previous = secrets.get(ref)
        defer { if let previous { try? secrets.put(ref, previous) } else { secrets.delete(ref) } }

        let source = WebSearchSourceHost(secrets: secrets)
        var settings = AppSettings(values: [:])
        source.update(settings)
        #expect(source.config() == nil)

        settings.searchProvider = "BRAVE"
        settings.searchRoute = .tor
        source.update(settings)
        try secrets.put(ref, Secret("brave-key"))
        let config = try #require(source.config())
        #expect(config.apiKey == "brave-key")
        #expect(config.route == "TOR")
        #expect(!config.description.contains("brave-key"))

        settings.searchProvider = "SEARXNG"
        settings.searchBaseUrl = "https://searx.example.org"
        source.update(settings)
        #expect(source.config()?.apiKey == nil)
        #expect(source.config()?.baseUrl == "https://searx.example.org")
    }

    @Test func draftValidation() {
        #expect(WebSearchDraft().problem == nil)
        #expect(WebSearchDraft(provider: "SEARXNG").problem != nil)
        #expect(WebSearchDraft(provider: "SEARXNG", baseUrl: "https://searx.example.org").problem == nil)
        #expect(WebSearchDraft(provider: "BRAVE").problem != nil)
        #expect(WebSearchDraft(provider: "BRAVE", hasStoredKey: true).problem == nil)
        #expect(!WebSearchDraft(provider: "BRAVE", apiKey: "secret").description.contains("secret"))
    }
}
