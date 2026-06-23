import SwiftUI
import Shared

/// Regular-width iPad workspace — the PWA's wide multi-pane layout, native:
/// Sessions sidebar │ Chat │ (Editor over Terminal) │ Display, every divider drag-resizable
/// via `ResizableSplit`. Which work panes are visible is driven by `layout`'s open flags;
/// chat is always present. `RootView` swaps to `ChatView`'s tab bar at compact width.
///
/// The session header (name · branch/sync · links · pane toggles · Finish · ⋯) is a custom
/// bar at the top of the **detail** column only (`WorkspaceDetail`) — mirroring the PWA,
/// where the header spans the detail, not the sidebar. The `NavigationStack`'s own nav bar is
/// hidden for the workspace (the sidebar keeps its in-list "Archived" pull-bar, which is a
/// `safeAreaInset`, not the nav bar). Navigation to management pages is driven by the `route`
/// binding via `RootView`'s `.navigationDestination`, so it keeps working with the bar hidden.
struct IPadWorkspace: View {
    let broker: BrokerSession
    @Binding var selected: String?
    @Binding var route: RootView.NavRoute?
    @Bindable var layout: WorkspaceLayoutModel

    // Session-action state shared with ChatPane (slash /rename, /kill).
    @State private var showRename = false
    @State private var renameText = ""
    @State private var showKillConfirm = false
    // Git status / proxies / finish flow / result banner — one source of truth, the same
    // SessionChrome the compact ChatView uses. Optional: there may be no selected session.
    // It's the lifecycle owner; `WorkspaceDetail` receives the unwrapped value as `@Bindable`.
    @State private var chrome: SessionChrome?
    // Sidebar-width drag: width captured at gesture start so the cumulative translation
    // is applied once (no double-counting), mirroring `PaneDivider`.
    @State private var dragStartWidth: Double?
    // The SM_IPAD_OPEN_PANES / SM_IPAD_PRESS hooks mutate the SELECTED session's panes, but
    // `selected` is populated asynchronously (RootView's `.task(id: broker.synced)`), well after
    // onAppear. This one-shot guard defers the hooks until a session is selected, then runs them once.
    @State private var didApplyEnvHooks = false

    private var session: SessionInfo? { broker.sessions.first { $0.id == selected } }

    /// The id of the current session's newest running display stream, or nil. Drives the
    /// Display column's auto-open (PWA `SessionDisplayPanel` parity): nil→non-nil = a stream
    /// just went live. Reading `broker.runningDisplay` tracks `broker.displays` for onChange.
    private var liveDisplayId: String? { session.flatMap { broker.runningDisplay(for: $0.name)?.id } }

