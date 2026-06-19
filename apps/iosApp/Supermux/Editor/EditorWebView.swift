import SwiftUI
import WebKit

/// One CodeMirror 6 surface hosted in a `WKWebView`, bridged to the bundled
/// `EditorWeb/index.html` (a copy of the Android `editor/` assets). Mirrors the
/// Android `EditorEngine` contract exactly: Swift drives the page through the
/// `window.cm*` functions and the page calls back through
/// `window.AndroidEditor.{onChange,onSave,onReady}`.
///
/// SwiftUI re-creates the representable whenever a prop changes, so the props are
/// taken as plain `let`/closures and deltas are pushed in `updateUIView`. The
/// underlying `WKWebView` is kept alive across those re-makes via the coordinator,
/// so tab switches reflow instead of reloading (no white flash).
struct EditorWebView: UIViewRepresentable {
    let content: String
    let path: String
    let lineWrap: Bool
    let fontSize: Int
    let onChange: (String) -> Void
    let onSave: () -> Void
    /// Hands the live `WKWebView` up to the parent so it can resign the keyboard
    /// (the web content owns a UIKit keyboard that SwiftUI dismissal can't reach —
    /// mirrors `SwiftTermView.onMakeView` / `TerminalPane`'s dismiss button).
    var onMakeView: (WKWebView) -> Void = { _ in }

    /// CodeMirror's one-dark canvas color (#282c34). Painted on the web view and
    /// its scroll view so the file:// page doesn't flash white before first paint.
    private static let editorBackground = UIColor(
        red: 0x28 / 255.0, green: 0x2c / 255.0, blue: 0x34 / 255.0, alpha: 1
    )

    func makeCoordinator() -> Coordinator {
        Coordinator(onChange: onChange, onSave: onSave)
    }

