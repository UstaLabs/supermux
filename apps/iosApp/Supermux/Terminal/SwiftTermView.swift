import SwiftUI
import SwiftTerm

/// Thin SwiftUI host for a PERSISTENT SwiftTerm `TerminalView`. The view (and its
/// emulator buffer + websocket wiring) is owned by a `TerminalHost` cached in
/// `BrokerSession`; this representable only re-parents that one instance into the
/// current mount. It creates nothing and tears nothing down — keeping the same
/// `TerminalView` alive across remounts is what preserves the scrollback.
struct SwiftTermView: UIViewRepresentable {
    let view: TerminalView

    func makeUIView(context: Context) -> TerminalView {
        // Detach from any prior mount before SwiftUI re-parents this cached, reused view.
        // Only one mount of a given terminal exists at a time, so this is normally a no-op;
        // it guards the toggle transition (split ⇄ standalone) from a "view already has a
        // superview" assertion when the old container hasn't been torn down yet.
        view.removeFromSuperview()
        return view
    }

    func updateUIView(_ uiView: TerminalView, context: Context) {}
}
