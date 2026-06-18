import SwiftUI
import SwiftTerm

/// Bridges SwiftTerm's UIKit `TerminalView` into SwiftUI and wires it to a
/// `TerminalSession`: incoming pty bytes are fed to the emulator; keystrokes and
/// size changes are sent back through the session.
struct SwiftTermView: UIViewRepresentable {
    let session: TerminalSession

    func makeCoordinator() -> Coordinator { Coordinator(session: session) }

    func makeUIView(context: Context) -> TerminalView {
        let tv = TerminalView(frame: .zero)
        tv.terminalDelegate = context.coordinator
        tv.font = UIFont.monospacedSystemFont(ofSize: 13, weight: .regular)
        tv.nativeBackgroundColor = UIColor(Theme.terminalBackground)
        tv.nativeForegroundColor = UIColor(Theme.terminalForeground)

        // Feed pty output into the emulator on the main actor.
        session.onBytes = { [weak tv] bytes in
            tv?.feed(byteArray: ArraySlice(bytes))
        }
        return tv
    }

    func updateUIView(_ uiView: TerminalView, context: Context) {}

    final class Coordinator: NSObject, TerminalViewDelegate {
        let session: TerminalSession
        init(session: TerminalSession) { self.session = session }

        func send(source: TerminalView, data: ArraySlice<UInt8>) {
            session.sendInput(Array(data))
        }
        func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) {
            session.resize(cols: newCols, rows: newRows)
        }
        // The remaining TerminalViewDelegate requirements — no-ops for our use.
        // (All 10 are required; SwiftTerm's protocol has no default implementations.)
        func setTerminalTitle(source: TerminalView, title: String) {}
        func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
        func scrolled(source: TerminalView, position: Double) {}
        func requestOpenLink(source: TerminalView, link: String, params: [String : String]) {}
        func bell(source: TerminalView) {}
        func clipboardCopy(source: TerminalView, content: Data) {}
        func iTermContent(source: TerminalView, content: ArraySlice<UInt8>) {}
        func rangeChanged(source: TerminalView, startY: Int, endY: Int) {}
    }
}
