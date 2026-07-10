import SwiftUI
import Shared

/// One selectable voice-cleanup engine (direct-API adapter layer). `family` is the
/// AgentKind whose models `listModels` returns for that engine.
private struct VoiceEngine: Identifiable {
    let id: String
    let label: String
    let family: String
}

/// Curated mirror of ENGINES in src/core/agent-api/index.ts. The gated Claude
/// adapter (ban-risk opt-in) is intentionally not offered here.
private let voiceEngines: [VoiceEngine] = [
    .init(id: "codex", label: "Codex", family: "codex"),
    .init(id: "opencode-zen", label: "OpenCode Zen", family: "opencode"),
    .init(id: "opencode-go", label: "OpenCode Go", family: "opencode"),
    .init(id: "cursor", label: "Cursor", family: "cursor"),
]
private let defaultVoiceEngine = "codex"
private func voiceFamily(_ engine: String) -> String {
    voiceEngines.first { $0.id == engine }?.family ?? "codex"
}
private func voiceEngineLabel(_ engine: String) -> String {
    voiceEngines.first { $0.id == engine }?.label ?? engine
}

/// Settings screen for voice-dictation cleanup.
///
/// Mirrors `VoiceSettingsView.vue` on the web PWA: pick the cleanup ENGINE
/// (Codex default / OpenCode / Cursor), then a MODEL for that engine ("Default" =
/// the engine's own). Switching engine resets the now-irrelevant model.
/// Broker calls used: `config()`, `listModels(family)`, `saveConfig(voiceCleanupEngine:voiceCleanupModel:)`.
struct VoiceSettingsView: View {
    let broker: BrokerSession

    @State private var models: [ModelInfo] = []
    @State private var engine = defaultVoiceEngine
    @State private var selected = ""   // "" = the engine's default
    @State private var loading = true
    @State private var saving = false
    @State private var saved = false
    @State private var error: String?
    @State private var showGlossary = false

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                form
            }
        }
        .navigationTitle("Voice")
        .smInlineNavigationTitle()
        .tint(Theme.teal)
        .sheet(isPresented: $showGlossary) { GlossaryView(broker: broker) }
        .task { await load() }
    }

    // A binding whose setter fires only on real user selection (load() sets the
    // @State directly, bypassing this) — so changing engine resets the stale model
    // and reloads the new engine's list without clobbering the loaded selection.
    private var engineBinding: Binding<String> {
        Binding(
            get: { engine },
            set: { newEngine in
                guard newEngine != engine else { return }
                engine = newEngine
                selected = ""
                Task { models = await broker.listModels(voiceFamily(newEngine)) }
            }
        )
    }

    private var form: some View {
        Form {
            Section {
                Picker("Cleanup engine", selection: engineBinding) {
                    ForEach(voiceEngines) { e in
                        Text(e.label).tag(e.id)
                    }
                }
                Picker("Cleanup model", selection: $selected) {
                    Text("Default").tag("")
                    ForEach(models, id: \.id) { m in
                        Text(m.displayName).tag(m.id)
                    }
                }
            } footer: {
                Text("Engine that cleans up voice-dictation transcripts, and the model it uses. Default uses the engine's own model.")
            }

            Section {
                Button {
                    showGlossary = true
                } label: {
                    Label("Dictation glossary", systemImage: "text.book.closed")
                }
                .tint(.primary)
            } footer: {
                Text("Project & technical terms to bias dictation toward (shared across devices).")
            }

            Section {
                if let error {
                    Text(error)
                        .foregroundStyle(.red)
                }

                Button {
                    Task { await save() }
                } label: {
                    HStack {
                        if saving {
                            ProgressView()
                                .padding(.trailing, 4)
                            Text("Saving…")
                        } else if saved {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(Theme.teal)
                            Text("Saved")
                        } else {
                            Text("Save")
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .disabled(saving)
            }
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        let cfg = await broker.config()
        let cfgEngine = cfg?.voiceCleanupEngine ?? ""
        engine = cfgEngine.isEmpty ? defaultVoiceEngine : cfgEngine
        selected = cfg?.voiceCleanupModel ?? ""
        models = await broker.listModels(voiceFamily(engine))
        error = nil
    }

    private func save() async {
        saving = true
        saved = false
        error = nil
        defer { saving = false }
        // selected == "" sends "", the broker's "reset to the engine's default" sentinel.
        await broker.saveConfig(voiceCleanupModel: selected, voiceCleanupEngine: engine)
        saved = true
        try? await Task.sleep(for: .seconds(2))
        saved = false
    }
}
