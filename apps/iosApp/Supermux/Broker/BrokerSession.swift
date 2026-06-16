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
    private(set) var synced = false

    init(baseURL: String, token: String) {
        self.baseURL = baseURL
        self.token = token
        let http = IosClientKt.iosHttpClient()
        self.api = BrokerApi(baseUrl: baseURL, token: token, http: http)
        self.client = BrokerClient(baseUrl: baseURL, token: token, http: http,
                                   policy: ReconnectPolicy(baseMs: 500, maxMs: 8000))
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
            synced = true
        case .sessionAdded(let a): sessions.append(a.session)
        case .sessionRemoved(let r): sessions.removeAll { $0.id == r.id }
        case .messageAppend(let m): messages[m.session, default: []].append(m.entry)
        case .activityAppend(let a): activity[a.session, default: []].append(a.event)
        case .agentState(let st):
            agentPhase[st.session] = st.phase
            agentSince[st.session] = (st.since ?? st.workingSince)?.int64Value
        case .commandsChanged: break
        case .agentError: break
        }
    }

    func send(_ sessionId: String, _ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        let frame = ClientFrameSend(session: sessionId, op: "reply",
                                    args: SendArgs(text: t, attachments: nil))
        Task { [client] in try? await client.send(frame: frame) }
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
    func projects() async -> [String] { (try? await api.listProjects()) ?? [] }
    func spawn(workdir: String, agent: String?, name: String?) async -> String? {
        let req = SpawnRequest(workdir: workdir, name: name, agent: agent, model: nil)
        return (try? await api.spawn(req: req))?.id
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
}
