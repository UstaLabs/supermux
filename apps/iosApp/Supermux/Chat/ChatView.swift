import SwiftUI
import Shared
import UIKit

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

    init(broker: BrokerSession, session: SessionInfo) {
        self.broker = broker
        self.session = session
        _chrome = State(initialValue: SessionChrome(broker: broker, session: session))
    }

    // MARK: - Toolbar / finish state
    @State private var showRename = false
    @State private var renameText = ""
    @State private var showKillConfirm = false
    // Git status, proxies, finish flow, and the result banner live in SessionChrome — one
    // source of truth shared with the iPad header. Created per ChatView; `load(for:)` is
    // idempotent per session id, so re-appearing the same session doesn't refetch.
    @State private var chrome: SessionChrome

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

    private var sessionLinks: [ProxyDto] { chrome.sessionLinks }

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
                         banner: $chrome.banner)
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
        .navigationSubtitle(chrome.navSubtitle)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                AgentLogo(agent: session.agent, size: 20)
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                if chrome.isRepo {
                    Button { chrome.runFinish() } label: { Label("Finish", systemImage: "arrow.triangle.merge") }
                        .tint(Theme.teal)
                }
                if chrome.isRepo || !sessionLinks.isEmpty { navMenu }
            }
        }
        .toolbarTitleDisplayMode(.inline)
        // Load per-session state on EVERY appearance — `.task(id:)` doesn't re-fire when
        // re-opening the *same* session (id unchanged), which left git/branch unloaded.
        // onAppear covers first-open + reopen; onChange covers switching (reused view).
        // `load(for:)` is idempotent per id, so re-appearing the same session is a no-op.
        .onAppear { chrome.load(for: session) }
        .onChange(of: session.id) { _, _ in tab = .chat; chrome.load(for: session) }
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
        .confirmationDialog("Kill \u{201C}\(session.name)\u{201D}?", isPresented: $showKillConfirm, titleVisibility: .visible) {
            Button("Kill session", role: .destructive) { broker.kill(session.id) }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("No verify script found", isPresented: $chrome.noVerifyConfirm, titleVisibility: .visible) {
            Button("Merge without verifying") { chrome.runFinish(skipVerify: true) }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Uncommitted changes", isPresented: $chrome.commitPrompt) {
            TextField("Commit message", text: $chrome.commitMsg)
            Button("Cancel", role: .cancel) {}
            Button("Commit & finish") { chrome.runFinish(commitFirst: true, commitMessage: chrome.commitMsg.isEmpty ? "wip" : chrome.commitMsg) }
        } message: { Text("Commit the session's changes, then finish.") }
    }

    // MARK: - Native pane

    // The raw agent terminal — the "Native" view of a (claude) session, now its own tab.
    private var nativePane: some View {
        TerminalPane(broker: broker, session: session, kind: "agent", terminalId: nil,
                     onExit: { tab = .chat })
    }

    // MARK: - Navigation menu

    /// Overflow menu (•••): git actions (when a repo) + session links. Folded out of the
    /// title row so the bar stays one tidy line regardless of session-name length. Git/finish
    /// state + actions live in `chrome` (shared with the iPad header).
    @ViewBuilder private var navMenu: some View {
        Menu {
            if let g = chrome.git, g.isRepo {
                Button { chrome.fetch() } label: { Label("Fetch", systemImage: "arrow.down") }
                Button { chrome.push() } label: { Label("Push", systemImage: "arrow.up") }
                Button { chrome.pull() } label: { Label("Pull", systemImage: "arrow.down.to.line") }
                if g.upstream == nil {
                    Button { chrome.publish() } label: { Label("Publish", systemImage: "arrow.up.to.line") }
                }
            }
            if !sessionLinks.isEmpty {
                if chrome.isRepo { Divider() }
                ForEach(sessionLinks, id: \.domain) { p in
                    if let u = chrome.linkURL(p) { Link(destination: u) { Label(proxyDisplayUrl(proxy: p), systemImage: "link") } }
                }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
    }
}

// MessageRow, AttachmentView, Lightbox, ShareSheet, CameraPicker → ChatMessages.swift

// OptionSwitchSheet → OptionSwitchSheet.swift

// ChatBlock, ToolRow, ToolStatus, tsMs, buildChatBlocks, ToolRowView → ChatActivity.swift
