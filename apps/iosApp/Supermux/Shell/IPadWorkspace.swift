import SwiftUI
import Shared

/// Regular-width iPad workspace — the PWA's wide multi-pane layout, native:
/// Sessions sidebar │ Chat │ (Editor over Terminal) │ Display, every divider drag-resizable
/// via `ResizableSplit`. Which work panes are visible is driven by `layout`'s open flags;
/// chat is always present. `RootView` swaps to `ChatView`'s tab bar at compact width.
struct IPadWorkspace: View {
    let broker: BrokerSession
    @Binding var selected: String?
    @Binding var route: RootView.NavRoute?
    @Bindable var layout: WorkspaceLayoutModel

    // Session-action state shared with ChatPane (slash /rename, /kill) + banner.
    @State private var showRename = false
    @State private var renameText = ""
    @State private var showKillConfirm = false
    @State private var banner: String?

    private var session: SessionInfo? { broker.sessions.first { $0.id == selected } }

    var body: some View {
        HStack(spacing: 0) {
            SessionsListView(broker: broker, selected: $selected,
                             onNewSession: { route = .newSession },
                             onArchived: { route = .archived })
                .frame(width: CGFloat(layout.sidebarWidth))
            Divider()
            detail.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .workspaceShortcuts(layout: layout) { route = .newSession }
        .navigationTitle(session?.name ?? "supermux")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { workspaceToolbar }
        .alert("Rename session", isPresented: $showRename) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) {}
            Button("Rename") { if let s = session { broker.rename(s.id, to: renameText) } }
        }
        .confirmationDialog("Kill \u{201C}\(session?.name ?? "")\u{201D}?", isPresented: $showKillConfirm, titleVisibility: .visible) {
            Button("Kill session", role: .destructive) { if let s = session { broker.kill(s.id) } }
            Button("Cancel", role: .cancel) {}
        }
        .onAppear(perform: applyOpenPanesEnv)
    }

    @ViewBuilder private var detail: some View {
        if let s = session {
            VStack(spacing: 0) {
                if let banner {
                    Text(banner).font(.footnote).padding(8)
                        .frame(maxWidth: .infinity).background(.thinMaterial)
                }
                content(s)
            }
        } else {
            ContentUnavailableView("Pick a session", systemImage: "bubble.left.and.bubble.right")
        }
    }

    @ViewBuilder private func content(_ s: SessionInfo) -> some View {
        let hasWork = layout.editorOpen || layout.terminalOpen || layout.displayOpen
        if layout.chatOpen && hasWork {
            ResizableSplit(axis: .horizontal, pct: $layout.chatPct, range: 20...80) {
                chat(s)
            } second: {
                rightArea(s)
            }
        } else if layout.chatOpen {
            chat(s)
        } else {
            rightArea(s)   // invariant: chat is hidden only while hasWork, so rightArea is non-empty
        }
    }

    private func chat(_ s: SessionInfo) -> some View {
        ChatPane(broker: broker, session: s,
                 showRename: $showRename, renameText: $renameText,
                 showKillConfirm: $showKillConfirm, banner: $banner)
    }

    @ViewBuilder private func rightArea(_ s: SessionInfo) -> some View {
        if layout.displayOpen && (layout.editorOpen || layout.terminalOpen) {
            ResizableSplit(axis: .horizontal, pct: $layout.workDisplayPct, range: 25...75) {
                workColumn(s)
            } second: {
                DisplayPane(broker: broker, session: s)
            }
        } else if layout.displayOpen {
            DisplayPane(broker: broker, session: s)
        } else {
            workColumn(s)
        }
    }

    @ViewBuilder private func workColumn(_ s: SessionInfo) -> some View {
        if layout.editorOpen && layout.terminalOpen {
            ResizableSplit(axis: .vertical, pct: $layout.editorTermPct, range: 20...80) {
                EditorPane(broker: broker, session: s)
            } second: {
                TerminalPanel(broker: broker, session: s)
            }
        } else if layout.editorOpen {
            EditorPane(broker: broker, session: s)
        } else if layout.terminalOpen {
            TerminalPanel(broker: broker, session: s)
        }
    }

    @ToolbarContentBuilder private var workspaceToolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            if let s = session { AgentLogo(agent: s.agent, size: 20) }
        }
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Button { route = .personalAssistants } label: { Label("Assistants", systemImage: "person.2") }
                Button { route = .usage } label: { Label("Usage", systemImage: "chart.bar") }
                Button { route = .devices } label: { Label("Devices", systemImage: "ipad.and.iphone") }
                Button { route = .proxies } label: { Label("Proxies", systemImage: "network") }
                Button { route = .displays } label: { Label("Displays", systemImage: "display") }
                Button { route = .settings } label: { Label("Settings", systemImage: "gearshape") }
            } label: { Image(systemName: "ellipsis.circle") }
        }
    }

    /// Headless screenshot hook: SM_IPAD_OPEN_PANES=editor,terminal,display opens those panes on launch.
    private func applyOpenPanesEnv() {
        guard let raw = ProcessInfo.processInfo.environment["SM_IPAD_OPEN_PANES"] else { return }
        let panes = Set(raw.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) })
        layout.editorOpen = panes.contains("editor")
        layout.terminalOpen = panes.contains("terminal")
        layout.displayOpen = panes.contains("display")
    }
}
