import SwiftUI
import WebKit

/// Owns ONE persistent CodeMirror surface — the `WKWebView` (file:// page + JS bridge)
/// plus the `EditorWebView.Coordinator` that holds the lastPath/lastContent/ready
/// handshake state, is the `editor`/`lsp` script-message handler, and is the nav
/// delegate. Cached per session.id in `BrokerSession` so the live page AND the loaded
/// CodeMirror document survive SwiftUI remounts / editor-pane toggles. Keeping the SAME
/// `WKWebView` alive is what avoids the reload (white flash) — `EditorWebView` just
/// re-parents it into the new mount and pushes per-tab content deltas.
@MainActor
final class EditorHost {
    let webView: WKWebView
    let coordinator: EditorWebView.Coordinator

    /// CodeMirror's one-dark canvas color (#282c34). Painted on the web view and its
    /// scroll view so the file:// page doesn't flash white before first paint.
    private static let editorBackground = PlatformColor(
        red: 0x28 / 255.0, green: 0x2c / 255.0, blue: 0x34 / 255.0, alpha: 1
    )

    init() {
        // Coordinator is the bridge target + nav delegate; it outlives every mount so the
        // ready handshake and the live `editor`/`lsp` message handlers survive remounts.
        // The closures are placeholders here — `EditorWebView.updatePlatformView` refreshes
        // them to the current SwiftUI props on every (re)make.
        let coordinator = EditorWebView.Coordinator(
            onChange: { _ in }, onSave: {}, onLspOut: { _, _ in }
        )
        self.coordinator = coordinator

        // (a) Define the JS->Swift bridge at document start, before cm6.js runs, so
        //     window.AndroidEditor exists when cmInit wires its listeners. Same shape
        //     the Android JavascriptInterface exposes, but routed over postMessage.
        let bridgeJS = """
        window.AndroidEditor = {
          onChange: (s) => window.webkit.messageHandlers.editor.postMessage({ t: 'change', s: s }),
          onSave: () => window.webkit.messageHandlers.editor.postMessage({ t: 'save' }),
          onReady: () => window.webkit.messageHandlers.editor.postMessage({ t: 'ready' })
        };
        """
        let userScript = WKUserScript(
            source: bridgeJS, injectionTime: .atDocumentStart, forMainFrameOnly: true
        )
        let controller = WKUserContentController()
        controller.addUserScript(userScript)
        controller.add(coordinator, name: "editor")
        controller.add(coordinator, name: "lsp")

        let config = WKWebViewConfiguration()
        config.userContentController = controller

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = coordinator
        #if os(iOS)
        webView.isOpaque = false
        webView.backgroundColor = Self.editorBackground
        webView.scrollView.backgroundColor = Self.editorBackground
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        #else
        // macOS: WKWebView is an NSView — no `isOpaque`/`backgroundColor`/`scrollView`.
        // The public `underPageBackgroundColor` paints the same one-dark canvas
        // around the page, and EditorPane's SwiftUI `.background(#282c34)` sits behind
        // the view so there's no white flash before CodeMirror first paints.
        webView.underPageBackgroundColor = Self.editorBackground
        #endif
        #if DEBUG
        if #available(iOS 16.4, macOS 13.3, *) { webView.isInspectable = true }
        #endif
        self.webView = webView
        coordinator.webView = webView

        webView.load(loadRequest: Self.editorPageURL())
    }

    /// Tear down the bridge + handlers. The coordinator is the `editor`/`lsp` message
    /// handler, which WebKit retains; removing the handlers breaks that webView↔handler
    /// retain cycle. Called from `BrokerSession` on `sessionRemoved` (the host is no
    /// longer mounted by anyone, so this is safe). Since the host owns teardown,
    /// `EditorWebView.dismantleUIView` is a no-op — a pane toggle must NOT break the bridge.
    func stop() {
        let controller = webView.configuration.userContentController
        controller.removeScriptMessageHandler(forName: "editor")
        controller.removeScriptMessageHandler(forName: "lsp")
    }

    /// Resolve the bundled editor page. `index.html` + `cm6.js` live under an
    /// `EditorWeb/` folder reference; fall back to the bundle root if flattened.
    private static func editorPageURL() -> URL? {
        Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "EditorWeb")
            ?? Bundle.main.url(forResource: "index", withExtension: "html")
    }
}

private extension WKWebView {
    /// Load the bundled file:// page, granting read access to its directory so the
    /// page can pull in its sibling `cm6.js`. No-op (leaves the #282c34 canvas) if
    /// the resource is missing rather than crashing.
    func load(loadRequest url: URL?) {
        guard let url else { return }
        loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
    }
}
