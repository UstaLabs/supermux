import Foundation

/// Decoded broker frames the watch cares about.
enum WatchServerEvent {
    case snapshot(sessions: [SessionInfo], logs: [String: [LogEntry]])
    case sessionAdded(SessionInfo)
    case sessionRemoved(id: String)
    case messageAppend(session: String, entry: LogEntry)
}

/// A minimal `URLSessionWebSocketTask` client for the broker control socket —
/// pure Swift (no KMP/SKIE), so it works on the watch device arch (arm64_32).
/// Mirrors the shared `BrokerClient`: subscribe on connect, answer pings, decode
/// JSON frames by their `type`, and reconnect with exponential backoff.
final class WatchSocket {
    private let wsURL: URL
    private let token: String
    private let session = URLSession(configuration: .default)
    private var task: URLSessionWebSocketTask?
    private var running = false
    private var backoffNs: UInt64 = 500_000_000

    var onEvent: ((WatchServerEvent) -> Void)?
    var onSyncChange: ((Bool) -> Void)?
    var onStatus: ((String) -> Void)?   // diagnostic: human-readable connection state

    init?(baseURL: String, token: String) {
        // Darwin WebSocket requires ws(s):// (http(s):// is rejected). Be robust to a
        // trailing slash, a missing scheme, and http vs https.
        var s = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        if s.hasPrefix("https://") { s = "wss://" + s.dropFirst(8) }
        else if s.hasPrefix("http://") { s = "ws://" + s.dropFirst(7) }
        else if !s.hasPrefix("ws://") && !s.hasPrefix("wss://") { s = "wss://" + s }
        guard let u = URL(string: s + "/ws") else { return nil }
        self.wsURL = u
        self.token = token
    }

    func start() {
        guard !running else { return }
        running = true
        Task { await loop() }
    }

    func stop() {
        running = false
        task?.cancel(with: .goingAway, reason: nil)
    }

    private func loop() async {
        var attempt = 0
        while running {
            var req = URLRequest(url: wsURL)
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let t = session.webSocketTask(with: req)
            t.maximumMessageSize = 64 * 1024 * 1024   // snapshot can exceed the 1 MB default
            task = t
            onStatus?("opening WS (try \(attempt + 1))")
            t.resume()
            send(raw: "{\"type\":\"subscribe\"}")
            onStatus?("subscribed; waiting for data")
            do {
                while running {
                    switch try await t.receive() {
                    case .string(let text): handle(text)
                    case .data(let d): if let text = String(data: d, encoding: .utf8) { handle(text) }
                    @unknown default: break
                    }
                }
            } catch {
                let e = error as NSError
                onStatus?("err \(e.domain) \(e.code): \(e.localizedDescription)")
            }
            onSyncChange?(false)
            guard running else { break }
            attempt += 1
            try? await Task.sleep(nanoseconds: backoffNs)
            backoffNs = min(backoffNs * 2, 8_000_000_000)
        }
    }

    private func handle(_ text: String) {
        if text.contains("\"type\":\"ping\"") { send(raw: "{\"type\":\"pong\"}"); return }
        guard let data = text.data(using: .utf8),
              let type = (try? JSONDecoder().decode(Envelope.self, from: data))?.type else { return }
        let dec = JSONDecoder()
        switch type {
        case "snapshot":
            guard let p = try? dec.decode(SnapshotPayload.self, from: data) else { return }
            backoffNs = 500_000_000
            onSyncChange?(true)
            onEvent?(.snapshot(sessions: p.sessions, logs: p.logs))
        case "session_added":
            if let p = try? dec.decode(SessionAddedPayload.self, from: data) { onEvent?(.sessionAdded(p.session)) }
        case "session_removed":
            if let p = try? dec.decode(SessionRemovedPayload.self, from: data) { onEvent?(.sessionRemoved(id: p.id)) }
        case "message_append":
            if let p = try? dec.decode(MessageAppendPayload.self, from: data) { onEvent?(.messageAppend(session: p.session, entry: p.entry)) }
        default:
            break   // agent_state / activity / lsp / display / etc. — unused on the watch
        }
    }

    private func send(raw: String) {
        task?.send(.string(raw)) { _ in }
    }

    /// Send a reply (mirrors ClientFrame.Send: {type:send, session, op:reply, args:{text}}).
    func sendReply(session sid: String, text: String) {
        let body: [String: Any] = ["type": "send", "session": sid, "op": "reply", "args": ["text": text]]
        guard let d = try? JSONSerialization.data(withJSONObject: body),
              let s = String(data: d, encoding: .utf8) else { return }
        send(raw: s)
    }

    private struct Envelope: Decodable { let type: String }
    private struct SnapshotPayload: Decodable { let sessions: [SessionInfo]; let logs: [String: [LogEntry]] }
    private struct SessionAddedPayload: Decodable { let session: SessionInfo }
    private struct SessionRemovedPayload: Decodable { let id: String }
    private struct MessageAppendPayload: Decodable { let session: String; let entry: LogEntry }
}
