import Foundation
import Shared

/// Observable controller around the shared `ScrcpyClient` (H.264 / Android). Mirrors
/// `TerminalSession`: pumps decoded video frames out to a sink, forwards touch/key/text
/// input back as JSON, and reflects connection state. The bitstream is decoded + drawn
/// natively by `ScrcpyVideoView`; this class only bridges flows.
@MainActor
@Observable
final class ScrcpySession {
    enum Status { case connecting, connected, disconnected }

    private let client: ScrcpyClient
    private(set) var status: Status = .disconnected
    /// Video size in device pixels (w, h); nil until the `init` frame arrives.
    private(set) var size: (Int, Int)?

    /// Set by the view: called on the main actor with each frame `(isKey, annexB)`.
    var onFrame: ((Bool, [UInt8]) -> Void)?

    private var tasks: [Task<Void, Never>] = []

    init(broker: BrokerSession, streamId: String) {
        self.client = broker.scrcpyClient(streamId: streamId)
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
            for await pair in self.client.dims {
                // SKIE bridges Kotlin `Pair<Int,Int>?` → `KotlinPair<KotlinInt, KotlinInt>?`.
                if let pair, let w = pair.first?.intValue, let h = pair.second?.intValue {
                    self.size = (Int(w), Int(h))
                } else {
                    self.size = nil
                }
            }
        })
        tasks.append(Task { [weak self] in
            guard let self else { return }
            for await frame in self.client.frames {
                self.onFrame?(frame.isKey, frame.data.toUInt8())
            }
        })
        tasks.append(Task { [weak self] in
            try? await self?.client.run()
        })
    }

    // MARK: - Input (JSON over the same WS, mirrors the web/Android encoders)

    /// `action`: 0 = down, 1 = up, 2 = move. `w`/`h` are the view's pixel dims the
    /// coordinates were taken in; the scrcpy server scales to the device.
    func sendTouch(x: Int, y: Int, action: Int, w: Int, h: Int) {
        send(["type": "touch", "action": action, "x": x, "y": y, "width": w, "height": h])
    }

    func sendText(_ text: String) {
        send(["type": "text", "text": text])
    }

    /// `down`: true → action 0 (down), false → action 1 (up).
    func sendKey(name: String, down: Bool) {
        send(["type": "key", "key": name, "action": down ? 0 : 1])
    }

    private func send(_ payload: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let json = String(data: data, encoding: .utf8) else { return }
        Task { [client] in try? await client.sendInput(json: json) }
    }

    func stop() {
        client.stop()
        tasks.forEach { $0.cancel() }
        tasks = []
        status = .disconnected
    }
}
