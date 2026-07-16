import SwiftUI
import Shared

/// Settings hub — mirrors the web PWA SettingsIndexView with NavigationLink rows
/// for each sub-screen, plus an inline Appearance picker.
struct SettingsView: View {
    let broker: BrokerSession
    @AppStorage("appearance") private var appearance = "system"

    var body: some View {
        List {
            Section {
                NavigationLink {
                    PersonalAssistantsView(broker: broker)
                } label: {
                    SettingsRow(
                        symbol: "person.2",
                        title: "Personal assistants",
                        subtitle: "Optional persistent orchestrators"
                    )
                }

                NavigationLink {
                    AssistantSettingsView(broker: broker)
                } label: {
                    SettingsRow(
                        symbol: "person.crop.circle",
                        title: "PA identity",
                        subtitle: "Shared soul.md for personal assistants"
                    )
                }

                NavigationLink {
                    AgentSettingsView(broker: broker)
                } label: {
                    SettingsRow(
                        symbol: "terminal",
                        title: "Agents",
                        subtitle: "CLI authorization and API key fallback"
                    )
                }

                NavigationLink {
                    CuratorSettingsView(broker: broker)
                } label: {
                    SettingsRow(
                        symbol: "moon.stars",
                        title: "Curator",
                        subtitle: "Nightly knowledge curation schedule"
                    )
                }

                NavigationLink {
                    VoiceSettingsView(broker: broker)
                } label: {
                    SettingsRow(
                        symbol: "mic",
                        title: "Voice",
                        subtitle: "Dictation cleanup model"
                    )
                }

                NavigationLink {
                    EditorSettingsScreen(broker: broker)
                } label: {
                    SettingsRow(
                        symbol: "doc.text",
                        title: "Editor",
                        subtitle: "Font, wrap, and language servers"
                    )
                }

                NavigationLink {
                    GitHostingSettingsView(broker: broker)
                } label: {
                    SettingsRow(
                        symbol: "arrow.triangle.branch",
                        title: "Git hosting",
                        subtitle: "GitHub & GitLab connections"
                    )
                }

                NavigationLink {
                    SystemSettingsView(broker: broker)
                } label: {
                    SettingsRow(
                        symbol: "gearshape.2",
                        title: "System",
                        subtitle: "Broker restart and status"
                    )
                }
            }

            Section("Appearance") {
                Picker("Theme", selection: $appearance) {
                    Text("System").tag("system")
                    Text("Light").tag("light")
                    Text("Dark").tag("dark")
                }
                .pickerStyle(.segmented)
            }
        }
        .navigationTitle("Settings")
        .tint(Theme.teal)
    }
}

// MARK: - Row helper

private struct SettingsRow: View {
    let symbol: String
    let title: String
    let subtitle: String

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.body)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: symbol)
                .foregroundStyle(Theme.teal)
        }
    }
}
