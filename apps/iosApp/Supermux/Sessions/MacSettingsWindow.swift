#if os(macOS)
import SwiftUI
import Shared

/// The native macOS Settings window (⌘, / Supermux ▸ Settings…): the classic icon-tab
/// strip over grouped-form panes — the Settings-scene twin of the iOS sheet's
/// NavigationLink hub. Owns its own `BrokerSession` (the `SessionWindow` "every window is
/// an independent broker client" model), created on open and stopped on close. Each tab
/// hosts its pane in a `NavigationStack` so the panes' drill-ins (Add a Git account,
/// pickers) keep working outside the iOS sheet.
///
/// `SM_SETTINGS_TAB=<general|assistant|agents|curator|voice|editor|git|system>` selects
/// the initial tab — the headless-screenshot hook for this window.
struct MacSettingsWindow: View {
    @State private var fleet: Fleet
    @State private var selected: Tab

    enum Tab: String {
        case general, assistant, agents, curator, voice, editor, git, system
    }

    init() {
        let want = ProcessInfo.processInfo.environment["SM_SETTINGS_TAB"]
        _fleet = State(initialValue: Fleet())
        _selected = State(initialValue: want.flatMap(Tab.init(rawValue:)) ?? .general)
    }

    var body: some View {
        Group {
            if let broker = fleet.activeBroker {
                VStack(spacing: 0) {
                    HostScopePicker(hosts: fleet.hostViews, selected: fleet.activeRecordId) {
                        fleet.setActive($0)
                    }
                    TabView(selection: $selected) {
                        pane { GeneralSettingsPane() }
                            .tabItem { Label("General", systemImage: "gearshape") }
                            .tag(Tab.general)
                        pane { AssistantSettingsView(broker: broker) }
                            .tabItem { Label("Assistant", systemImage: "person.crop.circle") }
                            .tag(Tab.assistant)
                        pane { AgentSettingsView(broker: broker) }
                            .tabItem { Label("Agents", systemImage: "terminal") }
                            .tag(Tab.agents)
                        pane { CuratorSettingsView(broker: broker) }
                            .tabItem { Label("Curator", systemImage: "moon.stars") }
                            .tag(Tab.curator)
                        pane { VoiceSettingsView(broker: broker) }
                            .tabItem { Label("Voice", systemImage: "mic") }
                            .tag(Tab.voice)
                        pane { EditorSettingsScreen(broker: broker) }
                            .tabItem { Label("Editor", systemImage: "doc.text") }
                            .tag(Tab.editor)
                        pane { GitHostingSettingsView(broker: broker) }
                            .tabItem { Label("Git Hosting", systemImage: "arrow.triangle.branch") }
                            .tag(Tab.git)
                        pane { SystemSettingsView(broker: broker) }
                            .tabItem { Label("System", systemImage: "gearshape.2") }
                            .tag(Tab.system)
                    }
                    .id(broker.baseURL)
                }
            } else {
                ContentUnavailableView("Not paired",
                                       systemImage: "link.badge.plus",
                                       description: Text("Pair with your broker in the main window first."))
                    .frame(width: 480, height: 220)
            }
        }
        .tint(Theme.teal)
        .task { fleet.start() }
        // The Settings scene keeps its view alive across window closes only sometimes —
        // stop the broker either way; reopening re-runs `.task`.
        .onDisappear {
            fleet.stop()
        }
    }

    /// One settings pane: its own NavigationStack (drill-ins), the System-Settings grouped
    /// form look, and a consistent window size across tabs.
    private func pane<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        NavigationStack { content() }
            .formStyle(.grouped)
            .frame(width: 720, height: 540)
    }
}

/// The General tab — appearance lives here on the Mac (the iOS sheet keeps it inline).
private struct GeneralSettingsPane: View {
    @AppStorage("appearance") private var appearance = "system"

    var body: some View {
        Form {
            Picker("Appearance", selection: $appearance) {
                Text("System").tag("system")
                Text("Light").tag("light")
                Text("Dark").tag("dark")
            }
            .pickerStyle(.segmented)
        }
    }
}
#endif
