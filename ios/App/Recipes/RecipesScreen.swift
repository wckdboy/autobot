import SwiftUI

/**
 Settings › Recipes: scheduled on-device agents (port of Android's `RecipesScreen.kt`; the copy
 and rules are the shared `RecipeCopy` / `RecipeBridge`). Each row: name, schedule, status, an
 enable switch, Run now and a link to the last result chat.
 */
struct RecipesScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var editing: RecipeDoc?
    @State private var summary = RecipeNotifier.showSummary

    var body: some View {
        let center = app.recipes
        let copy = RecipeRules.copy
        VStack(spacing: 0) {
            AutobotTopBar(title: copy.SCREEN_TITLE, onBack: { nav.back() }) {
                IconAction(symbol: "plus", label: copy.NEW_RECIPE, tint: c.primary) { editing = RecipeRules.blank() }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if !hasLocalModel {
                        MicroLabel(copy.NO_MODEL, color: c.error, lines: 3).padding(.vertical, 8)
                    }
                    SectionLabel("scheduled · \(center.recipes.filter(\.enabled).count) on").padding(.vertical, 8)
                    if let notice = center.nodeNotice {
                        MicroLabel(notice, color: c.error, lines: 3).padding(.bottom, 8)
                    }
                    if center.recipes.isEmpty {
                        Text(copy.EMPTY).textStyle(.bodyMedium).foregroundStyle(c.onSurfaceVariant).padding(.vertical, 12)
                    }
                    ForEach(center.recipes) { r in row(r).transition(.inkDissolve) }
                    SwitchRow(
                        title: copy.SUMMARY_SWITCH,
                        isOn: Binding(get: { summary }, set: { summary = $0; RecipeNotifier.showSummary = $0 }),
                        info: copy.SUMMARY_DETAIL,
                    )
                    .padding(.horizontal, -Tokens.Spacing.gutter)
                    .padding(.top, 8)
                    Text(copy.IOS_NOTE)
                        .textStyle(.bodySmall)
                        .foregroundStyle(c.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.vertical, 12)
                }
                .animation(.inkDissolve, value: center.recipes.map(\.id))
                .padding(.horizontal, Tokens.Spacing.gutter)
                .padding(.bottom, 96)
            }
        }
        .background(c.background)
        .sheet(item: $editing) { recipe in
            RecipeEditorSheet(initial: recipe, isNew: center.recipe(recipe.id) == nil) { editing = nil }
        }
    }

    private var hasLocalModel: Bool { app.providers.get(ProviderPresets.localProviderId) != nil }

    private func row(_ r: RecipeDoc) -> some View {
        let center = app.recipes
        let running = center.running == r.id
        return VStack(spacing: 0) {
            HStack(alignment: .top, spacing: 12) {
                Button { editing = r } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        MicroLabel(RecipeRules.where_(r, node: r.runsOn.map { app.providers.get($0)?.displayName ?? "my node" }))
                        Text(r.name).textStyle(.titleMedium).foregroundStyle(c.onSurface).lineLimit(2)
                        Text(running ? "Running…" : RecipeRules.status(r))
                            .textStyle(.micro)
                            .foregroundStyle(running ? c.primary : c.onSurfaceVariant)
                            .lineLimit(2)
                        if let last = r.state.lastRun, !last.summary.isEmpty {
                            MicroLabel(last.summary, color: last.done ? nil : c.error, lines: 2)
                        }
                        if let note = r.state.lastRun?.notes.first { MicroLabel(note, color: c.error, lines: 2) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                Toggle("", isOn: Binding(get: { r.enabled }, set: { center.setEnabled(r.id, $0) }))
                    .labelsHidden()
                    .tint(c.primary)
                    .accessibilityLabel("Enable \(r.name)")
            }
            HStack(spacing: 16) {
                TextAction(RecipeRules.copy.RUN_NOW, color: c.primary) { center.runNow(r.id) }
                    .disabled(running || (r.runsOn == nil && !hasLocalModel))
                if let session = r.state.lastRun?.sessionId, app.conversations.get(session) != nil {
                    TextAction(RecipeRules.copy.OPEN_RESULT, color: c.onSurface) { nav.push(.chat(conversationId: session)) }
                }
                Spacer()
            }
            .padding(.top, 6)
            .padding(.bottom, 12)
            Hairline()
        }
        .padding(.top, 12)
    }
}

/// Create or edit a recipe. Changes apply on Save.
struct RecipeEditorSheet: View {
    let isNew: Bool
    let onDismiss: () -> Void
    @State private var draft: RecipeDoc
    @State private var problems: [String] = []
    @State private var confirmDelete = false
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c

    init(initial: RecipeDoc, isNew: Bool, onDismiss: @escaping () -> Void) {
        _draft = State(initialValue: initial)
        self.isNew = isNew
        self.onDismiss = onDismiss
    }

