import SwiftUI
import Shared
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// The iPhone (compact) session screen: a 5-tab pane switcher (Chat / Native / Terminal /
/// Editor / Display) with the toolbar, finish flow, and git actions. The Chat tab renders
/// the shared `ChatPane` (transcript + composer with voice dictation) — the SAME component
/// the iPad multi-pane layout uses, so there's exactly one composer across form factors.
struct ChatView: View {
    let broker: BrokerSession
    let session: SessionInfo
    @State private var proxies: [ProxyDto] = []
    @State private var showRename = false
    @State private var renameText = ""
    @State private var showKillConfirm = false
    @State private var git: GitRemoteStatus?
    // Transient git-action banner. Owned here (git actions live in the toolbar menu) and
    // passed to ChatPane, which renders it above the composer.
    @State private var banner: String?
    // Finish flow (readiness → action → live job → recovery) lives on SessionChrome, shared
    // with the iPad header; the FinishSheet reads it. Created lazily on first appearance.
    @State private var chrome: SessionChrome?
    @State private var finishSheet = false
    @State private var loadedSessionId: String?

    enum PaneTab: Hashable { case chat, native, terminal, editor, display }
    @State private var tab: PaneTab = .chat
    private var agentViewAvailable: Bool { (session.agent ?? "claude") == "claude" }
    /// Asset-catalog logo for the session's agent — used as the Native tab's icon so it
    /// shows the relevant brand (Claude/Codex/…), not a generic glyph. nil → fallback.
    private var agentAssetName: String? {
        switch session.agent.lowercased() {
        case "claude": return "claude"
        case "codex": return "codex"
        case "cursor": return "cursor"
        case "opencode": return "opencode"
        default: return nil
        }
    }
    /// The agent logo, pre-rendered to a tab-bar-sized padded image — the raw vector asset
    /// renders far too large as a tab icon, and SwiftUI frame modifiers don't constrain it
    /// in a Tab label. A UIImage's point size IS respected. Cached per agent.
    private static var tabIconCache: [String: Image] = [:]
    private var agentTabIcon: Image {
        guard let asset = agentAssetName else { return Image(systemName: "cube.transparent") }
        if let cached = Self.tabIconCache[asset] { return cached }
        #if canImport(UIKit)
        guard let ui = UIImage(named: asset) else { return Image(systemName: "cube.transparent") }
        let canvas = CGSize(width: 26, height: 26)
        let inset: CGFloat = 4
        let rendered = UIGraphicsImageRenderer(size: canvas).image { _ in
            ui.draw(in: CGRect(x: inset, y: inset, width: canvas.width - 2 * inset, height: canvas.height - 2 * inset))
        }
        let image = Image(platform: rendered.withRenderingMode(.alwaysOriginal))
        #else
        guard let base = NSImage(named: asset) else { return Image(systemName: "cube.transparent") }
        let canvas = CGSize(width: 26, height: 26)
        let inset: CGFloat = 4
        let rendered = NSImage(size: canvas, flipped: false) { _ in
            base.draw(in: CGRect(x: inset, y: inset, width: canvas.width - 2 * inset, height: canvas.height - 2 * inset))
            return true
        }
        rendered.isTemplate = false   // keep the brand colors (analog of .alwaysOriginal)
        let image = Image(platform: rendered)
        #endif
        Self.tabIconCache[asset] = image
        return image
    }

    private var sessionLinks: [ProxyDto] { proxies.filter { $0.sessionName == session.name } }

    /// (Re)load session-level state (git/branch, proxies, finish chrome). Runs on first open,
    /// reopen, and session switch — git status is retried so the branch reliably appears. The
    /// composer's own per-session state (draft, glossary, reasoning) is loaded by ChatPane.
    private func loadSession() {
        loadedSessionId = session.id
        tab = .chat
        // Keep the finish-flow chrome pointed at the current session (one instance, reused
        // across switches); `load(for:)` is idempotent per id and warms git/proxies for it.
        if chrome == nil { chrome = SessionChrome(broker: broker, session: session) }
        chrome?.load(for: session)
        git = nil
        Task {
            for _ in 0..<8 {
                if let g = await broker.gitStatus(session.id) { git = g; return }
                if Task.isCancelled { return }
                try? await Task.sleep(nanoseconds: 1_500_000_000)
            }
        }
        Task { proxies = (try? await broker.api.proxies()) ?? [] }
    }

