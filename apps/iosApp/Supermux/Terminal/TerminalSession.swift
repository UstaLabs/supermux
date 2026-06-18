import Foundation
import Shared

/// Observable controller around the shared `TerminalClient`. Mirrors how
/// `BrokerSession` consumes Kotlin flows via SKIE (`for await`): pumps pty bytes
/// out to a sink, forwards keystrokes/resize back, and reflects connection state.
@MainActor
@Observable
final class TerminalSession {
    enum Status { case connecting, connected, disconnected }

    private let client: TerminalClient
    private(set) var status: Status = .disconnected

    /// Set by the view: called on the main actor with each pty chunk.
    var onBytes: (([UInt8]) -> Void)?
    /// Set by the view: shell/agent process exited or errored.
    var onExit: (() -> Void)?

    private var tasks: [Task<Void, Never>] = []

    init(broker: BrokerSession, sessionId: String, kind: String, terminalId: String?) {
        self.client = broker.terminalClient(sessionId: sessionId, kind: kind, terminalId: terminalId)
    }

    func start() {
        guard tasks.isEmpty else { return }
        tasks.append(Task { [weak self] in
            guard let self else { return }
            for await s in self.client.status {
                switch s {
                case .connecting: self.status = .connecting
                case .connected: self.status = .connected
                case .disconnected: self.status = .disconnected
                default: self.status = .disconnected
                }
            }
        })
        tasks.append(Task { [weak self] in
            guard let self else { return }
            for await arr in self.client.output {
                self.onBytes?(arr.toUInt8())
            }
        })
        tasks.append(Task { [weak self] in
            guard let self else { return }
            for await _ in self.client.exit { self.onExit?() }
        })
        tasks.append(Task { [weak self] in
            try? await self?.client.run()
        })
    }

    func sendInput(_ bytes: [UInt8]) {
        Task { [client] in try? await client.sendInput(bytes: bytes.toKotlin()) }
    }

    func resize(cols: Int, rows: Int) {
        Task { [client] in try? await client.resize(cols: Int32(cols), rows: Int32(rows)) }
    }

    func stop() {
        client.stop()
        tasks.forEach { $0.cancel() }
        tasks = []
        status = .disconnected
    }
}

// MARK: - KotlinByteArray bridging (verify member names against the generated Shared header)

extension KotlinByteArray {
    func toUInt8() -> [UInt8] {
        let n = Int(size)
        var out = [UInt8](repeating: 0, count: n)
        for i in 0..<n { out[i] = UInt8(bitPattern: get(index: Int32(i))) }
        return out
    }
}

extension Array where Element == UInt8 {
    func toKotlin() -> KotlinByteArray {
        let arr = KotlinByteArray(size: Int32(count))
        for (i, b) in enumerated() { arr.set(index: Int32(i), value: Int8(bitPattern: b)) }
        return arr
    }
}
