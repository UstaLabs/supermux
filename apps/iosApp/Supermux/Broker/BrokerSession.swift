import Foundation
import Shared

/// Observable wrapper over the shared `BrokerClient` — mirrors the Android
/// `AppViewModel`: collect `client.frames` into UI state, run the socket loop,
/// and expose `BrokerApi` for REST actions. SKIE gives us `async` + typed enums.
@MainActor
@Observable
final class BrokerSession {
    let baseURL: String
    private let token: String
    let api: BrokerApi
    private let client: BrokerClient

    private(set) var sessions: [SessionInfo] = []
    private(set) var messages: [String: [LogEntry]] = [:]
    private(set) var activity: [String: [ActivityEvent]] = [:]
    private(set) var agentPhase: [String: String] = [:]
    private(set) var agentSince: [String: Int64] = [:]
    private(set) var commands: [String: [SlashCommand]] = [:]
    private(set) var synced = false

    init(baseURL: String, token: String) {
        self.baseURL = baseURL
        self.token = token
        let http = IosClientKt.iosHttpClient()
        self.api = BrokerApi(baseUrl: baseURL, token: token, http: http)
        self.client = BrokerClient(baseUrl: baseURL, token: token, http: http,
                                   policy: ReconnectPolicy(baseMs: 500, maxMs: 8000))
    }

    /// Build a terminal WS client for a session. Centralized here so the device
    /// token stays private (mirrors how `api`/`client` are constructed).
    func terminalClient(sessionId: String, kind: String, terminalId: String?) -> TerminalClient {
        TerminalClient(
            baseUrl: baseURL,
            token: token,
            http: IosClientKt.iosHttpClient(),
            sessionId: sessionId,
            kind: kind,
            terminalId: terminalId
        )
    }

    func start() {
        Task { [weak self] in
            guard let self else { return }
            for await frame in self.client.frames {
                self.reduce(frame)
            }
        }
        Task { [weak self] in try? await self?.client.run() }
    }

    private func reduce(_ frame: ServerFrame) {
        switch onEnum(of: frame) {
        case .snapshot(let s):
            sessions = s.sessions
            messages = s.logs
            activity = s.activity
            agentPhase = s.agentState.mapValues { $0.phase }
            agentSince = s.agentState.compactMapValues { $0.since?.int64Value }
            commands = s.commands
            synced = true
        case .sessionAdded(let a): sessions.append(a.session)
        case .sessionRemoved(let r): sessions.removeAll { $0.id == r.id }
        case .messageAppend(let m):
            // Drop the optimistic local echo when the real inbound message arrives.
            if m.entry.direction.hasPrefix("in") {
                messages[m.session]?.removeAll { $0.id.hasPrefix("local-") && $0.text == m.entry.text }
            }
            messages[m.session, default: []].append(m.entry)
        case .activityAppend(let a): activity[a.session, default: []].append(a.event)
        case .agentState(let st):
            agentPhase[st.session] = st.phase
            agentSince[st.session] = (st.since ?? st.workingSince)?.int64Value
        case .commandsChanged(let c): commands[c.session] = c.commands
        case .agentError: break
        }
    }

