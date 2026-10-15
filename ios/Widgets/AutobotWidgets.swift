import AppIntents
import SwiftUI
import WidgetKit

/*
 Home screen, lock screen and Control Center entry points. All of them are static: no timeline
 ever carries a title, a message or anything else from the user's chats, so nothing private can
 show on the home or lock screen. They only open `autobot://` links; the app's lock applies.
 */

private enum Links {
    static let ask = URL(string: "autobot://ask")!
    static let voice = URL(string: "autobot://voice")!
    static let imagine = URL(string: "autobot://imagine")!
}

private enum Ink {
    static let paper = Color(red: 0.957, green: 0.933, blue: 0.902)
    static let graphite = Color(red: 0.055, green: 0.055, blue: 0.055)
    static let signal = Color(red: 0.910, green: 0.251, blue: 0.110)
}

struct StaticEntry: TimelineEntry {
    let date: Date
}

struct StaticProvider: TimelineProvider {
    func placeholder(in context: Context) -> StaticEntry { StaticEntry(date: .now) }

    func getSnapshot(in context: Context, completion: @escaping (StaticEntry) -> Void) {
        completion(StaticEntry(date: .now))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<StaticEntry>) -> Void) {
        completion(Timeline(entries: [StaticEntry(date: .now)], policy: .never))
    }
}

/// Small: one tap to ask. Medium: Ask · Voice · Imagine.
struct LauncherWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "ai.wckd.autobot.launcher", provider: StaticProvider()) { _ in
            LauncherView()
                .containerBackground(Ink.graphite, for: .widget)
        }
        .configurationDisplayName("Autobot")
        .description("Ask, talk or imagine. Shows nothing from your chats.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

private struct LauncherView: View {
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if family == .systemSmall {
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: "bubble.left.and.text.bubble.right").font(.title2).foregroundStyle(Ink.signal)
                Spacer()
                Text("ASK").font(.system(.title, design: .monospaced).weight(.bold)).foregroundStyle(Ink.paper)
                Text("autobot").font(.system(.caption, design: .monospaced)).foregroundStyle(Ink.paper.opacity(0.6))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .widgetURL(Links.ask)
        } else {
            HStack(spacing: 8) {
                button("Ask", "bubble.left.and.text.bubble.right", Links.ask)
                button("Voice", "waveform", Links.voice)
                button("Imagine", "photo", Links.imagine)
            }
        }
    }

    private func button(_ title: String, _ symbol: String, _ url: URL) -> some View {
        Link(destination: url) {
            VStack(spacing: 8) {
                Image(systemName: symbol).font(.title2).foregroundStyle(Ink.signal)
                Text(title.uppercased()).font(.system(.caption, design: .monospaced).weight(.semibold)).foregroundStyle(Ink.paper)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(RoundedRectangle(cornerRadius: 10).stroke(Ink.paper.opacity(0.2)))
        }
    }
}

/// Lock screen: voice chat (the phone and then the app unlock first).
struct VoiceAccessoryWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "ai.wckd.autobot.voice-accessory", provider: StaticProvider()) { _ in
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "waveform").font(.title3)
            }
            .widgetURL(Links.voice)
            .containerBackground(.clear, for: .widget)
        }
        .configurationDisplayName("Autobot voice")
        .description("Opens a voice chat.")
        .supportedFamilies([.accessoryCircular])
    }
}

/// Control Center, lock screen controls and the Action button: a voice chat.
struct VoiceControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "ai.wckd.autobot.voice-control") {
            ControlWidgetButton(action: OpenURLIntent(Links.voice)) {
                Label("Autobot voice", systemImage: "waveform")
            }
        }
        .displayName("Autobot voice")
        .description("Opens a voice chat. Shows nothing from your chats.")
    }
}

@main
struct AutobotWidgets: WidgetBundle {
    var body: some Widget {
        LauncherWidget()
        VoiceAccessoryWidget()
        VoiceControl()
    }
}
