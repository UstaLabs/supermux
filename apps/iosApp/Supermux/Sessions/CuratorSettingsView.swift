import SwiftUI
import Shared

/// Curator sub-screen — daily knowledge digest schedule.
/// Content moved verbatim from the old inline SettingsView Curator section.
struct CuratorSettingsView: View {
    let broker: BrokerSession

    @State private var curatorEnabled = false
    @State private var curatorHour = 1
    @State private var curatorMinute = 0
    @State private var nextRun: String?

    var body: some View {
        Form {
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
        }
        .navigationTitle("Curator")
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

    private func load() async {
        if let c = await broker.curatorSettings() {
            curatorEnabled = c.config.enabled
            curatorHour = Int(c.config.hour)
            curatorMinute = Int(c.config.minute)
            nextRun = c.nextRun
        }
    }
}
