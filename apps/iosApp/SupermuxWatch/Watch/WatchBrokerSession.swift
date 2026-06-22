import Foundation

/// Observable watch-side broker state over **REST polling** (NOT a WebSocket —
/// `URLSessionWebSocketTask` is blocked on real watchOS devices; see Apple TN3135).
/// Plain REST data tasks DO work on watchOS, so we poll `GET /sessions` for the list
/// and `GET /sessions/{id}/messages` for the open session, and send via
/// `POST /sessions/{id}/message`. Keeps the watch independent (its own connection).
@MainActor
@Observable
final class WatchBrokerSession {
    let baseURL: String
    private let token: String

    private(set) var sessions: [SessionInfo] = []
    private(set) var messages: [String: [LogEntry]] = [:]
    private(set) var synced = false
    private(set) var status = ""   // diagnostic: last REST error, empty when healthy

    /// The session whose detail view is open — its messages get polled each tick.
    var activeSession: String?

    private var polling = false
    private var pendingEchoes: [String: [LogEntry]] = [:]   // optimistic sends awaiting server echo

    init(baseURL: String, token: String) {
        var b = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while b.hasSuffix("/") { b.removeLast() }
        self.baseURL = b
        self.token = token
    }

    func start() {
        guard !polling else { return }
        polling = true
        Task { await pollLoop() }
    }

    func stop() { polling = false }

    private func pollLoop() async {
        while polling {
            do {
                sessions = try await get("/sessions", [SessionInfo].self)
                synced = true
                status = ""
            } catch {
                status = describe(error)
            }
            if let id = activeSession, let log = try? await get("/sessions/\(id)/messages", [LogEntry].self) {
                messages[id] = merge(server: log, sessionId: id)
            }
            try? await Task.sleep(nanoseconds: 3_000_000_000)
        }
    }

    /// Active sessions in the broker's order.
    var orderedSessions: [SessionInfo] { sessions }

    /// Open a session's detail: poll its messages, and fetch once immediately.
    func openSession(_ id: String) {
        activeSession = id
        Task {
            if let log = try? await get("/sessions/\(id)/messages", [LogEntry].self) {
                messages[id] = merge(server: log, sessionId: id)
            }
        }
    }

    func closeSession() { activeSession = nil }

    func send(_ sessionId: String, _ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        // Optimistic echo, reconciled against the server on the next poll.
        let echo = LogEntry(
            id: "local-\(abs(t.hashValue))-\((messages[sessionId]?.count ?? 0))",
            ts: ISO8601DateFormatter().string(from: Date()),
            direction: "inbound", text: t, attachments: nil
        )
        pendingEchoes[sessionId, default: []].append(echo)
        messages[sessionId, default: []].append(echo)
        Task { [baseURL, token] in
            guard let url = URL(string: "\(baseURL)/sessions/\(sessionId)/message") else { return }
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: ["text": t])
            _ = try? await URLSession.shared.data(for: req)
        }
    }

    /// Server log + any optimistic echoes the server hasn't reflected yet (dedupe by text).
    private func merge(server: [LogEntry], sessionId: String) -> [LogEntry] {
        var pend = pendingEchoes[sessionId] ?? []
        pend.removeAll { echo in server.contains { $0.direction.hasPrefix("in") && $0.text == echo.text } }
        pendingEchoes[sessionId] = pend.isEmpty ? nil : pend
        return server + pend
    }

    // MARK: - REST helpers

    private func get<T: Decodable>(_ path: String, _ type: T.Type) async throws -> T {
        guard let url = URL(string: baseURL + path) else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.timeoutInterval = 15
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func describe(_ error: Error) -> String {
        let e = error as NSError
        return "REST err \(e.domain) \(e.code)"
    }

    private struct TranscribeResponse: Decodable { let text: String; let degraded: Bool? }

    /// POST a rough dictation draft → broker LLM cleanup (glossary) → cleaned text.
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
