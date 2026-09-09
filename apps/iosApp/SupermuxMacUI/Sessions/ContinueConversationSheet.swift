import SwiftUI
import Shared

/// "Continue in new conversation" — same working directory as the source session, with an
/// editable handoff first-message that points the new agent at `read_session` (web
/// `ContinueConversationDialog.vue` parity).
struct ContinueConversationSheet: View {
    let broker: BrokerSession
    let source: SessionInfo
    var onStarted: (String) -> Void
    var onCancel: () -> Void

    @State private var agent: ContinueAgent
    @State private var model: String?
    @State private var models: [ModelInfo] = []
    @State private var reasoningLevels: [ReasoningLevel] = []
    @State private var reasoningLevel: String?
    @State private var reasoningVisible = false
    @State private var message: String
    @State private var submitting = false
    @State private var failed = false
    /// Skip agent-change resets while applying open-dialog defaults from the source session.
    @State private var seeding = true
    @State private var hostAgents: [String] = []
    @FocusState private var messageFocused: Bool

    private var agents: [ContinueAgent] {
        let installed = hostAgents.compactMap { ContinueAgent(rawValue: $0.lowercased()) }
        return installed.isEmpty ? ContinueAgent.allCases : installed
    }

    private var canStart: Bool {
        !submitting
            && !source.workdir.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var modelLabel: String {
        if let model, let m = models.first(where: { $0.id == model }) {
            return m.displayName
        }
        return model ?? "Default"
    }

    init(broker: BrokerSession, source: SessionInfo, onStarted: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
        self.broker = broker
        self.source = source
        self.onStarted = onStarted
        self.onCancel = onCancel
        let next = HandoffPrefill.defaultAgent(sourceAgent: source.agent)
        _agent = State(initialValue: next)
        let sameAgent = (source.agent ?? "").lowercased() == next.rawValue
        _model = State(initialValue: sameAgent ? source.model : nil)
        _reasoningLevel = State(initialValue: sameAgent ? source.reasoningLevel : nil)
        _message = State(initialValue: HandoffPrefill.build(name: source.name, id: source.id))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Same working directory as \(source.name). The new agent is told to read this session first. Edit freely before start.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .listRowBackground(Color.clear)
                }

                Section {
                    pickerRow
                }

                Section("Handoff message") {
                    TextEditor(text: $message)
                        .font(.body)
                        .frame(minHeight: 160)
                        .focused($messageFocused)
                        .accessibilityLabel("Handoff message")
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Continue in new conversation")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onCancel() }
                        .disabled(submitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(submitting ? "Starting…" : "Start") { start() }
                        .disabled(!canStart)
                        .fontWeight(.semibold)
                }
            }
            .alert("Couldn’t start conversation", isPresented: $failed) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("Make sure the selected agent is installed and signed in on this host, then try again.")
            }
            .task {
                await seedFromHost()
                messageFocused = true
            }
            .onChange(of: agent) { _, new in
                guard !seeding else { return }
                model = nil
                reasoningLevel = nil
                Task { await reloadModelsAndReasoning(for: new) }
            }
            .onChange(of: model) { _, _ in
                guard !seeding else { return }
                Task { await reloadReasoning(for: agent) }
            }
        }
        #if os(macOS)
        .frame(minWidth: 440, idealWidth: 480, minHeight: 420, idealHeight: 480)
        #endif
    }

    private var pickerRow: some View {
        HStack(spacing: 8) {
            Menu {
                ForEach(agents) { a in
                    Button {
                        agent = a
                    } label: {
                        HStack {
                            Text(a.label)
                            if agent == a { Image(systemName: "checkmark") }
                        }
                    }
                }
            } label: {
                HStack(spacing: 5) {
                    AgentLogo(agent: agent.rawValue, size: 16)
                    Text(agent.label).font(.caption.weight(.semibold)).lineLimit(1)
                    Image(systemName: "chevron.down").font(.system(size: 8, weight: .bold)).opacity(0.5)
                }
                .padding(.horizontal, 10).padding(.vertical, 5)
                .background(Color.smTertiaryFill, in: Capsule())
            }
            .smMacBorderlessMenu()

            Menu {
                Button("Default") { model = nil }
                ForEach(models, id: \.id) { m in
                    Button(m.displayName) { model = m.id }
                }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "cpu").font(.system(size: 10, weight: .semibold))
                    Text(modelLabel).font(.caption.weight(.semibold)).lineLimit(1)
                    Image(systemName: "chevron.down").font(.system(size: 8, weight: .bold)).opacity(0.5)
                }
                .padding(.horizontal, 10).padding(.vertical, 5)
                .background(Color.smTertiaryFill, in: Capsule())
            }
            .smMacBorderlessMenu()

            if reasoningVisible {
                Menu {
                    ForEach(reasoningLevels, id: \.id) { l in
                        Button(l.id.capitalized) { reasoningLevel = l.id }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "brain").font(.system(size: 10, weight: .semibold))
                        Text((reasoningLevel ?? "").capitalized).font(.caption.weight(.semibold)).lineLimit(1)
                        Image(systemName: "chevron.down").font(.system(size: 8, weight: .bold)).opacity(0.5)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(Color.smTertiaryFill, in: Capsule())
                }
                .smMacBorderlessMenu()
            }
            Spacer(minLength: 0)
        }
    }

    private func seedFromHost() async {
        seeding = true
        let installed = (await broker.agentStatuses()).filter { $0.installed }.map { $0.kind.lowercased() }
        if !installed.isEmpty {
            hostAgents = installed
            // If source agent isn't installed on this host, fall back to first available.
            if !installed.contains(agent.rawValue), let first = agents.first {
                agent = first
                model = nil
                reasoningLevel = nil
            }
        }
        await reloadModelsAndReasoning(for: agent)
        seeding = false
    }

    private func reloadModelsAndReasoning(for agent: ContinueAgent) async {
        models = await broker.listModels(agent.rawValue)
        // Drop a model id that doesn't belong to the new agent list.
        if let model, !models.contains(where: { $0.id == model }) {
            self.model = nil
        }
        await reloadReasoning(for: agent)
    }

    private func reloadReasoning(for agent: ContinueAgent) async {
        let resp = await broker.reasoningLevels(agent.rawValue, model)
        let levels = resp?.levels ?? []
        reasoningLevels = levels
        reasoningVisible = (resp?.visible ?? false) && ReasoningLevelsKt.showReasoningPicker(levels: levels)
        if reasoningVisible {
            if let current = reasoningLevel, levels.contains(where: { $0.id == current }) {
                // keep
            } else {
                reasoningLevel = ReasoningLevelsKt.resolveReasoningLevel(
                    levels: levels,
                    stored: reasoningLevel
                )
            }
        } else {
            reasoningLevel = nil
        }
    }

    private func start() {
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard canStart, !text.isEmpty else { return }
        submitting = true
        Task {
            // Same checkout as the source — never mint a new worktree. Pass the source name +
            // id so the broker can uniquify the display name and copy project/worktree metadata.
            let id = await broker.spawn(
                workdir: source.workdir,
                agent: agent.rawValue,
                name: source.name,
                model: model,
                worktree: false,
                baseBranch: nil,
                reasoningLevel: reasoningLevel,
                inheritFrom: source.id,
                firstMessage: text
            )
            await MainActor.run {
                if let id, !id.isEmpty {
                    onStarted(id)
                } else {
                    failed = true
                    submitting = false
                }
            }
        }
    }
}
