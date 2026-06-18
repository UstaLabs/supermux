import SwiftUI
import Shared

/// Per-session strip of scratch terminal tabs. The tab set is rebuilt from the
/// broker (live tmux) on open so both shells AND tabs survive relaunch. Only the
/// active tab is mounted, so only it connects; background shells keep running.
struct TerminalPanel: View {
    let broker: BrokerSession
    let session: SessionInfo

    @State private var tabs: [String] = []        // terminal ids
    @State private var activeId: String = ""
    @State private var loaded = false

    var body: some View {
        VStack(spacing: 0) {
            tabStrip
            Divider()
            ZStack {
                if !activeId.isEmpty {
                    TerminalPane(broker: broker, session: session,
                                 kind: "scratch", terminalId: activeId,
                                 onExit: { onPaneExit(activeId) })
                        .id("\(session.id):\(activeId)")   // remount on tab switch → reconnect
                } else if loaded {
                    emptyState
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .task(id: session.id) { await refresh() }
    }

    private var tabStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(Array(tabs.enumerated()), id: \.element) { idx, id in
                    HStack(spacing: 6) {
                        Text("Terminal \(idx + 1)").font(.caption.weight(.medium))
                        Button { closeTab(id) } label: {
                            Image(systemName: "xmark").font(.system(size: 9, weight: .bold))
                        }.buttonStyle(.plain).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(activeId == id ? Theme.teal.opacity(0.18) : Color(.tertiarySystemFill),
                                in: Capsule())
                    .foregroundStyle(activeId == id ? Theme.teal : .secondary)
                    .contentShape(Capsule())
                    .onTapGesture { activeId = id }
                }
                Button { addTab() } label: {
                    Image(systemName: "plus").font(.system(size: 13, weight: .semibold))
                        .frame(width: 28, height: 28)
                }.buttonStyle(.plain).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "terminal").font(.system(size: 32)).foregroundStyle(.secondary)
            Button { addTab() } label: {
                Label("New terminal", systemImage: "plus")
            }.buttonStyle(.bordered).tint(Theme.teal)
        }
    }

    private func genId() -> String {
        String(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(16))
    }

    private func addTab() {
        let id = genId()
        tabs.append(id)
        activeId = id
    }

    private func closeTab(_ id: String) {
        let idx = tabs.firstIndex(of: id)
        tabs.removeAll { $0 == id }
        pickActiveAfterRemoval(idx ?? 0)
        Task { try? await broker.api.closeTerminal(session: session.name, terminal: id) }
    }

    /// Shell exited (tmux already gone): drop the tab, no close call needed.
    private func onPaneExit(_ id: String) {
        let idx = tabs.firstIndex(of: id)
        tabs.removeAll { $0 == id }
        pickActiveAfterRemoval(idx ?? 0)
    }

    private func pickActiveAfterRemoval(_ removedIdx: Int) {
        if tabs.contains(activeId) { return }
        let next = tabs[safe: removedIdx] ?? tabs[safe: removedIdx - 1] ?? tabs.first
        activeId = next ?? ""
    }

    private func refresh() async {
        let ids = ((try? await broker.api.listTerminals(session: session.name)) ?? []).map { $0.id }
        tabs = ids
        if tabs.isEmpty {
            addTab()                                  // first open → one usable terminal
        } else if !tabs.contains(activeId) {
            activeId = tabs[0]
        }
        loaded = true
    }
}

private extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
