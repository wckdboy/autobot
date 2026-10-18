import Foundation
import Testing
@testable import Autobot

/// The local-first cascade on iOS: the setting, and the bridge's receipt and offer JSON.
struct CascadeTests {

    @Test func theSettingIsOffUnlessChosenAndRoundTrips() {
        let s = AppSettings()
        #expect(s.cascade == "OFF" && s.values["cascade"] == "OFF")
        #expect(AppSettings(values: ["theme_mode": "LIGHT"]).cascade == "OFF")
        #expect(AppSettings(values: ["cascade": "sometimes"]).cascade == "OFF")
        var ask = s
        ask.cascade = "ASK"
        #expect(AppSettings(values: ask.values) == ask)
        #expect(SettingsSection.privacy.rows.contains(.cascade))
        #expect(SettingsRow.cascade.title() == "Phone first")
    }

    @Test func theCascadeReceiptAndOfferDecode() throws {
        let item = #"""
        {"type":"assistant","key":"e5","text":"I don't know.","model":"qwen3","receipt":{"turn":1,"line":"on device · judge weak","onDevice":true,"tracked":true,
          "cascade":{"role":"local","policy":"ASK","verdict":"WEAK","action":"OFFERED","summary":"This phone answered.","details":["policy · ask","risk · low"]}}}
        """#
        guard case .assistant(_, _, _, _, _, _, let r?, _, _) = try JSONDecoder().decode(SessionItem.self, from: Data(item.utf8)) else {
            Issue.record("expected a receipt"); return
        }
        #expect(r.cascade?.role == "local" && r.cascade?.action == "OFFERED")
        #expect(r.cascade?.details == ["policy · ask", "risk · low"])
        // Older receipts have none.
        guard case .assistant(_, _, _, _, _, _, let old?, _, _) = try JSONDecoder().decode(SessionItem.self, from: Data(#"{"type":"assistant","key":"e6","text":"x","receipt":{"turn":1,"line":"on device"}}"#.utf8)) else {
            Issue.record("expected a receipt"); return
        }
        #expect(old.cascade == nil)

        let projection = try JSONDecoder().decode(SessionProjection.self, from: Data(#"{"items":[],"cascadeOffer":{"turn":3,"text":"The phone isn't sure about this answer: this needs fresh information."}}"#.utf8))
        #expect(projection.cascadeOffer == CascadeOffer(turn: 3, text: "The phone isn't sure about this answer: this needs fresh information."))
        #expect(try JSONDecoder().decode(SessionProjection.self, from: Data(#"{"items":[]}"#.utf8)).cascadeOffer == nil)
    }
}
