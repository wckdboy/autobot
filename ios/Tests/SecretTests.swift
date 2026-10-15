import Foundation
import Testing
@testable import Autobot

/// Port of `SecretTest.kt`, plus the redaction of drafts and key updates.
struct SecretTests {
    @Test func descriptionIsRedacted() {
        let secret = Secret("sk-live-1234567890")
        #expect(secret.description == Secret.redacted)
        #expect(!"\(secret)".contains("sk-live"))
        #expect(!String(describing: secret).contains("sk-live"))
        #expect(!String(reflecting: secret).contains("sk-live"))
        #expect(!"\(ApiKeyUpdate.set(secret))".contains("sk-live"))
        #expect(secret.reveal() == "sk-live-1234567890")
    }

    @Test func containersDoNotLeak() {
        struct Holder { let key: Secret }
        #expect(!"\(Holder(key: Secret("sk-abc")))".contains("sk-abc"))
        #expect(!"\([Secret("sk-abc")])".contains("sk-abc"))
    }

    @Test func providerDraftRedactsKeyInput() {
        var draft = ProviderDraft.fromPreset(ProviderPresets.forKind(.deepseek))
        draft.apiKeyInput = "sk-draft-secret"
        #expect(!draft.description.contains("sk-draft-secret"))
        #expect(draft.description.contains(Secret.redacted))
    }

    @Test func blankDetection() {
        #expect(Secret("  ").isBlank)
        #expect(!Secret("x").isBlank)
    }
}
