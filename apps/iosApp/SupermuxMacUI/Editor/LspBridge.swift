import Foundation
import Shared

/// Per-session LSP control-plane + relay — the Swift half of the bridge, mirroring the web
/// `stores/lsp.ts`. The broker is a dumb JSON-RPC pipe; the real LSP protocol (initialize,
/// didOpen, completion, hover…) runs inside the WKWebView's CodeMirror `LSPClient`. Swift drives
/// the control frames (status query / open) and relays `lsp_rpc` in both directions.
@MainActor
final class LspBridge {
    struct Status {
        let supported: Bool
        let serverId: String?
        let languageId: String?
        let state: String?          // "ready" | "missing" | "prereq-missing" | "unavailable"
        let label: String?
        let installLabel: String?
        let requires: String?
        var isReady: Bool { supported && serverId != nil && state == "ready" }
    }

    let sessionId: String
    private let send: (ClientFrame) -> Void
    /// Set by EditorWebView — delivers an inbound `lsp_rpc` message into the webview's LSPClient.
    var onRpcIn: ((_ serverId: String, _ message: String) -> Void)?

    private var statusWaiters: [String: [(Status) -> Void]] = [:]   // key: path
    private var openWaiters: [String: [(Bool) -> Void]] = [:]       // key: serverId

    init(sessionId: String, send: @escaping (ClientFrame) -> Void) {
        self.sessionId = sessionId
        self.send = send
    }

    // MARK: control plane (request/response over the WS)

    func queryStatus(_ path: String) async -> Status {
        await withCheckedContinuation { (cont: CheckedContinuation<Status, Never>) in
            var resumed = false
            let resolve: (Status) -> Void = { s in if !resumed { resumed = true; cont.resume(returning: s) } }
            statusWaiters[path, default: []].append(resolve)
            send(ClientFrameLspStatusQuery(session: sessionId, path: path))
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 9_000_000_000)
                self?.fireStatus(path, Status(supported: false, serverId: nil, languageId: nil,
                                              state: "unavailable", label: nil, installLabel: nil, requires: nil))
            }
        }
    }

    func open(_ serverId: String) async -> Bool {
        await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            var resumed = false
            let resolve: (Bool) -> Void = { ok in if !resumed { resumed = true; cont.resume(returning: ok) } }
            openWaiters[serverId, default: []].append(resolve)
            send(ClientFrameLspOpen(session: sessionId, serverId: serverId))
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 12_000_000_000)
                self?.fireOpen(serverId, false)
            }
        }
    }

    func rpcOut(_ serverId: String, _ message: String) {
        send(ClientFrameLspRpcOut(session: sessionId, serverId: serverId, message: message))
    }
    func install(_ serverId: String) { send(ClientFrameLspInstall(serverId: serverId)) }
    func close(_ serverId: String) { send(ClientFrameLspClose(session: sessionId, serverId: serverId)) }

    // MARK: inbound routing (called from BrokerSession.reduce)

    func handleStatus(_ f: ServerFrameLspStatus) {
        fireStatus(f.path ?? "", Status(supported: f.supported, serverId: f.serverId, languageId: f.languageId,
                                        state: f.state, label: f.label, installLabel: f.installLabel, requires: f.requires))
    }
    func handleReady(_ serverId: String) { fireOpen(serverId, true) }
    func handleError(_ serverId: String?) { if let serverId { fireOpen(serverId, false) } }
    func handleRpcIn(_ serverId: String, _ message: String) { onRpcIn?(serverId, message) }

    private func fireStatus(_ path: String, _ s: Status) {
        guard let waiters = statusWaiters[path], !waiters.isEmpty else { return }
        statusWaiters[path] = nil
        waiters.forEach { $0(s) }
    }
    private func fireOpen(_ serverId: String, _ ok: Bool) {
        guard let waiters = openWaiters[serverId], !waiters.isEmpty else { return }
        openWaiters[serverId] = nil
        waiters.forEach { $0(ok) }
    }
}
