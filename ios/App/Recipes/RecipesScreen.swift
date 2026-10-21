import SwiftUI

/**
 Settings › Recipes: scheduled on-device agents (port of Android's `RecipesScreen.kt`; the copy
 and rules are the shared `RecipeCopy` / `RecipeBridge`). Each row: name, schedule, status, an
 enable switch, Run now and a link to the last result chat.
 */
struct RecipesScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var editing: RecipeDoc?
    @State private var summary = RecipeNotifier.showSummary

    var body: some View {
        let center = app.recipes
        let copy = RecipeRules.copy
        VStack(spacing: 0) {
            // How iOS schedules recipes sits behind the ⓘ, not in a paragraph under the list (as on Android).
            AutobotTopBar(title: copy.SCREEN_TITLE, onBack: { nav.back() }) {
                RowInfo(title: copy.SCREEN_TITLE, text: copy.IOS_NOTE)
                Button { editing = RecipeRules.blank() } label: { Image(systemName: "plus") }
                    .accessibilityLabel(copy.NEW_RECIPE)
            }
            List {
                if !hasLocalModel || center.nodeNotice != nil {
                    Section {
                        if !hasLocalModel {
                            Label(copy.NO_MODEL, systemImage: "exclamationmark.triangle").foregroundStyle(.orange).font(.subheadline)
                        }
                        if let notice = center.nodeNotice {
                            Text(notice).font(.footnote).foregroundStyle(.red).lineLimit(3)
                        }
                    }
                }
                if center.recipes.isEmpty {
                    MarkUnavailable(copy.EMPTY)
                } else {
                    Section {
                        ForEach(center.recipes) { r in row(r) }
                    }
                }
                Section {
                    InfoToggle(
                        title: copy.SUMMARY_SWITCH,
                        info: copy.SUMMARY_DETAIL,
                        isOn: Binding(get: { summary }, set: { summary = $0; RecipeNotifier.showSummary = $0 }),
                    )
                }
            }
            .listStyle(.insetGrouped)
            .animation(.default, value: center.recipes.map(\.id))
        }
        .sheet(item: $editing) { recipe in
            RecipeEditorSheet(initial: recipe, isNew: center.recipe(recipe.id) == nil) { editing = nil }
        }
    }

    private static let secondary = Color(uiColor: .secondaryLabel)

    private var hasLocalModel: Bool { app.providers.get(ProviderPresets.localProviderId) != nil }

    private func row(_ r: RecipeDoc) -> some View {
        let center = app.recipes
        let running = center.running == r.id
        let canRun = !(running || (r.runsOn == nil && !hasLocalModel))
        let session = r.state.lastRun?.sessionId.flatMap { app.conversations.get($0) != nil ? $0 : nil }
        return VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 12) {
                Button { editing = r } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(RecipeRules.where_(r, node: r.runsOn.map { app.providers.get($0)?.displayName ?? "my node" }))
                            .font(.caption).foregroundStyle(Self.secondary)
                        Text(r.name).font(.headline).foregroundStyle(Color.primary).lineLimit(2)
                        Text(running ? "Running…" : RecipeRules.status(r))
                            .font(.subheadline)
                            .foregroundStyle(running ? AnyShapeStyle(.tint) : AnyShapeStyle(Self.secondary))
                            .lineLimit(2)
                        if let last = r.state.lastRun, !last.summary.isEmpty {
                            Text(last.summary).font(.footnote).foregroundStyle(last.done ? AnyShapeStyle(Self.secondary) : AnyShapeStyle(.red)).lineLimit(2)
                        }
                        if let note = r.state.lastRun?.notes.first { Text(note).font(.footnote).foregroundStyle(.red).lineLimit(2) }
                    }
                    // A borderless button centres several lines and tints a hierarchical style:
                    // leading text and concrete colours keep the row a row.
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                Toggle("Enable \(r.name)", isOn: Binding(get: { r.enabled }, set: { center.setEnabled(r.id, $0) }))
                    .labelsHidden()
            }
            HStack(spacing: 16) {
                Button(RecipeRules.copy.RUN_NOW) { center.runNow(r.id) }
                    .disabled(!canRun)
                if let session {
                    Button(RecipeRules.copy.OPEN_RESULT) { nav.push(.chat(conversationId: session)) }
                }
            }
            .buttonStyle(.borderless)
            .font(.subheadline)
        }
        .padding(.vertical, 4)
        .swipeActions(edge: .leading) {
            if canRun { Button(RecipeRules.copy.RUN_NOW) { center.runNow(r.id) }.tint(.accentColor) }
        }
        .contextMenu {
            Button("Edit", systemImage: "pencil") { editing = r }
            Button(RecipeRules.copy.RUN_NOW, systemImage: "play") { center.runNow(r.id) }.disabled(!canRun)
            if let session {
                Button(RecipeRules.copy.OPEN_RESULT, systemImage: "bubble.left") { nav.push(.chat(conversationId: session)) }
            }
        }
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

    init(initial: RecipeDoc, isNew: Bool, onDismiss: @escaping () -> Void) {
        _draft = State(initialValue: initial)
        self.isNew = isNew
        self.onDismiss = onDismiss
    }

    var body: some View {
        let copy = RecipeRules.copy
        NavigationStack {
            Form {
                Section {
                    FormField("Name", text: $draft.name, prompt: "Morning brief")
                    TextField("Prompt", text: $draft.prompt, prompt: Text("What should the agent do?"), axis: .vertical)
                        .lineLimit(4 ... 12)
                }

                Section("Schedule") {
                    Picker("Repeats", selection: Binding(get: { draft.schedule.kind }, set: { draft.schedule = draft.schedule.with(kind: $0) })) {
                        ForEach(RecipeScheduleDoc.Kind.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    scheduleDetail
                }

                let nodes = app.providers.providers.filter { $0.kind == .invincibleNode }
                if !nodes.isEmpty || draft.runsOn != nil {
                    Section {
                        Picker(copy.RUNS_ON_HEADER, selection: Binding(get: { draft.runsOn ?? "" }, set: { draft.runsOn = $0.isEmpty ? nil : $0 })) {
                            ForEach([""] + nodes.map(\.id), id: \.self) { id in
                                Text(id.isEmpty ? copy.THIS_PHONE : (nodes.first { $0.id == id }?.displayName ?? "my node")).tag(id)
                            }
                        }
                        if draft.runsOn != nil {
                            ForEach(RecipeRules.nodeLimits(draft), id: \.self) { Text($0).font(.footnote).foregroundStyle(.red) }
                        }
                    } footer: {
                        if draft.runsOn != nil { Text(copy.NODE_DETAIL) }
                    }
                }
                if draft.runsOn == nil {
                    Section {
                        Picker(copy.MODEL_HEADER, selection: $draft.model) {
                            ForEach([""] + app.recipeModels.map(\.id), id: \.self) { id in
                                Text(id.isEmpty ? copy.MODEL_DEFAULT : (app.recipeModels.first { $0.id == id }?.title ?? id)).tag(id)
                            }
                        }
                    }
                }

                Section {
                    ForEach(RecipeRules.toolGroups) { group in
                        Toggle(isOn: Binding(get: { group.tools.allSatisfy(draft.tools.contains) }, set: { on in
                            draft.tools = RecipeRules.toggleTools(draft.tools, group: group.id, on: on)
                        })) {
                            Text(group.label)
                            Text(group.detail)
                        }
                    }
                } header: {
                    Text(copy.TOOLS_HEADER)
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(copy.TOOLS_DETAIL)
                        if draft.usesNetwork { Text(copy.NETWORK_NOTE) }
                    }
                }

                Section {
                    InfoToggle(title: copy.RECENT_CHATS, info: copy.RECENT_CHATS_DETAIL, isOn: $draft.includeRecentChats)
                    Toggle(copy.CHARGING, isOn: $draft.requiresCharging)
                    Toggle(copy.BATTERY, isOn: $draft.requiresBatteryNotLow)
                    Toggle("Enabled", isOn: $draft.enabled)
                }

                if !problems.isEmpty {
                    Section {
                        ForEach(problems, id: \.self) { Text($0).foregroundStyle(.red) }
                    }
                }

                if !isNew {
                    Section {
                        Button(copy.DELETE, role: .destructive) { confirmDelete = true }
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity)
                    }
                }
            }
            .formStyle(.grouped)
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle(isNew ? "New recipe" : "Edit recipe")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onDismiss) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        problems = app.recipes.save(draft)
                        if problems.isEmpty { onDismiss() }
                    }
                }
            }
        }
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
                Text(h == 1 ? "Every hour" : "Every \(h) hours")
            }
        case .weekly(let day, let minute):
            Picker("Day", selection: Binding(get: { day }, set: { draft.schedule = .weekly(dayOfWeek: $0, minuteOfDay: minute) })) {
                ForEach(Array(1 ... 7), id: \.self) { Text(RecipeRules.dayNames[$0 - 1]).tag($0) }
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
    }
}

extension AppContainer {
    /// On-device chat models a recipe can run on (installed llama.cpp models, Apple Intelligence when it can answer).
    var recipeModels: [(id: String, title: String)] {
        library.models.filter(\.isLocalChat).map { ($0.id, $0.title) }
            + (AppleIntelligence.unavailability == nil ? [(AppleIntelligence.id, "Apple Intelligence")] : [])
    }
}
