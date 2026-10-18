import AutobotShared
import Foundation
import Network
import Testing
@testable import Autobot

/// "My node" on iOS: the Network.framework connector under the network policy, and pairing links.
@Suite(.serialized)
struct NodeConnectorTests {
    /// A loopback TCP echo server standing in for a node.
    private final class Echo: @unchecked Sendable {
        let listener: NWListener
        let queue = DispatchQueue(label: "test.echo")

        init() throws {
            listener = try NWListener(using: .tcp, on: .any)
            listener.newConnectionHandler = { [queue] connection in
                connection.start(queue: queue)
                func pump() {
                    connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, done, error in
                        if let data, !data.isEmpty { connection.send(content: data, completion: .contentProcessed { _ in }) }
                        if done || error != nil { connection.cancel() } else { pump() }
                    }
                }
                pump()
            }
        }

        func start() async throws -> UInt16 {
            listener.start(queue: queue)
            for _ in 0..<200 {
                if let port = listener.port?.rawValue, port != 0 { return port }
                try await Task.sleep(for: .milliseconds(10))
            }
            throw NodeConnectError("the echo server did not start")
        }
    }

    private struct Opened: @unchecked Sendable {
        let link: HostNodeLink?
        let blocked: Bool
        let message: String?
    }

    private func open(_ connector: SwiftNodeConnector, _ addresses: [String], session: String? = nil, turn: Int32 = -1) async -> Opened {
        await withCheckedContinuation { continuation in
            connector.open(addresses: addresses, route: "INHERIT", session: session, turn: turn) { link, blocked, message in
                continuation.resume(returning: Opened(link: link, blocked: blocked.boolValue, message: message))
            }
        }
    }

    private func read(_ link: HostNodeLink, _ count: Int32) async -> Data? {
        await withCheckedContinuation { continuation in
            link.read(count: count) { bytes, _ in
                continuation.resume(returning: bytes.map { BackupBytes.shared.toData(bytes: $0) as Data })
            }
        }
    }

    private func write(_ link: HostNodeLink, _ data: Data) async -> String? {
        await withCheckedContinuation { continuation in
            link.write(bytes: BackupBytes.shared.fromData(data: data)) { error in continuation.resume(returning: error) }
        }
    }

    @Test func aLoopbackNodeCarriesBytesAndIsAuditedForTheTurn() async throws {
        let echo = try Echo()
        let port = try await echo.start()
        defer { echo.listener.cancel() }
        let policy = NetworkPolicy()
        policy.setMode(.direct)
        let audit = AuditLog(capacity: 50, clock: { 0 })
        let connector = SwiftNodeConnector(policy: policy, audit: audit)

        // A first address that refuses is skipped for the next one.
        let opened = await open(connector, ["127.0.0.1:1", "127.0.0.1:\(port)"], session: "chat-1", turn: 3)
        let link = try #require(opened.link, "\(opened.message ?? "")")
        #expect(await write(link, Data("hello".utf8)) == nil)
        #expect(await read(link, 3) == Data("hel".utf8))
        #expect(await read(link, 2) == Data("lo".utf8))
        link.close()
        link.close()

        let entries = audit.entries
        #expect(entries.count == 2)
        let refused = entries[0]
        #expect(refused.error != nil && refused.blocked == false)
        let used = entries[1]
        #expect(used.method == "NOISE" && used.route == "loopback")
        #expect(used.session == "chat-1" && used.turn == 3)
        #expect(used.bytesSent == 5 && used.bytesReceived == 5)
    }

    @Test func offlineRefusesBeforeAnySocket() async {
        let policy = NetworkPolicy()
        policy.setMode(.offline)
        let audit = AuditLog(capacity: 50, clock: { 0 })
        let opened = await open(SwiftNodeConnector(policy: policy, audit: audit), ["192.168.1.20:7778"])
        #expect(opened.link == nil && opened.blocked)
        #expect(opened.message?.isEmpty == false)
        #expect(audit.entries.single?.blocked == true)
        #expect(audit.entries.single?.host == "192.168.1.20")
    }

    /**
     The whole iOS path against a real node, when `INVINCIBLE_PAIR_CODE` holds a fresh code from
     `invincible pair` (pass it as `TEST_RUNNER_INVINCIBLE_PAIR_CODE` to xcodebuild; the node needs
     a model): pair, store the provider as the app does, chat through the Noise channel, and find
     the node's signed receipt in the turn's privacy receipt.
     */
    @MainActor @Test func pairsAndChatsWithARealNode() async throws {
        guard let code = ProcessInfo.processInfo.environment["INVINCIBLE_PAIR_CODE"], !code.isEmpty else { return }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("node-live-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let keys = KeyManager()
        let store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: keys)
        let secrets = SecretStore()
        let policy = NetworkPolicy()
        policy.setMode(.direct)
        let audit = AuditLog()
        let snapshots = HostSnapshots()
        let conversations = ConversationRepository(store: store)
        let providers = ProviderRepository(store: store, secrets: secrets)
        let runtime = SharedAgentRuntime(
            conversations: conversations, providers: providers, store: Autobot.SessionStore(store: store),
            clients: HttpClientFactory(policy: policy, auditLog: audit), llm: LocalLlm(),
            attachments: AttachmentRepository(keys: keys), secrets: secrets, snapshots: snapshots,
        )
        #expect(runtime.pairingFingerprint(code) != nil)
        let paired = await runtime.pairNode(code: code, deviceName: "iPhone test")
        let secret = try #require(paired.secret, "\(paired.error ?? "")")
        let node = try #require(paired.node)
        let provider = try providers.save(
            Provider(id: "node-live", kind: .invincibleNode, displayName: node, baseUrl: "http://invincible/v1", defaultModel: "auto", presetId: "invincible"),
            apiKey: .set(Secret(secret)),
        )
        snapshots.setProviders(providers.providers)

        let chat = conversations.create(incognito: false, providerId: provider.id, model: "auto")
        let session = runtime.session(chat.id)
        await runtime.send(chat.id, text: "Reply with exactly: ios ok /no_think", images: [])
        #expect(await eventually(.seconds(240)) { !session.running && session.transcript.items.count >= 2 })
        let answer = session.transcript.items.compactMap { item -> (String, Autobot.Receipt?)? in
            if case .assistant(_, let text, _, _, _, _, let receipt, _, _) = item { return (text, receipt) } else { return nil }
        }.last
        let (text, _) = try #require(answer)
        #expect(text.lowercased().contains("ios ok"), "\(text)")
        #expect(audit.entries.contains { $0.method == "NOISE" && $0.session == chat.id && $0.bytesSent ?? 0 > 0 })
        // The receipt arrives with the turn's end: the node's signed part, verified.
        #expect(await eventually(.seconds(30)) {
            session.transcript.items.contains { item in
                if case .assistant(_, _, _, _, _, _, let receipt?, _, _) = item { receipt.line.contains("kept on \(node) ✓") } else { false }
            }
        }, "\(session.transcript.items)")
    }

    @Test func aPairingLinkOpensThePairingSheetOnly() {
        let link = URL(string: "invincible://pair?v=0&k=abc&s=def&a=10.0.2.2:7778")!
        #expect(EntryLink.parse(link) == .pairNode(code: link.absoluteString))
        #expect(EntryRouting.plan(.pairNode(code: "x"), canChat: false) { _ in nil } == .pairNode(code: "x"))
        #expect(EntryLink.parse(URL(string: "invincible://other")!) == nil)
    }
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}
