import Foundation
import Shared

/// Observable controller around the shared `VncClient`. Mirrors `TerminalSession`:
/// pumps decoded framebuffer rects out to a sink, forwards pointer/key input back,
/// and reflects RFB connection state (including the macOS `needsPassword` gate).
/// The pixels are rendered natively by `VncMetalView`; this class only bridges flows.
@MainActor
@Observable
final class VncSession {
    enum Status { case connecting, connected, disconnected, needsPassword }

    private let client: VncClient
    private(set) var status: Status = .disconnected
    /// Framebuffer size in remote pixels (w, h); nil until ServerInit / DesktopSize.
    private(set) var size: (Int, Int)?

    /// Set by the view: called on the main actor with each FramebufferUpdate's rect list.
    var onUpdate: (([VncRect]) -> Void)?

    private var tasks: [Task<Void, Never>] = []

    init(broker: BrokerSession, streamId: String) {
        self.client = broker.vncClient(streamId: streamId)
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
                case .needsPassword: self.status = .needsPassword
                default: self.status = .disconnected
                }
            }
        })
        tasks.append(Task { [weak self] in
            guard let self else { return }
            for await pair in self.client.size {
                // SKIE bridges Kotlin `Pair<Int,Int>?` as `KotlinPair<KotlinInt, KotlinInt>?`;
                // the generic components arrive boxed (`KotlinInt?`) → read `.intValue`.
                if let pair, let w = pair.first?.intValue, let h = pair.second?.intValue {
                    self.size = (Int(w), Int(h))
                } else {
                    self.size = nil
                }
            }
        })
        tasks.append(Task { [weak self] in
            guard let self else { return }
            for await rects in self.client.updates {
                // `updates` is a SharedFlow<List<VncRect>>; SKIE bridges the list to [VncRect].
                self.onUpdate?(rects)
            }
        })
        tasks.append(Task { [weak self] in
            try? await self?.client.run()
        })
    }

    func setPassword(_ pw: String) {
        client.setPassword(pw: pw)
    }

    func sendPointer(x: Int, y: Int, buttonMask: Int) {
        Task { [client] in
            try? await client.sendPointer(x: Int32(x), y: Int32(y), buttonMask: Int32(buttonMask))
        }
    }

    func sendKey(keysym: Int64, down: Bool) {
        Task { [client] in try? await client.sendKey(keysym: keysym, down: down) }
    }

    func sendCtrlAltDel() {
        Task { [client] in try? await client.sendCtrlAltDel() }
    }

    func stop() {
        client.stop()
        tasks.forEach { $0.cancel() }
        tasks = []
        status = .disconnected
    }
}
