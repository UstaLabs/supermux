import SwiftUI
import Shared
import UIKit
import PhotosUI
import UniformTypeIdentifiers

/// A photo staged in the composer, awaiting upload on send.
struct PendingAttachment: Identifiable {
    let id = UUID()
    let data: Data
    let filename: String
    let mime: String
}

/// TabView shell: Chat (`ChatPane`) · Native · Terminal · Editor · Display.
/// Owns navigation chrome (title, subtitle, toolbar) and session-level state that the
/// toolbar acts on — git status, rename/kill dialogs, git-op banner, finish flow.
/// Per-pane chat/composer state lives in `ChatPane`.
struct ChatView: View {
    let broker: BrokerSession
    let session: SessionInfo

    // MARK: - Toolbar / finish state
    @State private var showRename = false
    @State private var renameText = ""
    @State private var showKillConfirm = false
    @State private var git: GitRemoteStatus?
    @State private var banner: String?
    @State private var noVerifyConfirm = false
    @State private var commitPrompt = false
    @State private var commitMsg = ""
    @State private var loadedSessionId: String?
    @State private var proxies: [ProxyDto] = []

    // MARK: - Tab state
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
        guard let ui = UIImage(named: asset) else { return Image(systemName: "cube.transparent") }
        let canvas = CGSize(width: 26, height: 26)
        let inset: CGFloat = 4
        let rendered = UIGraphicsImageRenderer(size: canvas).image { _ in
            ui.draw(in: CGRect(x: inset, y: inset, width: canvas.width - 2 * inset, height: canvas.height - 2 * inset))
        }
        let image = Image(uiImage: rendered.withRenderingMode(.alwaysOriginal))
        Self.tabIconCache[asset] = image
        return image
    }

    private var sessionLinks: [ProxyDto] { proxies.filter { $0.sessionName == session.name } }

    // MARK: - Body

    var body: some View {
        // Native iOS 26 TabView → the system draws the floating Liquid Glass bar and its
        // selection (no hand-rolled chrome). Editor/Display are placeholders for now.
        TabView(selection: $tab) {
            Tab("Chat", systemImage: "bubble.left", value: PaneTab.chat) {
                ChatPane(broker: broker, session: session,
                         showRename: $showRename,
                         renameText: $renameText,
                         showKillConfirm: $showKillConfirm,
                         banner: $banner)
            }
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
        .navigationTitle(session.name)
        .navigationSubtitle(navSubtitle)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                AgentLogo(agent: session.agent, size: 20)
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                if let g = git, g.isRepo {
                    Button { runFinish() } label: { Label("Finish", systemImage: "arrow.triangle.merge") }
                        .tint(Theme.teal)
                }
                if (git?.isRepo ?? false) || !sessionLinks.isEmpty { navMenu }
            }
        }
        .toolbarTitleDisplayMode(.inline)
        // Load per-session state on EVERY appearance — `.task(id:)` doesn't re-fire when
        // re-opening the *same* session (id unchanged), which left git/branch unloaded.
        // onAppear covers first-open + reopen; onChange covers switching (reused view).
        .onAppear { if loadedSessionId != session.id { loadSession() } }
        .onChange(of: session.id) { _, _ in loadSession() }
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
        .alert("Rename session", isPresented: $showRename) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) {}
            Button("Rename") { broker.rename(session.id, to: renameText) }
        }
        .confirmationDialog("Kill "\(session.name)"?", isPresented: $showKillConfirm, titleVisibility: .visible) {
            Button("Kill session", role: .destructive) { broker.kill(session.id) }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("No verify script found", isPresented: $noVerifyConfirm, titleVisibility: .visible) {
            Button("Merge without verifying") { runFinish(skipVerify: true) }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Uncommitted changes", isPresented: $commitPrompt) {
            TextField("Commit message", text: $commitMsg)
            Button("Cancel", role: .cancel) {}
            Button("Commit & finish") { runFinish(commitFirst: true, commitMessage: commitMsg.isEmpty ? "wip" : commitMsg) }
        } message: { Text("Commit the session's changes, then finish.") }
    }

    // MARK: - Native pane

    // The raw agent terminal — the "Native" view of a (claude) session, now its own tab.
    private var nativePane: some View {
        TerminalPane(broker: broker, session: session, kind: "agent", terminalId: nil,
                     onExit: { tab = .chat })
    }

    // MARK: - Session load

    /// (Re)load everything tied to the current session. Runs on first open, reopen,
    /// and session switch — git status is retried so the branch reliably appears.
    private func loadSession() {
        loadedSessionId = session.id
        tab = .chat
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

    // MARK: - Navigation subtitle

    /// Subtitle under the inline title: branch + sync status when in a repo, else the workdir.
    /// Kept off the title row so a long session name can't crowd it (it truncates on its own line).
    private var navSubtitle: String {
        if let g = git, g.isRepo, let b = g.branch {
            if g.upstream == nil { return "\(b) · not published" }
            var s = b
            if g.ahead > 0 { s += " ↑\(g.ahead)" }
            if g.behind > 0 { s += " ↓\(g.behind)" }
            return s
        }
        return formatWorkdir(workdir: session.workdir, home: inferHomeDir(workdir: session.workdir))
    }

    // MARK: - Navigation menu

    /// Overflow menu (•••): git actions (when a repo) + session links. Folded out of the
    /// title row so the bar stays one tidy line regardless of session-name length.
    @ViewBuilder private var navMenu: some View {
        Menu {
            if let g = git, g.isRepo {
                Button { gitAction { await broker.gitFetch(session.id) } } label: { Label("Fetch", systemImage: "arrow.down") }
                Button { gitAction { await broker.gitPush(session.id) } } label: { Label("Push", systemImage: "arrow.up") }
                Button { gitAction { await broker.gitPull(session.id) } } label: { Label("Pull", systemImage: "arrow.down.to.line") }
                if g.upstream == nil {
                    Button { gitAction { await broker.gitPublish(session.id) } } label: { Label("Publish", systemImage: "arrow.up.to.line") }
                }
            }
            if !sessionLinks.isEmpty {
                if git?.isRepo ?? false { Divider() }
                ForEach(sessionLinks, id: \.domain) { p in
                    if let u = linkURL(p) { Link(destination: u) { Label(proxyDisplayUrl(proxy: p), systemImage: "link") } }
                }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
    }
    private func linkURL(_ p: ProxyDto) -> URL? { URL(string: proxyUrl(proxy: p)) }

    // MARK: - Git helpers

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
    private func runFinish(skipVerify: Bool? = nil, commitFirst: Bool? = nil, commitMessage: String? = nil) {
        Task {
            guard let r = await broker.finish(session.id, skipVerify: skipVerify,
                                              commitFirst: commitFirst, commitMessage: commitMessage) else {
                showBanner("Finish failed"); return
            }
            switch r.status {
            case "integrated": showBanner("Merged into \(r.base ?? "base")")
            case "nothing_to_do": showBanner("Nothing to merge")
            case "no_verify": noVerifyConfirm = true; return
            case "uncommitted": commitMsg = ""; commitPrompt = true; return
            case "sync_conflict": showBanner("Sync conflict in \(r.files.count) file(s) — resolve via the agent")
            case "tests_failed": showBanner("Verify failed: \(r.command ?? "tests")")
            case "dirty_overlap": showBanner("Dirty overlap in \(r.files.count) file(s)")
            case "non_ff": showBanner("Base moved — retry")
            case "error": showBanner(r.message ?? "Error")
            default: showBanner(r.status)
            }
            git = await broker.gitStatus(session.id)
        }
    }
}

// MessageRow, AttachmentView, Lightbox, ShareSheet, CameraPicker → ChatMessages.swift

// OptionSwitchSheet → OptionSwitchSheet.swift

// ChatBlock, ToolRow, ToolStatus, tsMs, buildChatBlocks, ToolRowView → ChatActivity.swift
