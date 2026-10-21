import SwiftUI

extension Receipt: Identifiable {
    var id: Int { turn }

    /// Short policy wording of a model that trains on prompts (Kotlin `Receipts.policyLabel`).
    static let trains = "trains on prompts"
}

/**
 The receipt under a turn's last answer: `ON DEVICE` or `2 HOSTS · 14 KB OUT · NO RETENTION`.
 It stamps in when the turn ends; a tap opens [ReceiptSheet].
 */
struct ReceiptLine: View {
    let receipt: Receipt?
    let onOpen: (Receipt) -> Void

    var body: some View {
        ZStack(alignment: .leading) {
            if let receipt {
                let trains = receipt.models.contains { $0.policy == Receipt.trains }
                Button { onOpen(receipt) } label: {
                    Label(receipt.line, systemImage: receipt.onDevice ? "lock.shield.fill" : trains ? "exclamationmark.shield" : "doc.text.magnifyingglass")
                        .foregroundStyle(trains ? AnyShapeStyle(Color.orange) : receipt.onDevice ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                        .chipLook(active: receipt.onDevice)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Privacy receipt: \(receipt.line)")
            }
        }
        .animation(.easeOut(duration: 0.2), value: receipt?.line)
    }
}

/// Everything one turn shared, and with whom. Built on this phone; never sent anywhere.
struct ReceiptSheet: View {
    let receipt: Receipt
    /// Opens the spend and quota board (nil: no link).
    var onOpenUsage: (() -> Void)? = nil

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(headline).font(.title3.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
                } header: {
                    Text("Receipt · turn \(receipt.turn)")
                }

                if !receipt.hosts.isEmpty {
                    Section("Hosts") {
                        ForEach(Array(receipt.hosts.enumerated()), id: \.offset) { _, host in hostRow(host) }
                    }
                }

                if !receipt.models.isEmpty {
                    Section("Model") {
                        ForEach(Array(receipt.models.enumerated()), id: \.offset) { _, m in
                            VStack(alignment: .leading, spacing: 4) {
                                HStack(spacing: 8) {
                                    Text(m.model).font(.callout.monospaced()).lineLimit(1).truncationMode(.middle)
                                    if let policy = m.policy {
                                        CapsuleTag(policy, color: policy == Receipt.trains ? Color.orange : m.onDevice ? .accentColor : .secondary)
                                    }
                                }
                                Text([m.name, m.policyNote ?? (m.policy == nil ? "data policy not documented" : nil)].compactMap { $0 }.joined(separator: " · "))
                                    .font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                                if let line = receipt.usageLine(m.model) { Text(line).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                            }
                        }
                    }
                }

                if let cascade = receipt.cascade {
                    Section(cascade.role == "cloud" ? "Phone first · \(cascade.followUpTarget) follow-up" : "Phone first") {
                        Text(cascade.summary).font(.footnote).fixedSize(horizontal: false, vertical: true)
                        ChatFlow(spacing: 6) {
                            ForEach(Array(cascade.details.enumerated()), id: \.offset) { _, d in CapsuleTag(d, color: .secondary) }
                        }
                    }
                }

                if receipt.redacted {
                    Section("Redacted") {
                        if receipt.redactions.isEmpty {
                            Text("Nothing personal found").font(.footnote).foregroundStyle(.secondary)
                        } else {
                            ChatFlow(spacing: 6) {
                                ForEach(Array(receipt.redactions.enumerated()), id: \.offset) { _, r in CapsuleTag(r.label) }
                            }
                            Text("The provider saw placeholders like [PERSON_1]. The real values were put back on this phone.")
                                .font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }

                if let usage = receipt.usage {
                    Section {
                        Text(usage).font(.callout.monospaced()).fixedSize(horizontal: false, vertical: true)
                        if let onOpenUsage { Button("Usage", action: onOpenUsage) }
                    } header: {
                        Text("Tokens")
                    } footer: {
                        Text("Counted from the provider's own usage report. Cost uses your price table; models without a price stay unknown.")
                    }
                }

                if !receipt.tools.isEmpty {
                    Section("Tools") {
                        ChatFlow(spacing: 6) {
                            ForEach(Array(receipt.tools.enumerated()), id: \.offset) { _, t in
                                CapsuleTag(t.network ? "\(t.name) · net" : t.name, color: t.network ? .accentColor : .secondary)
                            }
                        }
                    }
                }

                if !receipt.fallbacks.isEmpty {
                    Section("Fallbacks") {
                        ForEach(receipt.fallbacks, id: \.self) { Text($0).font(.footnote).foregroundStyle(Color.orange) }
                    }
                }

                Section {
                } footer: {
                    Text("Built on this phone from the network audit log. Receipts stay in this chat and are never sent anywhere.")
                }
            }
            .navigationTitle("Privacy receipt")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var headline: String {
        if receipt.onDevice { return "Nothing left this phone" }
        if !receipt.tracked { return "Traffic not tracked for this turn" }
        return "\(Receipt.formatBytes(receipt.bytesSent)) sent · \(Receipt.formatBytes(receipt.bytesReceived)) received"
    }

    /// `api.deepseek.com` / `TOR · 2 REQ · 14 KB OUT · 30 KB IN`
    private func hostRow(_ host: Receipt.Host) -> some View {
        let detail = [
            host.route,
            "\(host.requests) req",
            "\(Receipt.formatBytes(host.bytesSent)) out",
            "\(Receipt.formatBytes(host.bytesReceived)) in",
            host.blocked > 0 ? "\(host.blocked) blocked" : nil,
            host.tags.isEmpty ? nil : host.tags.joined(separator: ", "),
        ].compactMap { $0 }.joined(separator: " · ")
        return VStack(alignment: .leading, spacing: 2) {
            Text(host.host).font(.callout.monospaced()).lineLimit(1).truncationMode(.middle)
            Text(detail).font(.caption).foregroundStyle(host.blocked == host.requests ? AnyShapeStyle(Color.orange) : AnyShapeStyle(.secondary)).lineLimit(2)
        }
    }
}
