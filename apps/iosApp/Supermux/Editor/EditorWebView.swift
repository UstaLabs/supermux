import SwiftUI
import WebKit

/// Thin SwiftUI host for a PERSISTENT CodeMirror `WKWebView`. The view (and its
/// loaded file:// page, JS bridge, ready handshake + lastPath/lastContent state) is
/// owned by an `EditorHost` cached in `BrokerSession`; this representable only
/// re-parents that one instance into the current mount and pushes per-tab deltas in
/// `updateUIView`. It builds nothing and tears nothing down — keeping the same
/// `WKWebView` + `Coordinator` alive across remounts is what makes tab switches AND
/// editor-pane toggles reflow instead of reloading (no white flash).
///
/// Bridged to the bundled `EditorWeb/index.html` (a copy of the Android `editor/`
/// assets). Mirrors the Android `EditorEngine` contract: Swift drives the page through
/// the `window.cm*` functions and the page calls back through
/// `window.AndroidEditor.{onChange,onSave,onReady}`. SwiftUI re-creates the
/// representable whenever a prop changes, so the props are plain `let`/closures and
/// deltas are pushed in `updateUIView`.
struct EditorWebView: UIViewRepresentable {
    /// The persistent webview + coordinator, owned by `BrokerSession`'s editor cache.
    let host: EditorHost
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
    /// JS->Swift LSP relay: the webview's CodeMirror LSP client posts outbound
    /// JSON-RPC `{serverId, message}` here; the parent forwards it to the broker.
    var onLspOut: (_ serverId: String, _ message: String) -> Void = { _, _ in }

    /// Reuse the host's long-lived coordinator (the bridge target + nav delegate +
    /// ready/lastPath/lastContent state), so remounts don't reset the handshake.
    func makeCoordinator() -> Coordinator { host.coordinator }

    func makeUIView(context: Context) -> WKWebView {
        // Detach from any prior mount before SwiftUI re-parents this cached, reused
        // webview. Only one mount of a given editor exists at a time, so this is
        // normally a no-op; it guards the toggle transition (split ⇄ standalone) from
        // a "view already has a superview" assertion when the old container hasn't been
        // torn down yet (mirrors `SwiftTermView.makeUIView`). NO construction here —
        // the `EditorHost` built the webview + bridge + handlers + file:// load once.
        host.webView.removeFromSuperview()
        DispatchQueue.main.async { onMakeView(host.webView) }
        return host.webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        let coordinator = context.coordinator
        // Keep the live callbacks current — SwiftUI hands fresh closures on each
        // re-make; the long-lived coordinator must call the latest ones.
        coordinator.onChange = onChange
        coordinator.onSave = onSave
        coordinator.onLspOut = onLspOut

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
        // No-op: the webview + coordinator are owned by the `EditorHost` cached in
        // `BrokerSession` and MUST outlive this mount, so toggling the editor pane off
        // doesn't reload the page. The script-message handlers (the webView↔coordinator
        // retain cycle) are removed by `EditorHost.stop()` when the SESSION is removed.
    }

    /// `cm6.js` keys the language off the file's basename (extension), so we only
    /// ever hand it the last path component.
    private func filename(from path: String) -> String {
        (path as NSString).lastPathComponent
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var onChange: (String) -> Void
        var onSave: () -> Void
        var onLspOut: (String, String) -> Void

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

        init(onChange: @escaping (String) -> Void, onSave: @escaping () -> Void,
             onLspOut: @escaping (String, String) -> Void) {
            self.onChange = onChange
            self.onSave = onSave
            self.onLspOut = onLspOut
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
            if message.name == "lsp" {
                handleLspOut(message.body)
                return
            }
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

        /// The webview LSP client posts `JSON.stringify({serverId, message})` to the `lsp`
        /// handler; parse it and hand the outbound JSON-RPC to the parent relay.
        private func handleLspOut(_ body: Any) {
            guard let raw = body as? String,
                  let data = raw.data(using: .utf8),
                  let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let serverId = obj["serverId"] as? String,
                  let msg = obj["message"] as? String else { return }
            onLspOut(serverId, msg)
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
