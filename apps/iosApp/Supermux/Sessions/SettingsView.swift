import SwiftUI
import Shared

/// Settings — assistant name, configured-agent status, Curator schedule, theme.
/// (Mirrors the web settings that the shared BrokerApi already exposes.)
struct SettingsView: View {
    let broker: BrokerSession
    @AppStorage("appearance") private var appearance = "system"

    @State private var config: AppConfigDto?
    @State private var paName = ""
    @State private var curatorEnabled = false
    @State private var curatorHour = 1
    @State private var curatorMinute = 0
    @State private var nextRun: String?

    var body: some View {
        Form {
            Section("Assistant") {
                TextField("Assistant name", text: $paName)
                    .autocorrectionDisabled()
                    .onSubmit { broker.setPAName(paName) }
                if let url = config?.webPublicUrl, !url.isEmpty {
                    LabeledContent("Broker", value: url)
                }
            }

            Section("Agents") {
                agentRow("Claude", ok: config?.claudeConfigured ?? false)
                agentRow("Codex", ok: config?.codexConfigured ?? false)
                agentRow("Cursor", ok: config?.cursorConfigured ?? false)
                agentRow("Telegram", ok: config?.telegramConfigured ?? false)
            }

            Section("Curator") {
                Toggle("Daily knowledge digest", isOn: $curatorEnabled)
                    .onChange(of: curatorEnabled) { _, v in
                        broker.saveCurator(enabled: v, hour: curatorHour, minute: curatorMinute)
                    }
                if curatorEnabled {
                    DatePicker("Time", selection: timeBinding, displayedComponents: .hourAndMinute)
                    if let n = nextRun { LabeledContent("Next run", value: n) }
                }
                Button("Run now") { broker.runCuratorNow() }
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
        .task { await load() }
    }

    private var timeBinding: Binding<Date> {
        Binding(
            get: { Calendar.current.date(from: DateComponents(hour: curatorHour, minute: curatorMinute)) ?? Date() },
            set: { d in
                let c = Calendar.current.dateComponents([.hour, .minute], from: d)
                curatorHour = c.hour ?? 1
                curatorMinute = c.minute ?? 0
                broker.saveCurator(enabled: curatorEnabled, hour: curatorHour, minute: curatorMinute)
            }
        )
    }

    private func agentRow(_ name: String, ok: Bool) -> some View {
        HStack {
            Text(name)
            Spacer()
            Image(systemName: ok ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(ok ? Theme.teal : .secondary)
        }
    }

    private func load() async {
        config = await broker.config()
        paName = config?.paName ?? ""
        if let c = await broker.curatorSettings() {
            curatorEnabled = c.config.enabled
            curatorHour = Int(c.config.hour)
            curatorMinute = Int(c.config.minute)
            nextRun = c.nextRun
        }
    }
}