    var body: some View {
        let copy = RecipeRules.copy
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                SectionLabel(isNew ? "new recipe" : "edit recipe")
                ConsoleTextField(text: $draft.name, placeholder: "Morning brief", label: "Name")
                ConsoleTextField(text: $draft.prompt, placeholder: "What should the agent do?", label: "Prompt", minLines: 4, maxLines: 12)

                MicroLabel("schedule")
                ChipRow(items: RecipeScheduleDoc.Kind.allCases, selected: draft.schedule.kind, label: \.label) { kind in
                    draft.schedule = draft.schedule.with(kind: kind)
                }
                scheduleDetail

                let nodes = app.providers.providers.filter { $0.kind == .invincibleNode }
                if !nodes.isEmpty || draft.runsOn != nil {
                    MicroLabel(copy.RUNS_ON_HEADER.lowercased())
                    ChipRow(items: [""] + nodes.map(\.id), selected: draft.runsOn ?? "", label: { id in
                        id.isEmpty ? copy.THIS_PHONE : (nodes.first { $0.id == id }?.displayName ?? "my node")
                    }) { draft.runsOn = $0.isEmpty ? nil : $0 }
                    if draft.runsOn != nil {
                        Text(copy.NODE_DETAIL).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                        ForEach(RecipeRules.nodeLimits(draft), id: \.self) { MicroLabel($0, color: c.error, lines: 2) }
                    }
                }
                if draft.runsOn == nil {
                    MicroLabel(copy.MODEL_HEADER.lowercased())
                    ChipRow(items: [""] + app.recipeModels.map(\.id), selected: draft.model, label: { id in
                        id.isEmpty ? copy.MODEL_DEFAULT : (app.recipeModels.first { $0.id == id }?.title ?? id)
                    }) { draft.model = $0 }
                }

                MicroLabel(copy.TOOLS_HEADER.lowercased())
                ForEach(RecipeRules.toolGroups) { group in
                    SwitchRow(
                        title: group.label,
                        subtitle: group.detail,
                        isOn: Binding(get: { group.tools.allSatisfy(draft.tools.contains) }, set: { on in
                            draft.tools = RecipeRules.toggleTools(draft.tools, group: group.id, on: on)
                        }),
                    )
                    .padding(.horizontal, -Tokens.Spacing.gutter)
                }
                Text(copy.TOOLS_DETAIL).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                if draft.usesNetwork {
                    Text(copy.NETWORK_NOTE).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                }

                Group {
                    SwitchRow(title: copy.RECENT_CHATS, isOn: $draft.includeRecentChats, info: copy.RECENT_CHATS_DETAIL)
                    SwitchRow(title: copy.CHARGING, isOn: $draft.requiresCharging)
                    SwitchRow(title: copy.BATTERY, isOn: $draft.requiresBatteryNotLow)
                    SwitchRow(title: "Enabled", isOn: $draft.enabled)
                }
                .padding(.horizontal, -Tokens.Spacing.gutter)

                ForEach(problems, id: \.self) { MicroLabel($0, color: c.error, lines: 2) }

                PrimaryButton("Save") {
                    problems = app.recipes.save(draft)
                    if problems.isEmpty { onDismiss() }
                }
                if !isNew {
                    TextAction(copy.DELETE, color: c.error) { confirmDelete = true }.frame(maxWidth: .infinity)
                }
                TextAction("Cancel", color: c.onSurfaceVariant, action: onDismiss).frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 20)
        }
        .background(c.surfaceContainerLow.ignoresSafeArea())
        .presentationDetents([.large])
        .alert("Delete \(draft.name)?", isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) {
                app.recipes.delete(draft.id)
                onDismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Its result chats stay in the Recipes folder.")
        }
    }

    @ViewBuilder private var scheduleDetail: some View {
        switch draft.schedule {
        case .hours(let h):
            Stepper(value: Binding(get: { h }, set: { draft.schedule = .hours($0) }), in: 1 ... 24) {
                Text(h == 1 ? "Every hour" : "Every \(h) hours").textStyle(.bodyMedium).foregroundStyle(c.onSurface)
            }
        case .weekly(let day, let minute):
            ChipRow(items: Array(1 ... 7), selected: day, label: { String(RecipeRules.dayNames[$0 - 1].prefix(3)) }) {
                draft.schedule = .weekly(dayOfWeek: $0, minuteOfDay: minute)
            }
            timePicker(minute)
        case .daily(let minute), .weekdays(let minute):
            timePicker(minute)
        }
    }

    private func timePicker(_ minute: Int) -> some View {
        DatePicker(
            "Time",
            selection: Binding(
                get: { Calendar.current.date(bySettingHour: minute / 60, minute: minute % 60, second: 0, of: Date()) ?? Date() },
                set: { date in
                    let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
                    draft.schedule = draft.schedule.with(minute: (parts.hour ?? 0) * 60 + (parts.minute ?? 0))
                },
            ),
            displayedComponents: .hourAndMinute,
        )
        .textStyle(.bodyMedium)
        .foregroundStyle(c.onSurface)
        .tint(c.primary)
    }
}

extension AppContainer {
    /// On-device chat models a recipe can run on (installed llama.cpp models, Apple Intelligence when it can answer).
    var recipeModels: [(id: String, title: String)] {
        library.models.filter(\.isLocalChat).map { ($0.id, $0.title) }
            + (AppleIntelligence.unavailability == nil ? [(AppleIntelligence.id, "Apple Intelligence")] : [])
    }
}
