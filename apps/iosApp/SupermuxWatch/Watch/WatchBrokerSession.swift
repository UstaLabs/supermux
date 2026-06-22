import Foundation

/// Observable watch-side broker state: subscribes via `WatchSocket` (pure-Swift
/// WebSocket — no KMP/SKIE so it works on arm64_32), exposes active sessions +
/// per-session messages, sends a reply, cleans a dictation draft, and loads an
/// attachment's bytes. Deliberately minimal — no terminal/editor/display/git.
@MainActor
@Observable
final class WatchBrokerSession {
    let baseURL: String
    private let token: String
    private let socket: WatchSocket?

    private(set) var sessions: [SessionInfo] = []
    private(set) var messages: [String: [LogEntry]] = [:]
    private(set) var synced = false

    init(baseURL: String, token: String) {
        self.baseURL = baseURL
        self.token = token
        self.socket = WatchSocket(baseURL: baseURL, token: token)
    }

    func start() {
        socket?.onSyncChange = { [weak self] s in Task { @MainActor in self?.synced = s } }
        socket?.onEvent = { [weak self] ev in Task { @MainActor in self?.reduce(ev) } }
        socket?.start()
    }

    private func reduce(_ ev: WatchServerEvent) {
        switch ev {
        case .snapshot(let sessions, let logs):
            self.sessions = sessions
            self.messages = logs
            self.synced = true
        case .sessionAdded(let s):
            sessions.append(s)
        case .sessionRemoved(let id):
            sessions.removeAll { $0.id == id }
            messages.removeValue(forKey: id)
        case .messageAppend(let session, let entry):
            // Drop the optimistic local echo when the real inbound message arrives.
            if entry.direction.hasPrefix("in") {
                messages[session]?.removeAll { $0.id.hasPrefix("local-") && $0.text == entry.text }
            }
            messages[session, default: []].append(entry)
        }
    }

    /// Active sessions, most-recent activity first.
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
            direction: "inbound", text: t, attachments: nil
        )
        messages[sessionId, default: []].append(optimistic)
        socket?.sendReply(session: sessionId, text: t)
    }

    private struct TranscribeResponse: Decodable { let text: String; let degraded: Bool? }

    /// POST a rough dictation draft → broker LLM cleanup (applies the voice glossary)
    /// → cleaned text.
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

    /// Bearer-authed attachment bytes (inline photos).
    func loadFile(_ id: String) async -> Data? {
        guard let url = URL(string: "\(baseURL)/files/\(id)") else { return nil }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return try? await URLSession.shared.data(for: req).0
    }
}
