import SwiftUI
import Shared

/// Session-level header state + actions shared by the compact `ChatView` toolbar and the
/// regular-width `IPadWorkspace` header bar: git status, proxy links, the finish flow
/// (readiness preflight → action → live job → recovery, surfaced by `FinishSheet`), git ops,
/// and the transient result banner.
///
/// Extracted from `ChatView` so BOTH paths drive one source of truth instead of duplicating
/// ~100 lines of finish/git plumbing. The owning view renders `banner`/`navSubtitle` itself
/// and presents `FinishSheet(chrome:)`; the menus differ per call site (ChatView shows
/// git+links, the iPad header adds the management pages), so the chrome exposes the data +
/// actions and each view builds its Menu.
@MainActor @Observable final class SessionChrome {
    let broker: BrokerSession
    var session: SessionInfo

    // Loaded session state (parity with ChatView's former @State).
    var git: GitRemoteStatus?
    var proxies: [ProxyDto] = []
    var banner: String?

    // Finish preflight snapshot (loaded by FinishSheet on open when no job is running).
    var readiness: FinishReadiness?
    // Unacked-badge: the startedAt the user has already seen. A terminal job whose startedAt
    // differs is "unacked" → the toolbar Finish button shows a dot until the sheet opens.
    var ackedAt: Double?
    // Set when kicking off a finish fails before any job is created (e.g. network error). The
    // FinishSheet menu surfaces it — ChatView renders its own banner (not `chrome.banner`), so
    // the menu is the reliable place to show a kickoff failure on both iPhone and iPad.
    var runError: String?

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
        if let lite = session.git, let badge = GitBadgeKt.gitBadge(git: lite), badge.kind == .base {
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

    /// The live finish job for this session, kept fresh by the WS `finish_job` frame
    /// (BrokerSession publishes `finishJobs`). nil when no finish has been kicked off.
    var currentJob: FinishJobDto? { broker.finishJobs[session.id] }

    /// A terminal finish job the user hasn't acknowledged yet (drives the toolbar button dot).
    /// Running jobs aren't "unacked" — the dot is for a result that arrived in the background.
    var isUnacked: Bool {
        guard let j = currentJob, j.status != "running" else { return false }
        return ackedAt != j.startedAt
    }

    /// Mark the current job's result as seen (called when the sheet opens).
    func ack() { if let j = currentJob { ackedAt = j.startedAt } }

    /// Drop this session's finish job so the sheet returns to the readiness menu (Dismiss/Done
    /// in FinishSheet). Mirrors the web `finishJob.clear(sessionId)`; without it a terminal
    /// outcome (esp. a `failed` one) is stuck on screen and can't be dismissed back to the menu.
    func clearJob() { broker.clearFinishJob(session.id) }

    /// Load the preflight snapshot for the finish menu (branch sync / diff / conflict / dirty).
    func loadReadiness() async {
        readiness = await broker.finishReadiness(session.id)
    }

    /// Kick off a finish job optimistically. `broker.finish` returns the *running* job; the
    /// real outcome arrives on the WS `finish_job` frame (BrokerSession keeps `finishJobs`
    /// fresh), so we don't branch on the result here — `currentJob` drives the sheet.
    func run(action: String, skipVerify: Bool? = nil, commitFirst: Bool? = nil, commitMessage: String? = nil) {
        runError = nil
        Task {
            guard await broker.finish(session.id, action: action, skipVerify: skipVerify,
                                      commitFirst: commitFirst, commitMessage: commitMessage) != nil else {
                // No job was created (kickoff failed) → the sheet stays on .menu; surface it there.
                runError = "Couldn't start finish — check your connection and try again."; return
            }
            // Refresh git after terminal outcomes land (the WS frame updates currentJob; this
            // keeps the header's branch/sync in step once a merge/keep/discard settles).
            git = await broker.gitStatus(session.id)
        }
    }

    /// Suggest a `.mux/verify.sh` for the no_verify recovery path.
    func generateVerify() async -> VerifySuggestResult? { await broker.verifySuggest(session.id) }

    /// Save an edited verify script; returns the result so the caller can auto-run merge on ok.
    func saveVerify(content: String) async -> VerifySaveResult? { await broker.verifySave(session.id, content: content) }

    /// Hand the failed-finish outcome back to the agent as a tailored message (web
    /// `FinishSheet.vue` `issueMessage` parity), then send it. Pure builder is `Self.issueMessage`.
    func letAgentFix(outcome: FinishResult) {
        broker.sendMessage(session.id, Self.issueMessage(outcome))
    }

    /// Build the agent message for a failed finish outcome — mirrors web `issueMessage`
    /// (FinishSheet.vue ~81-86) exactly. Pure + static so it's unit-testable.
    static func issueMessage(_ o: FinishResult) -> String {
        let files = o.files
        switch o.status {
        case "sync_conflict":
            let list = files.map { "- \($0)" }.joined(separator: "\n")
            return "The Finish step merged the base branch in and hit conflicts in:\n\(list)\n\nThe worktree is in a conflicted merge state — please resolve the conflicts and commit, then I'll run Finish again."
        case "tests_failed":
            return "The Finish step ran the tests (`\(o.command ?? "")`) and they failed:\n\n```\n\(o.output ?? "")\n```\n\nPlease fix them so the branch is green, then I'll run Finish again."
        case "dirty_overlap":
            return "The base checkout has unsaved changes in: \(files.joined(separator: ", ")) — the same files my work touches. Please commit or stash them so Finish can fast-forward."
        case "push_rejected":
            return "Pushing the branch for a PR was rejected because the remote has diverged: \(o.message ?? ""). Please reconcile (pull/rebase) and I'll run Finish again."
        default:
            return "Finish reported: \(o.message ?? o.status)"
        }
    }

    func linkURL(_ p: ProxyDto) -> URL? { URL(string: proxyUrl(proxy: p)) }
}
