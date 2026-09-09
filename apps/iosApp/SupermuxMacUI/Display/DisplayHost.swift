import SwiftUI
import Shared

/// Owns ONE persistent display stream — the live transport session (VNC or scrcpy WS) plus
/// the native surface (`MTLTexture` framebuffer / `AVSampleBufferDisplayLayer` decoder) that
/// holds the rendered picture. Cached per `stream.id` in `BrokerSession` so the connection
/// AND the on-screen surface survive SwiftUI remounts / Display-pane toggles / session
/// switches. Keeping the SAME backing `UIView` + its renderer coordinator alive is what keeps
/// the stream warm; the surface representables in `DisplayPane` just re-parent that one view.
///
/// Two transports → two host types with parallel shape (each owns session + surface view +
/// renderer coordinator, started once in `init`, torn down only in `stop()`), wrapped in the
/// `DisplayHost` enum so the cache + eviction in `BrokerSession` treat them uniformly.
@MainActor
enum DisplayHost {
    case vnc(VncHost)
    case scrcpy(ScrcpyHost)

    /// Build the right host for a stream's transport, matching `DisplayStreamView`'s branch
    /// (`"h264"` → scrcpy, otherwise VNC). Starts the session immediately.
    static func make(broker: BrokerSession, stream: DisplayStream) -> DisplayHost {
        if stream.transport == "h264" {
            return .scrcpy(ScrcpyHost(broker: broker, streamId: stream.id))
        } else {
            return .vnc(VncHost(broker: broker, streamId: stream.id))
        }
    }

    /// Stop the underlying session + cancel its flows (called on display/session removal).
    func stop() {
        switch self {
        case .vnc(let h): h.stop()
        case .scrcpy(let h): h.stop()
        }
    }
}

// MARK: - VNC

/// Persistent VNC stream: the `VncSession` (RFB websocket) plus the `MetalLayerView` whose
/// `CAMetalLayer` holds the framebuffer texture. `session.onUpdate` is wired ONCE into the
/// renderer coordinator here, so decoded rects keep landing in the same texture across mounts.
@MainActor
final class VncHost {
    let session: VncSession
    /// The persistent backing view (its layer IS the `CAMetalLayer`) — must outlive any mount.
    let view: VncMetalView.MetalLayerView
    /// The renderer that owns the framebuffer texture + CPU mirror; kept warm with the view.
    let coordinator: VncMetalView.Coordinator

    init(broker: BrokerSession, streamId: String) {
        let coord = VncMetalView.Coordinator()
        let v = VncMetalView.MetalLayerView()
        coord.attach(layer: v.metalLayer)
        self.coordinator = coord
        self.view = v

        let session = VncSession(broker: broker, streamId: streamId)
        self.session = session

        // Pump decoded framebuffer rects straight into the renderer. `VncSession` fires
        // `onUpdate` on the main actor (per its contract); `assumeIsolated` reaches the
        // @MainActor coordinator synchronously (we ARE on the main thread) — same idiom the
        // old per-mount `onMakeCoordinator` wiring used. Sync the texture size first so a
        // DesktopSize change (re)allocates before rects land.
        session.onUpdate = { [weak coord, weak session] rects in
            MainActor.assumeIsolated {
                guard let coord else { return }
                if let size = session?.size { coord.resize(width: size.0, height: size.1) }
                coord.applyUpdate(rects)
            }
        }
        session.start()
    }

    func stop() { session.stop() }
}

// MARK: - scrcpy

/// Persistent scrcpy stream: the `ScrcpySession` (H.264 websocket) plus the `SampleBufferView`
/// whose `AVSampleBufferDisplayLayer` owns the VideoToolbox decode + last rendered frame.
/// `session.onFrame` is wired ONCE into the coordinator, so the decoder (SPS/PPS + keyframe
/// state) survives mounts instead of restarting from the next keyframe each time.
@MainActor
final class ScrcpyHost {
    let session: ScrcpySession
    /// The persistent backing view (its layer IS the `AVSampleBufferDisplayLayer`).
    let view: ScrcpyVideoView.SampleBufferView
    /// The decoder feed that owns the format description + keyframe gate; kept warm with the view.
    let coordinator: ScrcpyVideoView.Coordinator

    init(broker: BrokerSession, streamId: String) {
        let coord = ScrcpyVideoView.Coordinator()
        let v = ScrcpyVideoView.SampleBufferView()
        coord.attach(layer: v.displayLayer)
        self.coordinator = coord
        self.view = v

        let session = ScrcpySession(broker: broker, streamId: streamId)
        self.session = session

        // Pump decoded Annex-B access units into the display layer. `ScrcpySession` fires
        // `onFrame` on the main actor (per its contract); `assumeIsolated` reaches the
        // @MainActor coordinator synchronously — same idiom the old per-mount wiring used.
        session.onFrame = { [weak coord] isKey, annexB in
            MainActor.assumeIsolated { coord?.enqueue(isKey: isKey, annexB: annexB) }
        }
        session.start()
    }

    func stop() { session.stop() }
}
