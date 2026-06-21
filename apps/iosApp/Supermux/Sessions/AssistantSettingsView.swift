import SwiftUI

/// Full-screen editor for PA identity: the assistant name and the soul.md personality
/// document that is prepended to every session.
///
/// Mirrors `AssistantSettingsView.vue` + `SetupStepIdentity.vue` on the web PWA.
/// Broker calls used: `config()`, `saveConfig(paName:)`, `getSoul()`, `putSoul(text:)`.
struct AssistantSettingsView: View {
    let broker: BrokerSession

    @State private var paName = ""
    @State private var soul = ""
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
        .navigationTitle("Assistant")
        .navigationBarTitleDisplayMode(.inline)
        .tint(Theme.teal)
        .task { await load() }
    }

    private var form: some View {
        Form {
            Section("Identity") {
                TextField("PA name", text: $paName)
                    .autocorrectionDisabled()
            }

            Section {
                TextEditor(text: $soul)
                    .font(.system(.body, design: .monospaced))
                    .frame(minHeight: 280)
            } header: {
                Text("soul.md")
            } footer: {
                Text("Personality, instructions, and persistent context prepended to every session.")
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
        async let configResult = broker.config()
        async let soulResult = broker.getSoul()
        let (cfg, soulText) = await (configResult, soulResult)
        paName = cfg?.paName ?? ""
        soul = soulText
        error = nil
    }

    private func save() async {
        saving = true
        saved = false
        error = nil
        defer { saving = false }

        await broker.saveConfig(paName: paName)
        let ok = await broker.putSoul(text: soul)

        if ok {
            saved = true
            // Clear the "Saved" badge after 2 seconds.
            try? await Task.sleep(for: .seconds(2))
            saved = false
        } else {
            error = "Couldn't save soul.md — check connection and try again"
        }
    }
}
