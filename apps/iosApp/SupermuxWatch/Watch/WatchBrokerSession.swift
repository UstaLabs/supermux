import Foundation
import Shared

/// Slim observable wrapper over the shared KMP `BrokerClient`/`BrokerApi` for the
/// watch: subscribe to the broker, expose active sessions + per-session messages,
/// send a reply, clean a dictation draft, and load an attachment's bytes.
///
/// Deliberately minimal — no terminal / editor / display / git / finish, which don't
/// belong on the wrist. Reuses the SAME shared client the iPhone uses (compiled for
/// watchOS), so the connection + DTOs are identical.
@MainActor
@Observable
final class WatchBrokerSession {
    let baseURL: String
    private let token: String
    private let api: BrokerApi
    private let client: BrokerClient

    private(set) var sessions: [SessionInfo] = []
    private(set) var messages: [String: [LogEntry]] = [:]
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
            for await frame in self.client.frames { self.reduce(frame) }
        }
        Task { [weak self] in try? await self?.client.run() }
    }

    private func reduce(_ frame: ServerFrame) {
        switch onEnum(of: frame) {
        case .snapshot(let s):
            sessions = s.sessions
            messages = s.logs
            synced = true
        case .sessionAdded(let a):
            sessions.append(a.session)
        case .sessionRemoved(let r):
            sessions.removeAll { $0.id == r.id }
            messages.removeValue(forKey: r.id)
        case .messageAppend(let m):
            // Drop the optimistic local echo when the real inbound message arrives.
            if m.entry.direction.hasPrefix("in") {
                messages[m.session]?.removeAll { $0.id.hasPrefix("local-") && $0.text == m.entry.text }
            }
            messages[m.session, default: []].append(m.entry)
        default:
            break   // terminal/editor/display/lsp/etc — not used on the watch
        }
    }

    /// Active sessions, most-recent activity first (newest last-message timestamp).
    var orderedSessions: [SessionInfo] {
        sessions.sorted { (messages[$0.id]?.last?.ts ?? "") > (messages[$1.id]?.last?.ts ?? "") }
    }

    func send(_ sessionId: String, _ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        // Optimistic local echo (deduped when the broker echoes the real inbound).
        let optimistic = LogEntry(
            id: "local-\(messages[sessionId]?.count ?? 0)-\(abs(t.hashValue))",
            ts: ISO8601DateFormatter().string(from: Date()),
            direction: "inbound", text: t,
            op: nil, channel: nil, chat_id: nil, message_id: nil, attachments: nil
        )
        messages[sessionId, default: []].append(optimistic)
        let frame = ClientFrameSend(session: sessionId, op: "reply",
                                    args: SendArgs(text: t, attachments: nil))
        Task { [client] in try? await client.send(frame: frame) }
    }

    private struct TranscribeResponse: Decodable { let text: String; let degraded: Bool? }

    /// POST a rough dictation draft → broker LLM cleanup (applies the voice glossary)
    /// → cleaned text. Bearer-authed direct HTTP (same shape as the iOS app).
    func transcribeDraft(sessionId: String, draft: String) async throws -> String {
        guard let url = URL(string: "\(baseURL)/sessions/\(sessionId)/transcribe") else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["draft": draft])
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return try JSONDecoder().decode(TranscribeResponse.self, from: data).text
    }

    /// Bearer-authed attachment bytes (inline photos in the history view).
    func loadFile(_ id: String) async -> Data? {
        guard let url = URL(string: "\(baseURL)/files/\(id)") else { return nil }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return try? await URLSession.shared.data(for: req).0
    }
}
