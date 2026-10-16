import Foundation
import Testing
@testable import Autobot

/// Privacy receipts on iOS: the bridge's JSON decodes (old states without receipts too), and the
/// audit log ties requests to turns with the bytes read.
struct ReceiptTests {
    private func decode(_ json: String) throws -> SessionItem {
        try JSONDecoder().decode(SessionItem.self, from: Data(json.utf8))
    }

    @Test func oldStatesWithoutAReceiptStillDecode() throws {
        let item = try decode(#"{"type":"assistant","key":"e3","text":"hi","reasoning":null,"interrupted":false,"outputTokens":4,"model":"m"}"#)
        guard case .assistant(let key, let text, _, _, _, _, let receipt, _, _) = item else { Issue.record("expected an answer"); return }
        #expect(key == "e3" && text == "hi")
        #expect(receipt == nil)
        // The bridge encodes defaults, so a turn without a receipt sends an explicit null.
        guard case .assistant(_, _, _, _, _, _, nil, _, _) = try decode(#"{"type":"assistant","key":"e4","text":"x","receipt":null}"#) else {
            Issue.record("expected no receipt"); return
        }
    }

    @Test func aReceiptFromTheBridgeDecodes() throws {
        let json = #"""
        {"type":"assistant","key":"e9","text":"done","reasoning":null,"interrupted":false,"outputTokens":null,"model":"glm-5",
         "receipt":{"turn":2,"line":"2 hosts · 14 KB out · no retention","onDevice":false,"tracked":true,
           "hosts":[{"host":"opencode.ai","route":"tor","requests":2,"bytesSent":12000,"bytesReceived":30000,"blocked":0,"tags":["Go"]},
                    {"host":"example.org","route":"tor","requests":1,"bytesSent":2336,"rtt":12}],
           "bytesSent":14336,"bytesReceived":30000,"routes":["tor"],
           "models":[{"provider":"p","model":"glm-5","name":"OpenCode Go","onDevice":false,"policy":"no retention","policyNote":"no training · zero retention"}],
           "tools":[{"name":"web_fetch","network":true},{"name":"read","network":false}],"fallbacks":[],"redactions":3}}
        """#
        guard case .assistant(_, _, _, _, _, _, let r?, _, _) = try decode(json) else { Issue.record("expected a receipt"); return }
        #expect(r.turn == 2)
        #expect(r.line == "2 hosts · 14 KB out · no retention")
        #expect(!r.onDevice && r.tracked)
        #expect(r.hosts.map(\.host) == ["opencode.ai", "example.org"])
        #expect(r.hosts[1].bytesReceived == 0 && r.hosts[1].tags.isEmpty) // missing fields default
        #expect(r.models.first?.policy == "no retention")
        #expect(r.tools.filter(\.network).map(\.name) == ["web_fetch"])

        // Round trip through the Swift encoder.
        let item = try decode(json)
        #expect(try JSONDecoder().decode(SessionItem.self, from: JSONEncoder().encode(item)) == item)
    }

    @Test func aMinimalOnDeviceReceiptDecodes() throws {
        let item = try decode(#"{"type":"assistant","key":"e1","text":"hi","receipt":{"turn":1,"line":"on device","onDevice":true}}"#)
        guard case .assistant(_, _, _, _, _, _, let r?, _, _) = item else { Issue.record("expected a receipt"); return }
        #expect(r.onDevice && r.hosts.isEmpty && r.models.isEmpty)
    }

    @Test func bytesFormatLikeKotlin() {
        #expect(Receipt.formatBytes(512) == "512 B")
        #expect(Receipt.formatBytes(14_336) == "14 KB")
        #expect(Receipt.formatBytes(2 * 1024 * 1024 + 300_000) == "2.3 MB")
    }

    @Test func theAuditLogTiesRequestsToTurnsWithTheBytesRead() {
        let log = AuditLog(capacity: 10, clock: { 0 })
        let entry = log.record(method: "POST", host: "api.example.com", path: "/v1", bytesSent: 900, route: "tor", blocked: false, session: "s1", turn: 3)
        log.record(method: "GET", host: "huggingface.co", path: "/", route: "direct", blocked: false)
        #expect(entry.session == "s1" && entry.turn == 3)
        #expect(entry.bytesReceived == nil)
        log.received(id: entry.id, bytes: 4096)
        let turn = log.entries.filter { $0.session == "s1" && $0.turn == 3 }
        #expect(turn.count == 1)
        #expect(turn.first?.bytesReceived == 4096) // read back from the shared Kotlin log
    }
}
