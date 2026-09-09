import SwiftUI
import Shared

/// Model / reasoning-level switcher: a detented sheet on iOS, a pill-anchored popover on the Mac.
/// Modern menu chrome (search + hover rows) — stock List looked dated inside the popover.
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
    @State private var search = ""
    @FocusState private var searchFocused: Bool

    struct Opt: Identifiable { let id: String; let label: String }

    private var filtered: [Opt] {
        let q = search.trimmingCharacters(in: .whitespaces).lowercased()
        return q.isEmpty ? options : options.filter {
            $0.label.lowercased().contains(q) || $0.id.lowercased().contains(q)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            #if os(iOS)
            HStack {
                Text(title).font(.headline)
                Spacer()
                Button("Done") { dismiss() }.fontWeight(.semibold)
            }
            .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 8)
            #endif

            VStack(alignment: .leading, spacing: 10) {
                MenuSectionLabel(title.uppercased(), trailing: loading
                    ? AnyView(ProgressView().controlSize(.mini))
                    : nil)

                if kind == .model && options.count > 6 {
                    MenuSearchField(text: $search, placeholder: "Search models…", focused: $searchFocused)
                }

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        if loading {
                            HStack(spacing: 8) {
                                ProgressView().controlSize(.small)
                                Text("Loading…").font(.caption).foregroundStyle(.secondary)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 24)
                        } else if filtered.isEmpty {
                            Text(options.isEmpty
                                 ? (kind == .model ? "No models available" : "No levels available")
                                 : "No match")
                                .font(.caption).foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 24)
                        } else {
                            ForEach(filtered) { o in
                                MenuOptionRow(
                                    title: o.label,
                                    systemImage: kind == .model ? "cpu" : "brain",
                                    selected: o.id == current
                                ) {
                                    if kind == .model { broker.switchModel(session.id, o.id) }
                                    else { broker.switchReasoning(session.id, o.id) }
                                    dismiss()
                                }
                            }
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
            .padding(12)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color.smBackground)
        .tint(Theme.teal)
        .smPresentationDetents([.medium, .large])
        .smMacFixedFrame(width: 280, height: 360)
        .task {
            await load()
            #if os(macOS)
            if kind == .model && options.count > 6 {
                DispatchQueue.main.async { searchFocused = true }
            }
            #endif
        }
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
