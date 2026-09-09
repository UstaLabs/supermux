import Foundation
#if COMPOSE_SHELL
import SupermuxKit
#else
import Shared
#endif

/// Observable controller around the shared `TerminalClient`. Mirrors how
/// `BrokerSession` consumes Kotlin flows via SKIE (`for await`): pumps pty bytes
/// out to a sink, forwards keystrokes/resize back, and reflects connection state.
@MainActor
@Observable
final class TerminalSession {
    enum Status { case connecting, connected, disconnected }

    private let client: TerminalClient
    private(set) var status: Status = .disconnected

    /// Set by the view: called on the main actor with each pty chunk, as the raw
    /// KotlinByteArray — no per-element bridge here. The predictive-echo consumer passes it
    /// straight to the engine (zero conversions); the teardown fallback converts once if needed.
    var onBytes: ((KotlinByteArray) -> Void)?
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
                self.onBytes?(arr)
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
        // client.sendInput is a non-suspending FIFO enqueue — call it directly (no
        // Task) so keystrokes keep the order SwiftTerm delivered them.
        client.sendInput(bytes: bytes.toKotlin())
    }

    func resize(cols: Int, rows: Int) {
        Task { [client] in try? await client.resize(cols: Int32(cols), rows: Int32(rows)) }
    }

    func focus(_ focused: Bool) {
        Task { [client] in try? await client.focus(isFocused: focused) }
    }

    func stop() {
        client.stop()
        tasks.forEach { $0.cancel() }
        tasks = []
        status = .disconnected
    }
}

// MARK: - KotlinByteArray bridging
//
// Both directions hop through `NSData`, which is a memcpy on the Kotlin side and a bulk copy on the
// Swift side. The obvious implementation — `get(index:)` / `set(index:value:)` in a loop — is ONE
// Objective-C message per byte, and this is the terminal's hot path in both directions: every pty
// chunk out (a `cat` of a big file is megabytes) and every keystroke and mouse-wheel burst in.
//
// `NSDataBytesKt` lives in `:shared`, so these compile against Shared.framework (the macOS target)
// and SupermuxKit alike; `:ios`'s `IosBytesKt` would not.

extension KotlinByteArray {
    func toUInt8() -> [UInt8] {
        [UInt8](NSDataBytesKt.nsDataOf(bytes: self) as Data)
    }
}

extension Array where Element == UInt8 {
    func toKotlin() -> KotlinByteArray {
        NSDataBytesKt.toByteArray(Data(self))
    }
}
