import SwiftUI
import Shared

/// Model / reasoning-level switcher sheet.
struct OptionSwitchSheet: View {
    enum Kind { case model, reasoning }
    let title: String
    let broker: BrokerSession
    let session: SessionInfo
    let kind: Kind
    @Environment(\.dismiss) private var dismiss
    @State private var options: [Opt] = []
    @State private var current: String?
    @State private var loading = true

    struct Opt: Identifiable { let id: String; let label: String }

    var body: some View {
        NavigationStack {
            List {
                if loading { HStack { Spacer(); ProgressView(); Spacer() } }
                ForEach(options) { o in
                    Button {
                        if kind == .model { broker.switchModel(session.id, o.id) }
                        else { broker.switchReasoning(session.id, o.id) }
                        dismiss()
                    } label: {
                        HStack {
                            Text(o.label)
                            Spacer()
                            if o.id == current { Image(systemName: "checkmark").foregroundStyle(Theme.teal) }
                        }
                    }
                    .foregroundStyle(.primary)
                }
            }
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .task { await load() }
        }
        .tint(Theme.teal)
        .presentationDetents([.medium, .large])
    }

    private func load() async {
        switch kind {
        case .model:
            if let r = await broker.models(session.id) {
                options = r.models.map { Opt(id: $0.id, label: $0.displayName) }
                current = r.current
            }
        case .reasoning:
            if let r = await broker.reasoning(session.id) {
                options = r.levels.map { Opt(id: $0.id, label: $0.id.capitalized) }
                current = r.current
            }
        }
        loading = false
    }
}
