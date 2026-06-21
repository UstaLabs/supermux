import SwiftUI

/// Settings screen for voice-dictation cleanup.
///
/// Mirrors `VoiceSettingsView.vue` on the web PWA.
/// Broker calls used: `listModels("claude")`, `config()`, `saveConfig(voiceCleanupModel:)`.
struct VoiceSettingsView: View {
    let broker: BrokerSession

    @State private var models: [ModelInfo] = []
    @State private var selected = ""   // "" = Default (Haiku)
    @State private var loading = true
    @State private var saving = false
    @State private var saved = false
    @State private var error: String?

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
        .navigationBarTitleDisplayMode(.inline)
        .tint(Theme.teal)
        .task { await load() }
    }

    private var form: some View {
        Form {
            Section {
                Picker("Cleanup model", selection: $selected) {
                    Text("Default (Haiku)").tag("")
                    ForEach(models, id: \.id) { m in
                        Text(m.displayName).tag(m.id)
                    }
                }
            } footer: {
                Text("Model used to clean up voice-dictation transcripts. Default is Haiku.")
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
        async let modelsResult = broker.listModels("claude")
        async let configResult = broker.config()
        let (ms, cfg) = await (modelsResult, configResult)
        models = ms
        selected = cfg?.voiceCleanupModel ?? ""
        error = nil
    }

    private func save() async {
        saving = true
        saved = false
        error = nil
        defer { saving = false }
        await broker.saveConfig(voiceCleanupModel: selected.isEmpty ? nil : selected)
        saved = true
        try? await Task.sleep(for: .seconds(2))
        saved = false
    }
}
