//
//  AppletWidgetPictures.swift
//  Supermux
//
//  #165: the pictures in applet widgets, drawn by the app (the widget extension can't run WebKit: about 30 MB). Each
//  applet's widget.html is loaded with no network at all (one self-contained page) at the size of every picture its
//  layouts show, light and dark, and kept as a PNG in the App Group beside the snapshot; the widget shows it with its
//  VoiceOver label, and the block's native "alt" until it exists. It runs when the app sees applets change (the app
//  is in front then): WebKit paints only a view in a window, so the page sits behind the app's own view while drawn.
//
//  App target only.
//

import UIKit
import WebKit
import WidgetKit

@MainActor
final class AppletWidgetPictures: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
    static let shared = AppletWidgetPictures()
    private var running = false
    private var again = false
    private var onLoaded: (() -> Void)?
    private var onReady: (() -> Void)?
    private var blockAll: WKContentRuleList?

    /// The widget's width (points) per size on an iPhone/iPad; a picture's share of it is taken from the layout.
    private static func width(_ s: WidgetSizeKey) -> Double {
        switch s { case .small, .slim, .tall: return 170; case .xlarge: return 715; default: return 364 }
    }

    /// Draws what the widgets need, then asks WidgetKit to redraw; a call while drawing runs once more after.
    func renderIfNeeded() {
        if running { again = true; return }
        running = true
        Task {
            repeat {
                again = false
                await renderAll()
            } while again
            running = false
        }
    }

    private func renderAll() async {
        var drew = false
        for host in AppletSnapshot.hosts() {
            for a in host.applets {
                if await render(host: host.hostId, applet: a) { drew = true }
            }
        }
        if drew { WidgetCenter.shared.reloadAllTimelines() }
    }

    /// True when it wrote new pictures (or dropped old ones).
    private func render(host: String, applet a: WidgetApplet) async -> Bool {
        let doc = a.doc
        let sizes: [WidgetSizeKey] = [.small, .medium, .large, .xlarge]
        let slots = (doc == nil || a.widgetHtml == nil) ? [] : sizes.flatMap { s in doc!.pictureSlots(s).map { (s, $0) } }
        if slots.isEmpty {
            if !(a.pictures ?? [:]).isEmpty { AppletSnapshot.update(host: host, applet: a.name) { $0["pictures"] = [String: String]() }; return true }
            return false
        }
        guard let page = await fetchPage(host: host, path: a.widgetHtml!) else { return false }
        let sig = fnv64(page + "\u{0}" + (a.widget?.json ?? ""))
        let defaults = UserDefaults(suiteName: AppletSnapshot.appGroup)
        let sigKey = "pictures.sig.\(host)/\(a.name)"
        if !(a.pictures ?? [:]).isEmpty, defaults?.string(forKey: sigKey) == sig { return false }
        guard let dir = AppletSnapshot.dir else { return false }
        var out: [String: String] = [:]
        for (size, slot) in slots {
            for dark in [false, true] {
                let w = max(40, (Self.width(size) - 32) * slot.share)
                let h = max(16, slot.height - (slot.caption ? 17 : 0))
                guard let img = await snapshot(page: page, size: size, part: slot.part, w: w, h: h, dark: dark), let png = img.pngData() else { continue }
                let name = widgetPictureFile(host: host, applet: a.name, size: size, part: slot.part, dark: dark)
                try? png.write(to: dir.appendingPathComponent(name), options: .atomic)
                out["\(size.rawValue)/\(slot.part)/\(dark ? "dark" : "light")"] = name
            }
        }
        defaults?.set(sig, forKey: sigKey)
        AppletSnapshot.update(host: host, applet: a.name) { $0["pictures"] = out }
        return true
    }

    private func fetchPage(host: String, path: String) async -> String? {
        guard let c = WidgetHostAccess.credentials(host: host),
              let url = URL(string: c.url.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path) else { return nil }
        var req = URLRequest(url: url, timeoutInterval: 15)
        req.setValue("Bearer \(c.token)", forHTTPHeaderField: "Authorization")
        guard let (data, resp) = try? await URLSession.shared.data(for: req), (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func snapshot(page: String, size: WidgetSizeKey, part: String, w: Double, h: Double, dark: Bool) async -> UIImage? {
        guard let window = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first else { return nil }
        if blockAll == nil {
            // Nothing leaves the phone: every load but the page itself is refused.
            blockAll = try? await WKContentRuleListStore.default().compileContentRuleList(
                forIdentifier: "supermux-widget-offline",
                encodedContentRuleList: #"[{"trigger":{"url-filter":".*"},"action":{"type":"block"}}]"#)
        }
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        if let blockAll { config.userContentController.add(blockAll) }
        config.userContentController.add(self, name: "supermuxWidget")
        config.userContentController.addUserScript(WKUserScript(
            source: "window.supermux = Object.assign(window.supermux || {}, { widgetReady: function () { webkit.messageHandlers.supermuxWidget.postMessage(1) } });",
            injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let view = WKWebView(frame: CGRect(x: 0, y: 0, width: w, height: h), configuration: config)
        view.overrideUserInterfaceStyle = dark ? .dark : .light
        view.isOpaque = false
        view.backgroundColor = .clear
        view.scrollView.isScrollEnabled = false
        view.isUserInteractionEnabled = false
        view.navigationDelegate = self
        // Behind the app's own view: in a window, so WebKit paints it, and never seen.
        window.insertSubview(view, at: 0)
        defer {
            view.removeFromSuperview()
            config.userContentController.removeScriptMessageHandler(forName: "supermuxWidget")
        }
        var q = URLComponents(string: "https://widget.supermux.invalid/widget.html")!
        q.queryItems = [
            URLQueryItem(name: "size", value: size.rawValue), URLQueryItem(name: "w", value: String(Int(w))), URLQueryItem(name: "h", value: String(Int(h))),
            URLQueryItem(name: "scale", value: String(Double(window.screen.scale))), URLQueryItem(name: "theme", value: dark ? "dark" : "light"),
            URLQueryItem(name: "platform", value: "ios"), URLQueryItem(name: "part", value: part),
        ]
        view.loadHTMLString(page, baseURL: q.url)
        // Loaded within 5 s, then its own "drawn" signal or 2 s.
        guard await wait(seconds: 5, { self.onLoaded = $0 }) else { return nil }
        _ = await wait(seconds: 2, { self.onReady = $0 })
        let cfg = WKSnapshotConfiguration()
        cfg.rect = CGRect(x: 0, y: 0, width: w, height: h)
        return try? await view.takeSnapshot(configuration: cfg)
    }

    /// Waits until the callback [set] is given is called, or [seconds]; true when it was called.
    private func wait(seconds: Double, _ set: (@escaping () -> Void) -> Void) async -> Bool {
        let result = await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in
            var done = false
            let finish: (Bool) -> Void = { by in if !done { done = true; c.resume(returning: by) } }
            set { finish(true) }
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { finish(false) }
        }
        onLoaded = nil
        onReady = nil
        return result
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { let f = onLoaded; onLoaded = nil; f?() }

    func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) { let f = onReady; onReady = nil; f?() }
}
