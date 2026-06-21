import SwiftUI
import Shared

/// Session-level header state + actions shared by the compact `ChatView` toolbar and the
/// regular-width `IPadWorkspace` header bar: git status, proxy links, the finish flow
/// (no-verify / uncommitted prompts), git ops, and the transient result banner.
///
/// Extracted from `ChatView` so BOTH paths drive one source of truth instead of duplicating
/// ~100 lines of finish/git plumbing. The owning view binds its dialogs to the published
/// flags (`noVerifyConfirm`, `commitPrompt`, …) and renders `banner`/`navSubtitle` itself;
/// the menus differ per call site (ChatView shows git+links, the iPad header adds the
/// management pages), so the chrome exposes the data + actions and each view builds its Menu.
@MainActor @Observable final class SessionChrome {
    let broker: BrokerSession
    var session: SessionInfo

    // Loaded session state (parity with ChatView's former @State).
    var git: GitRemoteStatus?
    var proxies: [ProxyDto] = []
    var banner: String?

    // Finish-flow dialog flags — owned here so every call site gets the same prompts.
    var noVerifyConfirm = false
    var commitPrompt = false
    var commitMsg = ""

    private var loadedSessionId: String?
    private var loadTask: Task<Void, Never>?

    init(broker: BrokerSession, session: SessionInfo) {
        self.broker = broker
        self.session = session
    }

    /// True once git status has loaded and the workdir is a git repo (gates Finish + git ops).
    var isRepo: Bool { git?.isRepo ?? false }
    /// Proxy links bound to this session (the header's SessionLinks source).
    var sessionLinks: [ProxyDto] { proxies.filter { $0.sessionName == session.name } }

    // MARK: - Session load

    /// (Re)load everything tied to `session`. Idempotent per id: re-pointing at the same
    /// session is a no-op so re-appearances don't refetch, while a real switch reloads.
    /// Git status is retried (≈8×1.5s) so the branch reliably appears after the broker warms up.
    func load(for session: SessionInfo) {
        self.session = session
        guard loadedSessionId != session.id else { return }
        loadedSessionId = session.id
        git = nil
        loadTask?.cancel()
        let id = session.id
        loadTask = Task {
            for _ in 0..<8 {
                if Task.isCancelled { return }
                if let g = await broker.gitStatus(id) { git = g; break }
                try? await Task.sleep(nanoseconds: 1_500_000_000)
            }
        }
        Task { proxies = (try? await broker.api.proxies()) ?? [] }
    }

    // MARK: - Navigation subtitle

    /// Subtitle under the title: branch + sync status when in a repo, else the workdir.
    var navSubtitle: String {
        if let g = git, g.isRepo, let b = g.branch {
            if g.upstream == nil { return "\(b) · not published" }
            var s = b
            if g.ahead > 0 { s += " ↑\(g.ahead)" }
            if g.behind > 0 { s += " ↓\(g.behind)" }
            return s
        }
        return formatWorkdir(workdir: session.workdir, home: inferHomeDir(workdir: session.workdir))
    }

    // MARK: - Banner

    private func showBanner(_ text: String) {
        banner = text
        Task { try? await Task.sleep(nanoseconds: 4_000_000_000); banner = nil }
    }

    // MARK: - Git ops

    func gitAction(_ op: @escaping () async -> GitOpResult?) {
        Task {
            let r = await op()
            showBanner(Self.gitResultText(r))
            git = await broker.gitStatus(session.id)
        }
    }
    static func gitResultText(_ r: GitOpResult?) -> String {
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

    func fetch()   { gitAction { [broker, id = session.id] in await broker.gitFetch(id) } }
    func push()    { gitAction { [broker, id = session.id] in await broker.gitPush(id) } }
    func pull()    { gitAction { [broker, id = session.id] in await broker.gitPull(id) } }
    func publish() { gitAction { [broker, id = session.id] in await broker.gitPublish(id) } }

    // MARK: - Finish flow

    func runFinish(skipVerify: Bool? = nil, commitFirst: Bool? = nil, commitMessage: String? = nil) {
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

    func linkURL(_ p: ProxyDto) -> URL? { URL(string: proxyUrl(proxy: p)) }
}