    func send(_ sessionId: String, _ text: String, attachments: [String]? = nil) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let atts = attachments ?? []
        guard !t.isEmpty || !atts.isEmpty else { return }
        // Optimistic local echo so the message appears instantly (web parity).
        // The broker's real inbound echo replaces it (deduped in reduce()).
        if !t.isEmpty {
            let optimistic = LogEntry(
                id: "local-\(messages[sessionId]?.count ?? 0)-\(abs(t.hashValue))",
                ts: ISO8601DateFormatter().string(from: Date()),
                direction: "inbound", text: t,
                op: nil, channel: nil, chat_id: nil, message_id: nil, attachments: nil
            )
            messages[sessionId, default: []].append(optimistic)
        }
        let frame = ClientFrameSend(session: sessionId, op: "reply",
                                    args: SendArgs(text: t, attachments: atts.isEmpty ? nil : atts))
        Task { [client] in try? await client.send(frame: frame) }
    }

    /// Upload bytes (base64 over the wire) → file id, for composing attachments.
    func upload(_ sessionId: String, data: Data, filename: String, mime: String, kind: String? = nil) async -> String? {
        let b64 = data.base64EncodedString()
        return (try? await api.uploadBase64(session: sessionId, base64: b64, filename: filename, mime: mime, kind: kind))?.file_id
    }

    /// PWA-identical grouping (Personal Assistants + per-workdir) via the shared helper.
    func groups() -> [SessionGroup] {
        let home = inferHomeDir(workdir: sessions.first?.workdir) ?? ""
        return groupSessions(sessions: sessions, home: home, lastTs: { [messages] s in
            messages[s.id]?.last?.ts ?? ""
        })
    }

    // MARK: - Session actions (mirror the web SessionListView)
    func toggleMute(_ s: SessionInfo) {
        let next = !(s.mute?.boolValue ?? false)
        Task { [api] in try? await api.setMute(id: s.id, muted: next) }
    }
    func rename(_ id: String, to name: String) {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty else { return }
        Task { [api] in try? await api.rename(id: id, name: n) }
    }
    func kill(_ id: String) {
        Task { [api] in try? await api.kill(id: id) }
    }
    func interrupt(_ id: String) {
        Task { [api] in try? await api.interrupt(id: id) }
    }

    // Git status + finish (chat header).
    func gitStatus(_ id: String) async -> GitRemoteStatus? { try? await api.gitStatus(id: id) }
    func gitFetch(_ id: String) async -> GitOpResult? { try? await api.gitFetch(id: id) }
    func gitPublish(_ id: String) async -> GitOpResult? { try? await api.gitPublish(id: id) }
    func gitPush(_ id: String) async -> GitOpResult? { try? await api.gitPush(id: id) }
    func gitPull(_ id: String) async -> GitOpResult? { try? await api.gitPull(id: id) }
    func finish(_ id: String, skipVerify: Bool? = nil, commitFirst: Bool? = nil, commitMessage: String? = nil) async -> FinishResult? {
        try? await api.finish(id: id, skipVerify: skipVerify?.kb, commitFirst: commitFirst?.kb, commitMessage: commitMessage)
    }
    func sendMessage(_ id: String, _ text: String) { Task { [api] in try? await api.sendMessage(id: id, text: text) } }
    func projects() async -> [String] { (try? await api.listProjects()) ?? [] }
    func spawn(workdir: String, agent: String?, name: String?, model: String? = nil,
               worktree: Bool? = nil, baseBranch: String? = nil) async -> String? {
        // Resolve ~ to an absolute path so the worktree is cut from the real repo root (web parity).
        let resolved = (try? await api.validatePath(path: workdir)).flatMap { $0.ok ? $0.path : nil } ?? workdir
        let req = SpawnRequest(workdir: resolved, name: name, agent: agent, model: model,
                               worktree: worktree?.kb, baseBranch: baseBranch)
        return (try? await api.spawn(req: req))?.id
    }

    // MARK: - Launcher: worktree + git-hosting parity with the web SessionLauncherView

    /// Validate (resolve ~) then load git repo info — drives the launcher's worktree picker.
    /// Returns nil when the path isn't a resolvable directory (→ no worktree option, like web).
    func repoInfo(_ path: String, fetch: Bool = false) async -> RepoInfo? {
        guard let resolved = (try? await api.validatePath(path: path)).flatMap({ $0.ok ? $0.path : nil })
        else { return nil }
        return try? await api.getRepoInfo(path: resolved, fetch: fetch)
    }

    /// Configured GitHub/GitLab connections (for clone/create in the project picker).
    func forges() async -> [ForgeConnection] { (try? await api.listForges())?.connections ?? [] }
    /// Matching remote repos across all connections (caller debounces).
    func searchForge(_ query: String) async -> [RemoteRepo] { (try? await api.searchForge(query: query))?.repos ?? [] }
    /// Clone a remote repo → local checkout path.
    func cloneForge(connectionId: String, owner: String, name: String) async -> String? {
        (try? await api.cloneForge(connectionId: connectionId, owner: owner, name: name))?.localPath
    }
    /// Create a remote repo on a forge (always private, web parity) then clone → local path.
    func createForge(connectionId: String, name: String) async -> String? {
        (try? await api.createForge(connectionId: connectionId, name: name, isPrivate: true))?.localPath
    }
    /// `git init` a fresh local repo under the projects root → local path.
    func createLocalRepo(_ name: String) async -> String? {
        (try? await api.createLocalRepo(name: name))?.localPath
    }
    func listModels(_ agent: String) async -> [ModelInfo] {
        (try? await api.listModels(agent: agent))?.models ?? []
    }
    /// Agent slash commands for the new-session launcher (no live session yet).
    func previewCommands(_ agent: String, _ workdir: String) async -> [SlashCommand] {
        (try? await api.previewCommands(agent: agent, workdir: workdir))?.commands ?? []
    }

    func models(_ id: String) async -> ModelsResponse? { try? await api.models(id: id) }
    func switchModel(_ id: String, _ model: String) {
        Task { [api] in try? await api.switchModel(id: id, model: model) }
    }
    func reasoning(_ id: String) async -> ReasoningResponse? { try? await api.reasoningLevels(id: id) }
    func switchReasoning(_ id: String, _ level: String) {
        Task { [api] in try? await api.switchReasoning(id: id, level: level) }
    }

    func archived() async -> [ArchivedDto] { (try? await api.archived()) ?? [] }
    func resume(_ id: String) { Task { [api] in try? await api.resume(id: id) } }
    func archivedLogs(_ id: String) async -> [LogEntry] { (try? await api.archivedLogs(sessionId: id)) ?? [] }

    // Usage (typed), device mint/revoke, proxy privacy — mirror the web pages.
    func usage() async -> UsageResponse? { try? await api.usage() }
    func addDevice(_ name: String) async -> AddDeviceResponse? { try? await api.addDevice(name: name) }
    func revokeDevice(_ name: String) async { try? await api.revokeDevice(name: name) }
    func setProxyPublic(_ domain: String, _ isPublic: Bool) async { try? await api.setProxyPublic(domain: domain, isPublic: isPublic) }
    func removeProxy(_ domain: String) async { try? await api.removeProxy(domain: domain) }

    // Personal Assistants.
    func pas() async -> [PADto] { (try? await api.listPAs()) ?? [] }
    func createPA(name: String, agent: String?, model: String?, focusText: String?) async {
        try? await api.createPA(name: name, agent: agent, model: model, focusText: focusText)
    }

    func config() async -> AppConfigDto? { try? await api.getConfig() }
    func setPAName(_ name: String) { Task { [api] in try? await api.putConfig(paName: name) } }
    func curatorSettings() async -> CuratorSettingsResponse? { try? await api.getCuratorSettings() }
    func saveCurator(enabled: Bool, hour: Int, minute: Int) {
        Task { [api] in _ = try? await api.saveCuratorSettings(enabled: enabled, hour: Int32(hour), minute: Int32(minute)) }
    }
    func runCuratorNow() { Task { [api] in try? await api.runCuratorNow() } }

    /// Load an attachment's bytes (Bearer-authed) — used for inline images.
    func loadFile(_ id: String) async -> Data? {
        guard let url = URL(string: "\(baseURL)/files/\(id)") else { return nil }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return try? await URLSession.shared.data(for: req).0
    }

    // MARK: - Editor filesystem (workdir-relative paths)
    func fsList(_ id: String, _ path: String) async -> [FsEntry] { (try? await api.fsList(sessionId: id, path: path)) ?? [] }
    func fsRead(_ id: String, _ path: String) async throws -> String { try await api.fsRead(sessionId: id, path: path) }
    func fsWrite(_ id: String, _ path: String, _ content: String) async -> Bool { (try? await api.fsWrite(sessionId: id, path: path, content: content))?.boolValue ?? false }
    func fsSearch(_ id: String, _ q: String) async -> [FsSearchResult] { (try? await api.fsSearch(sessionId: id, q: q)) ?? [] }

    // MARK: - Editor state (one per session, cached so open tabs / tree expansion /
    // scroll survive pane AND session switches — full state preservation is required).
    @ObservationIgnored private var editorStates: [String: EditorState] = [:]
    func editorState(for sessionId: String) -> EditorState {
        if let existing = editorStates[sessionId] { return existing }
        let state = EditorState(
            sessionId: sessionId,
            fsRead: { [weak self] path in
                guard let self else { return "" }
                return try await self.fsRead(sessionId, path)
            },
            fsWrite: { [weak self] path, content in
                guard let self else { return false }
                return await self.fsWrite(sessionId, path, content)
            }
        )
        editorStates[sessionId] = state
        return state
    }
}

private extension Bool {
    /// Box for SKIE-bridged Kotlin `Boolean?` parameters.
    var kb: KotlinBoolean { KotlinBoolean(bool: self) }
}