    var body: some View {
        // Native iOS 26 TabView → the system draws the floating Liquid Glass bar and its
        // selection (no hand-rolled chrome).
        TabView(selection: $tab) {
            Tab("Chat", systemImage: "bubble.left", value: PaneTab.chat) { chatPane }
            if agentViewAvailable {
                Tab(value: PaneTab.native) {
                    nativePane
                } label: {
                    Label { Text("Native") } icon: { agentTabIcon }
                }
            }
            Tab("Terminal", systemImage: "terminal", value: PaneTab.terminal) {
                TerminalPanel(broker: broker, session: session)
            }
            Tab("Editor", systemImage: "chevron.left.forwardslash.chevron.right", value: PaneTab.editor) {
                EditorPane(broker: broker, session: session)
            }
            Tab("Display", systemImage: "display", value: PaneTab.display) {
                DisplayPane(broker: broker, session: session)
            }
        }
        // "Not responding" treatment for a dead agent (broker agent_state == "dead") — parity
        // with the web + Android dead-session banners (closes a previously iOS-only gap).
        .safeAreaInset(edge: .top, spacing: 0) {
            if broker.agentDead[session.id] == true {
                DeadSessionBanner()
            }
        }
        .navigationTitle(session.name)
        .navigationSubtitle(navSubtitle)
        .toolbar {
            ToolbarItem(placement: .smTopLeading) {
                AgentLogo(agent: session.agent, size: 20)
            }
            ToolbarItemGroup(placement: .smTopTrailing) {
                if session.session_branch != nil {
                    Button { finishSheet = true } label: {
                        Label("Finish", systemImage: "arrow.triangle.merge")
                            .overlay(alignment: .topTrailing) { finishBadge }
                    }
                    .tint(Theme.teal)
                }
                navMenu
            }
        }
        .toolbarTitleDisplayMode(.inline)
        // Load per-session state on EVERY appearance — `.task(id:)` doesn't re-fire when
        // re-opening the *same* session (id unchanged), which left git/branch unloaded.
        // onAppear covers first-open + reopen; onChange covers switching (reused view).
        .onAppear { if loadedSessionId != session.id { loadSession() } }
        .onChange(of: session.id) { _, _ in loadSession() }
        // A tapped file path in this session's transcript brings the Editor tab forward and
        // collapses the file-tree overlay — compact's tree is a full-screen overlay that would
        // otherwise cover the file you just opened (parity with Android revealFile's
        // `if (!expanded) treeVisible = false` and the editor's own openFile).
        .onChange(of: broker.editorFocus) { _, f in
            if let f, f.sessionId == session.id {
                tab = .editor
                withAnimation(.snappy(duration: 0.2)) {
                    broker.editorState(for: f.sessionId).treeVisible = false
                }
            }
        }
        // A path that couldn't be resolved (outside the project) surfaces as the git banner.
        .onChange(of: broker.editorOpenError) { _, msg in
            if let msg { showBanner(msg); broker.editorOpenError = nil }
        }
        .task(id: session.id) {
            if ProcessInfo.processInfo.environment["SM_OPEN_TERMINAL"] == "1" {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                tab = .terminal
            }
        }
        .task(id: session.id) {
            if ProcessInfo.processInfo.environment["SM_OPEN_NATIVE"] == "1" {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                tab = .native
            }
        }
        .task(id: session.id) {
            if ProcessInfo.processInfo.environment["SM_OPEN_EDITOR"] == "1" {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                tab = .editor
            }
        }
        .task(id: session.id) {
            if ProcessInfo.processInfo.environment["SM_OPEN_DISPLAY"] == "1" {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                tab = .display
            }
        }
        .sheet(isPresented: $finishSheet) {
            if let chrome { FinishSheet(chrome: chrome) }
        }
        .alert("Rename session", isPresented: $showRename) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) {}
            Button("Rename") { broker.rename(session.id, to: renameText) }
        }
        .confirmationDialog("Kill “\(session.name)”?", isPresented: $showKillConfirm, titleVisibility: .visible) {
            Button("Kill session", role: .destructive) { broker.kill(session.id) }
            Button("Cancel", role: .cancel) {}
        }
    }

    // The Chat tab renders the shared ChatPane (transcript + composer with voice dictation) —
    // the same component IPadWorkspace uses. Toolbar-owned state (rename/kill) and the
    // git-action banner are passed down as bindings.
    private var chatPane: some View {
        ChatPane(broker: broker, session: session,
                 showRename: $showRename, renameText: $renameText,
                 showKillConfirm: $showKillConfirm, banner: $banner)
    }

    // The raw agent terminal — the "Native" view of a (claude) session, its own tab.
    private var nativePane: some View {
        TerminalPane(broker: broker, session: session, kind: "agent", terminalId: nil,
                     onExit: { tab = .chat })
    }

    private func showBanner(_ text: String) {
        banner = text
        Task { try? await Task.sleep(nanoseconds: 4_000_000_000); banner = nil }
    }
    private func gitAction(_ op: @escaping () async -> GitOpResult?) {
        Task {
            let r = await op()
            showBanner(gitResultText(r))
            git = await broker.gitStatus(session.id)
        }
    }
    private func gitResultText(_ r: GitOpResult?) -> String {
        guard let r else { return "Failed" }
        switch r.status {
        case "pushed": return "Pushed"
        case "up_to_date": return "Up to date"
        case "clean": return "Pulled"
        case "rejected_non_ff": return "Push rejected — pull first"
        case "conflict": return "Conflict in \(r.files.count) file(s)"
        case "dirty": return "Uncommitted changes block the pull"
        case "auth_failed": return "Auth failed"
        case "error": return r.message ?? "Error"
        default: return r.status
        }
    }
    /// The unacked-finish dot on the toolbar Finish button: red when the last (background)
    /// job failed, teal when it succeeded; hidden while running or once acknowledged.
    @ViewBuilder private var finishBadge: some View {
        if let chrome, chrome.isUnacked {
            Circle()
                .fill(chrome.currentJob?.status == "failed" ? Color.red : Theme.teal)
                .frame(width: 8, height: 8)
        }
    }

    /// Subtitle under the inline title: branch + sync status when in a repo, else the workdir.
    /// Kept off the title row so a long session name can't crowd it (it truncates on its own line).
    private var navSubtitle: String {
        // Prefer the at-a-glance base-mode status (worktree vs base branch), live from the broker.
        let lite = broker.sessions.first { $0.id == session.id }?.git ?? session.git
        if let lite, let badge = GitBadgeKt.gitBadge(git: lite), badge.kind == .base {
            return lite.compareRef.isEmpty ? badge.text : "\(lite.compareRef) \(badge.text)"
        }
        if let g = git, g.isRepo, let b = g.branch {
            if g.upstream == nil { return "\(b) · not published" }
            var s = b
            if g.ahead > 0 { s += " ↑\(g.ahead)" }
            if g.behind > 0 { s += " ↓\(g.behind)" }
            return s
        }
        return formatWorkdir(workdir: session.workdir, home: inferHomeDir(workdir: session.workdir))
    }

    /// Overflow menu (•••): Detail density + git actions + session links.
    /// Always shown (not only when git/links exist) so Detail is always reachable.
    @ViewBuilder private var navMenu: some View {
        Menu {
            Menu {
                ForEach(ChatDetailLevel.allCases, id: \.self) { level in
                    Button {
                        guard level.isImplemented else { return }
                        UserDefaults.standard.set(level.rawValue, forKey: "chatDetailLevel")
                    } label: {
                        HStack {
                            Text(level.label + (level == .high ? " · Soon" : ""))
                            if ChatDetailLevel.parse(UserDefaults.standard.string(forKey: "chatDetailLevel")) == level {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                    .disabled(!level.isImplemented)
                }
            } label: {
                let cur = ChatDetailLevel.parse(UserDefaults.standard.string(forKey: "chatDetailLevel"))
                Label("Detail · \(cur.label)", systemImage: "text.alignleft")
            }
            if let g = git, g.isRepo {
                Divider()
                Button { gitAction { await broker.gitFetch(session.id) } } label: { Label("Fetch", systemImage: "arrow.down") }
                Button { gitAction { await broker.gitPush(session.id) } } label: { Label("Push", systemImage: "arrow.up") }
                Button { gitAction { await broker.gitPull(session.id) } } label: { Label("Pull", systemImage: "arrow.down.to.line") }
                if g.upstream == nil {
                    Button { gitAction { await broker.gitPublish(session.id) } } label: { Label("Publish", systemImage: "arrow.up.to.line") }
                }
            }
            if !sessionLinks.isEmpty {
                Divider()
                ForEach(sessionLinks, id: \.domain) { p in
                    if let u = linkURL(p) { Link(destination: u) { Label(proxyDisplayUrl(proxy: p), systemImage: "link") } }
                }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
    }
    private func linkURL(_ p: ProxyDto) -> URL? { URL(string: proxyUrl(proxy: p)) }
}

// MessageRow, AttachmentView, CameraPicker → ChatMessages.swift (attachments use system Quick Look)
// The transcript + composer (with voice dictation) → ChatPane.swift (shared iPhone + iPad)
// OptionSwitchSheet → OptionSwitchSheet.swift
// ChatBlock, ToolRow, ToolStatus, tsMs, buildChatBlocks, ToolRowView → ChatActivity.swift