    var body: some View {
        HStack(spacing: 0) {
            sidebar
            detail.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .animation(.snappy(duration: 0.25), value: layout.sidebarCollapsed)
        .workspaceShortcuts(layout: layout, session: selected) { route = .newSession }
        // The session header lives in the detail column (see `WorkspaceDetail`), so the stack's
        // own nav bar is hidden — it would otherwise span both columns and double the chrome.
        .toolbar(.hidden, for: .navigationBar)
        .alert("Rename session", isPresented: $showRename) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) {}
            Button("Rename") { if let s = session { broker.rename(s.id, to: renameText) } }
        }
        .confirmationDialog("Kill \u{201C}\(session?.name ?? "")\u{201D}?", isPresented: $showKillConfirm, titleVisibility: .visible) {
            Button("Kill session", role: .destructive) { if let s = session { broker.kill(s.id) } }
            Button("Cancel", role: .cancel) {}
        }
        .onAppear { syncChrome(); applyEnvHooksIfReady() }
        // Keep the chrome pointed at the selected session (load git/proxies on switch). The env
        // hooks need a selected session, which arrives async after onAppear — apply them here on
        // the first selection too (the guard makes it one-shot, so a later switch won't re-fire).
        .onChange(of: selected) { _, _ in syncChrome(); applyEnvHooksIfReady() }
        // Auto-open the Display column on the no-stream → live edge for this session (PWA parity).
        // Gated to nil→non-nil so a manual ⌘D close isn't resurrected by a second/restarted
        // stream getting a new id while one was already live. Writes the CURRENT session's pane.
        .onChange(of: liveDisplayId) { old, id in
            guard old == nil, id != nil, let s = session else { return }
            var v = layout.panes(for: s.id)
            v.displayOpen = true
            layout.setPanes(v, for: s.id)
        }
    }

    /// Ensure `chrome` exists for the selected session and (re)load its git/proxy state.
    /// Reuses one chrome across switches; `load(for:)` is idempotent per session id.
    private func syncChrome() {
        guard let s = session else { return }
        if chrome == nil { chrome = SessionChrome(broker: broker, session: s) }
        chrome?.load(for: s)
    }

    /// The leading column: a 56-pt avatar rail when collapsed, else the full grouped
    /// list at `sidebarWidth` followed by a drag-resizable divider. ⌘B toggles `sidebarCollapsed`.
    @ViewBuilder private var sidebar: some View {
        if layout.sidebarCollapsed {
            SessionsRailView(broker: broker, selected: $selected,
                             onExpand: { layout.sidebarCollapsed = false },
                             onNewSession: { route = .newSession })
                .frame(width: WorkspaceLayoutModel.B.rail)
            Divider()
        } else {
            SessionsListView(broker: broker, selected: $selected,
                             onNewSession: { route = .newSession },
                             onArchived: { route = .archived })
                .frame(width: CGFloat(layout.sidebarWidth))
            sidebarDivider
        }
    }

    /// A 1pt visible rule with a ~24pt hit area; dragging adjusts `sidebarWidth`. Captures the
    /// width at gesture start so the cumulative `DragGesture.translation` applies once; the model's
    /// didSet clamps to 220...560, so no manual clamp here.
    private var sidebarDivider: some View {
        Rectangle()
            .fill(Color.secondary.opacity(0.25))
            .frame(width: 1)
            .hoverEffect(.highlight)
            .overlay {
                Color.clear
                    .frame(width: 24)
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 1)
                            .onChanged { g in
                                let start = dragStartWidth ?? layout.sidebarWidth
                                if dragStartWidth == nil { dragStartWidth = start }
                                layout.sidebarWidth = start + Double(g.translation.width)
                            }
                            .onEnded { _ in dragStartWidth = nil }
                    )
            }
    }

    @ViewBuilder private var detail: some View {
        // The header + finish dialogs need a non-nil chrome bound for two-way state, so the
        // detail content lives in `WorkspaceDetail` and is rendered only once chrome exists.
        if let s = session, let chrome {
            WorkspaceDetail(broker: broker, session: s, layout: layout, chrome: chrome, route: $route,
                            showRename: $showRename, renameText: $renameText,
                            showKillConfirm: $showKillConfirm)
        } else {
            ContentUnavailableView("Pick a session", systemImage: "bubble.left.and.bubble.right")
        }
    }

    /// Headless screenshot/verification hooks, applied once on launch in a fixed order:
    /// SM_IPAD_OPEN_PANES sets the selected session's pane flags, then SM_IPAD_PRESS applies ⌘
    /// commands on top. Both target the SELECTED session's panes (now per-session), so they no-op
    /// until a session exists; the one-shot `didApplyEnvHooks` guard makes them fire exactly once.
    ///
    /// ORCHESTRATOR: timing. `selected` is set asynchronously by RootView's `.task(id:
    /// broker.synced)` AFTER the broker syncs — long after this view's onAppear. So the hooks are
    /// driven from BOTH onAppear (covers the rare case a session is already selected) and the
    /// first `.onChange(of: selected)`; the guard ensures a later session switch never re-applies
    /// them. If a hook ever appears not to fire, check that `selected` actually became non-nil
    /// (e.g. SM_OPEN_SESSION matched a real session name) before suspecting the guard.
    private func applyEnvHooksIfReady() {
        guard !didApplyEnvHooks, let s = session else { return }
        didApplyEnvHooks = true
        applyOpenPanesEnv(for: s.id)
        applyPressEnv(for: s.id)
    }

    /// Headless screenshot hook: SM_IPAD_OPEN_PANES=editor,terminal,display opens those panes on
    /// launch for the selected session. Preserves chat-on + the never-empty invariant via `setPanes`.
    private func applyOpenPanesEnv(for sessionId: String) {
        guard let raw = ProcessInfo.processInfo.environment["SM_IPAD_OPEN_PANES"] else { return }
        let panes = Set(raw.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) })
        var v = layout.panes(for: sessionId)
        v.editorOpen = panes.contains("editor")
        v.terminalOpen = panes.contains("terminal")
        v.displayOpen = panes.contains("display")
        layout.setPanes(v, for: sessionId)
    }

    /// Headless verification hook: SM_IPAD_PRESS=b,e applies those ⌘ commands on launch — the
    /// SAME WorkspaceCommand.apply the keyboard shortcuts trigger — so the toggle + render path
    /// (and the rail, via "b") is screenshot-verifiable without injecting hardware key events.
    /// Pane toggles act on the selected session; "b" is the global sidebar; "n" routes.
    private func applyPressEnv(for sessionId: String) {
        guard let raw = ProcessInfo.processInfo.environment["SM_IPAD_PRESS"] else { return }
        let map: [String: WorkspaceCommand] = [
            "b": .toggleSidebar, "l": .toggleChat, "t": .toggleTerminal,
            "e": .toggleEditor, "d": .toggleDisplay,
        ]
        for k in raw.split(separator: ",").map({ $0.trimmingCharacters(in: .whitespaces) }) {
            if k == "n" { route = .newSession } else if let cmd = map[k] { cmd.apply(to: layout, session: sessionId) }
        }
    }
}

