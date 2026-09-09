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
    @State private var pendingCreates: [String: Date] = [:]
    @State private var pendingCloses: Set<String> = []

    var body: some View {
        VStack(spacing: 0) {
            tabStrip
            Divider()
            ZStack {
                if !activeId.isEmpty {
                    let terminalId = activeId
                    TerminalPane(broker: broker, session: session,
                                 kind: "scratch", terminalId: terminalId,
                                 onExit: { onPaneExit(terminalId) })
                        // Remount on tab switch is cheap now: each tab's live terminal is
                        // cached in BrokerSession, so this reuses the warm host (no reconnect).
                        .id("\(session.id):\(terminalId)")
                } else if loaded {
                    emptyState
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .task(id: session.id) {
            while !Task.isCancelled {
                await refresh()
                do {
                    try await Task.sleep(nanoseconds: 3_000_000_000)
                } catch {
                    break
                }
            }
        }
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
                    .background(activeId == id ? Theme.teal.opacity(0.18) : Color.smTertiaryFill,
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
        pendingCreates[id] = Date()
        tabs.append(id)
        activeId = id
    }

    private func closeTab(_ id: String) {
        let idx = tabs.firstIndex(of: id)
        pendingCreates.removeValue(forKey: id)
        pendingCloses.insert(id)
        tabs.removeAll { $0 == id }
        pickActiveAfterRemoval(idx ?? 0)
        Task {
            do {
                try await broker.api.closeTerminal(session: session.id, terminal: id)
            } catch {
                pendingCloses.remove(id)
            }
            await refresh()
        }
    }

    /// Shell exited (tmux already gone): drop the tab, no close call needed.
    private func onPaneExit(_ id: String) {
        let idx = tabs.firstIndex(of: id)
        pendingCreates.removeValue(forKey: id)
        tabs.removeAll { $0 == id }
        pickActiveAfterRemoval(idx ?? 0)
    }

    private func pickActiveAfterRemoval(_ removedIdx: Int) {
        if tabs.contains(activeId) { return }
        let next = tabs[safe: removedIdx] ?? tabs[safe: removedIdx - 1] ?? tabs.first
        activeId = next ?? ""
    }

    private func refresh() async {
        do {
            let remoteIds = try await broker.api.listTerminals(session: session.id).map { $0.id }
            let remote = Set(remoteIds)
            let now = Date()

            for id in remoteIds { pendingCreates.removeValue(forKey: id) }
            pendingCreates = pendingCreates.filter { now.timeIntervalSince($0.value) < 15 }
            pendingCloses = Set(pendingCloses.filter { remote.contains($0) })

            let pendingLocal = tabs.filter { id in
                !remote.contains(id) && !pendingCloses.contains(id) && pendingCreates[id] != nil
            }
            tabs = (remoteIds.filter { !pendingCloses.contains($0) } + pendingLocal)
                .reduce(into: [String]()) { result, id in
                    if !result.contains(id) { result.append(id) }
                }

            if tabs.isEmpty && !loaded {
                addTab()                              // first open → one usable terminal
            } else if !tabs.contains(activeId) {
                activeId = tabs.first ?? ""
            }
        } catch {
            if tabs.isEmpty && !loaded { addTab() }
        }
        loaded = true
    }
}

private extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
