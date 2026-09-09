import SwiftUI
import Shared

/// Curator sub-screen — daily knowledge digest schedule + agent/model/effort
/// (same knobs as session launch / PA create).
struct CuratorSettingsView: View {
    let broker: BrokerSession

    @State private var curatorEnabled = false
    @State private var curatorHour = 1
    @State private var curatorMinute = 0
    @State private var agent = "claude"
    @State private var model: String?
    @State private var models: [ModelInfo] = []
    @State private var reasoningLevel: String?
    @State private var reasoningLevels: [ReasoningLevel] = []
    @State private var reasoningVisible = false
    @State private var nextRun: String?
    @State private var loading = true
    /// Suppress auto-save while the initial GET lands and derived pickers settle.
    @State private var ready = false

    private static let agents = ["claude", "codex", "cursor", "opencode", "grok"]

    var body: some View {
        Form {
            Section("Curator") {
                Toggle("Daily knowledge digest", isOn: $curatorEnabled)
                    .onChange(of: curatorEnabled) { _, _ in saveIfReady() }
                if curatorEnabled {
                    DatePicker("Time", selection: timeBinding, displayedComponents: .hourAndMinute)
                    if let n = nextRun { LabeledContent("Next run", value: n) }
                }
            }
            Section {
                Picker("Agent", selection: $agent) {
                    ForEach(Self.agents, id: \.self) { a in
                        HStack {
                            AgentLogo(agent: a, size: 18)
                            Text(a.capitalized)
                        }.tag(a)
                    }
                }
                Picker("Model", selection: modelBinding) {
                    Text("Default").tag(String?.none)
                    ForEach(models, id: \.id) { m in
                        Text(m.displayName).tag(String?.some(m.id))
                    }
                }
                if reasoningVisible {
                    Picker("Thinking", selection: reasoningBinding) {
                        ForEach(reasoningLevels, id: \.id) { l in
                            Text(l.id.capitalized).tag(String?.some(l.id))
                        }
                    }
                }
            } header: {
                Text("Agent")
            } footer: {
                Text("Which agent runs the nightly curation pass.")
            }
            Section {
                Button("Run now") { broker.runCuratorNow() }
            }
        }
        .navigationTitle("Curator")
        .tint(Theme.teal)
        .task { await load() }
        .task(id: agent) {
            let loaded = await broker.listModels(agent)
            models = loaded
            if let selected = model, !loaded.contains(where: { $0.id == selected }) {
                model = nil
            }
        }
        .task(id: "\(agent)|\(model ?? "")") {
            let resp = await broker.reasoningLevels(agent, model)
            let levels = resp?.levels ?? []
            reasoningLevels = levels
            reasoningVisible = (resp?.visible ?? false) && ReasoningLevelsKt.showReasoningPicker(levels: levels)
            if reasoningVisible {
                let resolved = ReasoningLevelsKt.resolveReasoningLevel(levels: levels, stored: reasoningLevel)
                if resolved != reasoningLevel { reasoningLevel = resolved }
            } else if reasoningLevel != nil {
                reasoningLevel = nil
            }
        }
        .onChange(of: agent) { old, new in
            guard ready, old != new else { return }
            model = nil
            reasoningLevel = nil
            saveIfReady()
        }
        .onChange(of: model) { _, _ in saveIfReady() }
        .onChange(of: reasoningLevel) { _, _ in saveIfReady() }
        .disabled(loading)
    }

    private var timeBinding: Binding<Date> {
        Binding(
            get: { Calendar.current.date(from: DateComponents(hour: curatorHour, minute: curatorMinute)) ?? Date() },
            set: { d in
                let c = Calendar.current.dateComponents([.hour, .minute], from: d)
                curatorHour = c.hour ?? 1
                curatorMinute = c.minute ?? 0
                saveIfReady()
            }
        )
    }

    private var modelBinding: Binding<String?> {
        Binding(get: { model }, set: { model = $0 })
    }

    private var reasoningBinding: Binding<String?> {
        Binding(get: { reasoningLevel }, set: { reasoningLevel = $0 })
    }

    private func load() async {
        loading = true
        ready = false
        defer { loading = false }
        if let c = await broker.curatorSettings() {
            curatorEnabled = c.config.enabled
            curatorHour = Int(c.config.hour)
            curatorMinute = Int(c.config.minute)
            let a = c.config.agent
            agent = Self.agents.contains(a) ? a : "claude"
            model = c.config.model
            reasoningLevel = c.config.reasoningLevel
            nextRun = c.nextRun.map(Self.formatNextRun)
        }
        ready = true
    }

    private func saveIfReady() {
        guard ready else { return }
        broker.saveCurator(
            enabled: curatorEnabled,
            hour: curatorHour,
            minute: curatorMinute,
            agent: agent,
            model: model,
            reasoningLevel: reasoningLevel
        )
    }

    /// The broker sends an ISO-8601 instant; show it as a local, human date-time
    /// ("8 Jul 2026 at 22:00") instead of the raw `2026-07-08T22:00:00.000Z`.
    private static func formatNextRun(_ iso: String) -> String {
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = parser.date(from: iso) ?? ISO8601DateFormatter().date(from: iso) else { return iso }
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