/// The detail column for a selected session: the PWA-parity session header (name · branch/sync ·
/// links · pane toggles · Finish · ⋯) over the optional git/finish banner over the multi-pane
/// content. Holds the finish-flow dialogs (bound to the non-optional `chrome`).
private struct WorkspaceDetail: View {
    let broker: BrokerSession
    let session: SessionInfo
    @Bindable var layout: WorkspaceLayoutModel
    @Bindable var chrome: SessionChrome
    @Binding var route: RootView.NavRoute?
    @Binding var showRename: Bool
    @Binding var renameText: String
    @Binding var showKillConfirm: Bool
    @State private var finishSheet = false

    var body: some View {
        VStack(spacing: 0) {
            sessionHeader
            if let banner = chrome.banner {
                Text(banner).font(.footnote).padding(8)
                    .frame(maxWidth: .infinity).background(.thinMaterial)
                Divider()
            }
            content
        }
        // The Finish bottom sheet (readiness → action → live job → recovery), shared chrome.
        .sheet(isPresented: $finishSheet) { FinishSheet(chrome: chrome) }
    }

    // MARK: - Session header bar

    /// The detail-only header (PWA `ChatView.vue` parity): AgentLogo + 2-line title
    /// (name · branch/sync) | links | pane toggles | Finish | ⋯ menu. A single ~52pt row on
    /// a `.bar` material with a bottom divider, spanning the detail column only.
    private var sessionHeader: some View {
        HStack(spacing: 12) {
            AgentLogo(agent: session.agent, size: 30)
            VStack(alignment: .leading, spacing: 1) {
                Text(session.name).font(.headline).lineLimit(1)
                Text(chrome.navSubtitle)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 8)
            sessionLinksMenu
            // Chat ⇄ Native (agent terminal) switch for the main column — only for agents that
            // have a native view (claude), mirroring the PWA's `v-if="isClaude"`, and only while
            // the chat column is actually shown (the native view IS the chat column's other mode).
            if session.agent == "claude" && panes.chatOpen {
                AgentViewToggle(layout: layout, sessionId: session.id)
            }
            PaneToggleCluster(layout: layout, sessionId: session.id)
            if session.session_branch != nil {
                Button { finishSheet = true } label: {
                    Label("Finish", systemImage: "arrow.triangle.merge")
                        .font(.subheadline.weight(.semibold))
                        .overlay(alignment: .topTrailing) { finishBadge }
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
                .controlSize(.small)
                .hoverEffect(.highlight)
            }
            overflowMenu
        }
        .padding(.horizontal, 16)
        .frame(height: 52)
        .background(.bar)
        .overlay(alignment: .bottom) { Divider() }
    }

    /// Unacked-finish dot on the header Finish button: red on failure, teal on success;
    /// hidden while running or once acknowledged (badge is offset off the prominent button).
    @ViewBuilder private var finishBadge: some View {
        if chrome.isUnacked {
            Circle()
                .fill(chrome.currentJob?.status == "failed" ? Color.red : Color.white)
                .frame(width: 8, height: 8)
                .offset(x: 4, y: -4)
        }
    }

    /// Session proxy links — a `link` menu shown only when the session has proxies
    /// (kept separate from the overflow ⋯ so the links are one tap away, like the PWA).
    @ViewBuilder private var sessionLinksMenu: some View {
        if !chrome.sessionLinks.isEmpty {
            Menu {
                ForEach(chrome.sessionLinks, id: \.domain) { p in
                    if let u = chrome.linkURL(p) { Link(destination: u) { Label(proxyDisplayUrl(proxy: p), systemImage: "link") } }
                }
            } label: {
                Image(systemName: "link").font(.body)
            }
            .hoverEffect(.highlight)
        }
    }

    /// The overflow ⋯ menu: git ops (when the session is a repo) + the management pages that
    /// used to live in the stack's toolbar (now route-driven, since the nav bar is hidden).
    @ViewBuilder private var overflowMenu: some View {
        Menu {
            if let g = chrome.git, g.isRepo {
                Section("Git") {
                    Button { chrome.fetch() } label: { Label("Fetch", systemImage: "arrow.down") }
                    Button { chrome.push() } label: { Label("Push", systemImage: "arrow.up") }
                    Button { chrome.pull() } label: { Label("Pull", systemImage: "arrow.down.to.line") }
                    if g.upstream == nil {
                        Button { chrome.publish() } label: { Label("Publish", systemImage: "arrow.up.to.line") }
                    }
                }
            }
            Section {
                Button { route = .personalAssistants } label: { Label("Assistants", systemImage: "person.2") }
                Button { route = .usage } label: { Label("Usage", systemImage: "chart.bar") }
                Button { route = .devices } label: { Label("Devices", systemImage: "ipad.and.iphone") }
                Button { route = .proxies } label: { Label("Proxies", systemImage: "network") }
                Button { route = .displays } label: { Label("Displays", systemImage: "display") }
                Button { route = .settings } label: { Label("Settings", systemImage: "gearshape") }
            }
        } label: {
            Image(systemName: "ellipsis.circle").font(.body)
        }
        .hoverEffect(.highlight)
    }

    // MARK: - Multi-pane content

    /// This session's open/closed pane state (PWA `panelsFor(sessionId)`). Split ratios stay
    /// global on `layout`; only which panes show is per-session.
    private var panes: PaneVisibility { layout.panes(for: session.id) }

    @ViewBuilder private var content: some View {
        let p = panes
        let hasWork = p.editorOpen || p.terminalOpen || p.displayOpen
        if p.chatOpen && hasWork {
            ResizableSplit(axis: .horizontal, pct: $layout.chatPct, range: 20...80) {
                chat
            } second: {
                rightArea
            }
        } else if p.chatOpen {
            chat
        } else {
            rightArea   // invariant: chat is hidden only while hasWork, so rightArea is non-empty
        }
    }

    /// The main column: the chat transcript+composer, or — when the user flips the header's
    /// Chat⇄Native switch (claude only) — the agent's native terminal, the SAME `TerminalPane`
    /// the iPhone "Native" tab hosts. `onExit` (agent process ended) falls back to chat, matching
    /// the PWA's `@exit="mainView = 'chat'"`.
    @ViewBuilder private var chat: some View {
        if layout.nativeView(for: session.id) && session.agent == "claude" {
            TerminalPane(broker: broker, session: session, kind: "agent", terminalId: nil,
                         onExit: { layout.setNativeView(false, for: session.id) })
        } else {
            ChatPane(broker: broker, session: session,
                     showRename: $showRename, renameText: $renameText,
                     showKillConfirm: $showKillConfirm, banner: $chrome.banner)
        }
    }

    @ViewBuilder private var rightArea: some View {
        let p = panes
        if p.displayOpen && (p.editorOpen || p.terminalOpen) {
            ResizableSplit(axis: .horizontal, pct: $layout.workDisplayPct, range: 25...75) {
                workColumn
            } second: {
                DisplayPane(broker: broker, session: session)
            }
        } else if p.displayOpen {
            DisplayPane(broker: broker, session: session)
        } else {
            workColumn
        }
    }

    @ViewBuilder private var workColumn: some View {
        let p = panes
        if p.editorOpen && p.terminalOpen {
            ResizableSplit(axis: .vertical, pct: $layout.editorTermPct, range: 20...80) {
                EditorPane(broker: broker, session: session)
            } second: {
                TerminalPanel(broker: broker, session: session)
            }
        } else if p.editorOpen {
            EditorPane(broker: broker, session: session)
        } else if p.terminalOpen {
            TerminalPanel(broker: broker, session: session)
        }
    }
}