    func makeUIView(context: Context) -> WKWebView {
        let coordinator = context.coordinator

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

        let config = WKWebViewConfiguration()
        config.userContentController = controller

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = coordinator
        webView.isOpaque = false
        webView.backgroundColor = Self.editorBackground
        webView.scrollView.backgroundColor = Self.editorBackground
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        #if DEBUG
        if #available(iOS 16.4, *) { webView.isInspectable = true }
        #endif

        // Seed the cache so the first cmInit (fired from didFinish) carries the
        // current file, matching Android's lastContent/lastFilename handshake.
        coordinator.lineWrap = lineWrap
        coordinator.fontSize = fontSize
        coordinator.cache(content: content, filename: filename(from: path), scrollTop: 0)
        coordinator.webView = webView

        webView.load(loadRequest: Self.editorPageURL())

        DispatchQueue.main.async { onMakeView(webView) }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        let coordinator = context.coordinator
        // Keep the live callbacks current — SwiftUI hands fresh closures on each
        // re-make; the long-lived coordinator must call the latest ones.
        coordinator.onChange = onChange
        coordinator.onSave = onSave

        let newFilename = filename(from: path)
        let pathChanged = coordinator.lastPath != path

        // Path change = a different tab/file: push content + language together and
        // start that file at the top. (Per-tab scroll restore isn't a prop here —
        // the parent persists the outgoing offset via `readScrollTop` before the
        // switch; there's no incoming scrollTop to push, so we reset to 0.)
        // Content-only change (same path, external edit) just re-pushes the text;
        // cmSetContent is a no-op in JS if the doc is unchanged.
        if pathChanged {
            coordinator.cache(content: content, filename: newFilename, scrollTop: 0)
            coordinator.lastPath = path
            if coordinator.ready {
                coordinator.setContent(content)
                coordinator.setLanguage(newFilename)
                coordinator.setScrollTop(0)
            }
        } else if coordinator.lastContent != content {
            coordinator.lastContent = content
            if coordinator.ready { coordinator.setContent(content) }
        }

        if coordinator.lineWrap != lineWrap {
            coordinator.lineWrap = lineWrap
            if coordinator.ready { coordinator.setLineWrap(lineWrap) }
        }
        if coordinator.fontSize != fontSize {
            coordinator.fontSize = fontSize
            if coordinator.ready { coordinator.setFontSize(fontSize) }
        }
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        // Break the retain cycle WebKit holds on the message handler.
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "editor")
    }

    /// `cm6.js` keys the language off the file's basename (extension), so we only
    /// ever hand it the last path component.
    private func filename(from path: String) -> String {
        (path as NSString).lastPathComponent
    }

    /// Resolve the bundled editor page. Task 1 bundles `index.html` + `cm6.js`
    /// under an `EditorWeb/` folder reference; fall back to the bundle root in
    /// case the resources land flattened.
    private static func editorPageURL() -> URL? {
        Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "EditorWeb")
            ?? Bundle.main.url(forResource: "index", withExtension: "html")
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var onChange: (String) -> Void
        var onSave: () -> Void

        /// Held so the parent can resign the keyboard and so we can reload after a
        /// renderer crash. Not owned (WebKit/SwiftUI own the view).
        weak var webView: WKWebView?

        // Last values handed to the page. Mirror Android's lastContent/lastFilename/
        // lastScrollTop: cached unconditionally, pushed only once `ready`.
        private(set) var ready = false
        var lastPath: String?
        var lastContent = ""
        var lastFilename = ""
        var lastScrollTop = 0
        var lineWrap = true
        var fontSize = 13

        init(onChange: @escaping (String) -> Void, onSave: @escaping () -> Void) {
            self.onChange = onChange
            self.onSave = onSave
        }

        func cache(content: String, filename: String, scrollTop: Int) {
            lastContent = content
            lastFilename = filename
            lastScrollTop = scrollTop
        }

        // MARK: WKNavigationDelegate

        /// The page finished loading: initialise CodeMirror with the cached file.
        /// Same single call Android makes from onPageFinished — strings JSON-quoted,
        /// bool/int raw. `onReady` (a postMessage) flips `ready` and flushes.
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            ready = false
            let js = "cmInit(\(jsString(lastContent)), \(jsString(lastFilename)), "
                + "\(lineWrap ? "true" : "false"), \(fontSize))"
            evaluate(js)
        }

        /// Survive a renderer crash: WebKit drops the content process, so reload the
        /// page and let the ready handshake repaint the cached document (parity with
        /// Android's onRenderProcessGone returning true).
        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            ready = false
            webView.reload()
        }

        // MARK: WKScriptMessageHandler

        func userContentController(_ controller: WKUserContentController,
                                   didReceive message: WKScriptMessage) {
            guard message.name == "editor",
                  let body = message.body as? [String: Any],
                  let t = body["t"] as? String else { return }
            switch t {
            case "change":
                let s = body["s"] as? String ?? ""
                lastContent = s
                onChange(s)
            case "save":
                onSave()
            case "ready":
                ready = true
                pushCachedDocument()
            default:
                break
            }
        }

        // MARK: Swift -> JS

        /// Flush the cached document once the page reports ready — the exact sequence
        /// Android's pushToView uses.
        private func pushCachedDocument() {
            setContent(lastContent)
            setLanguage(lastFilename)
            setLineWrap(lineWrap)
            setFontSize(fontSize)
            setScrollTop(lastScrollTop)
        }

        func setContent(_ s: String) { evaluate("cmSetContent(\(jsString(s)))") }
        func setLanguage(_ filename: String) { evaluate("cmSetLanguage(\(jsString(filename)))") }
        func setLineWrap(_ on: Bool) { evaluate("cmSetLineWrap(\(on ? "true" : "false"))") }
        func setFontSize(_ px: Int) { evaluate("cmSetFontSize(\(px))") }
        func setScrollTop(_ px: Int) { evaluate("cmSetScrollTop(\(px))") }

        /// Read the live scroll offset (NSNumber return) so the parent can persist it
        /// before switching files — companion to Android's readScrollTop.
        func readScrollTop(_ completion: @escaping (Int) -> Void) {
            guard let webView, ready else { return completion(lastScrollTop) }
            webView.evaluateJavaScript("cmGetScrollTop()") { [weak self] result, _ in
                if let n = result as? NSNumber {
                    let top = n.intValue
                    self?.lastScrollTop = top
                    completion(top)
                } else {
                    completion(self?.lastScrollTop ?? 0)
                }
            }
        }

        /// Resign the web content's keyboard. The parent calls this from its own
        /// dismiss affordance (mirrors `TerminalPane` resigning the TerminalView).
        func dismissKeyboard() {
            webView?.endEditing(true)
        }

        private func evaluate(_ js: String) {
            webView?.evaluateJavaScript(js, completionHandler: nil)
        }

        /// Produce a JS string literal (quoted + escaped) for safe interpolation —
        /// the WebKit analogue of Android's `JSONObject.quote`. JSONSerialization
        /// emits a valid JSON string, which is also a valid JS string literal.
        private func jsString(_ s: String) -> String {
            if let data = try? JSONSerialization.data(withJSONObject: [s]),
               let arr = String(data: data, encoding: .utf8) {
                // [ "..." ] -> "..."
                return String(arr.dropFirst().dropLast())
                    .trimmingCharacters(in: .whitespaces)
            }
            return "\"\""
        }
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
