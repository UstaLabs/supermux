#if os(macOS)
import AppKit

/// Headless feel-test eyes: `SM_SNAPSHOT=1` writes a PNG of every visible window to
/// `<app home>/Snapshots/` every 2 s (overwritten in place), so an agent driving the
/// app over SSH can *see* it. Renders via AppKit view-hierarchy caching — the only
/// headless-safe path: `screencapture`/ScreenCaptureKit need a Screen Recording TCC
/// grant an SSH context can't give, and `CGWindowListCreateImage` is unavailable in
/// the macOS 26 SDK. Materials/vibrancy render as approximations (no behind-window
/// compositing); everything else — layout, type, color, spacing — is faithful. Inert
/// without the env var — debug tooling, same family as the SM_OPEN_*/SM_IPAD_* hooks.
@MainActor
enum DebugSnapshot {
    static func startIfEnabled() {
        guard ProcessInfo.processInfo.environment["SM_SNAPSHOT"] == "1" else { return }
        let dir = URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Snapshots", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { _ in
            Task { @MainActor in capture(into: dir) }
        }
    }

    private static func capture(into dir: URL) {
        var lines: [String] = []
        for (i, w) in NSApp.windows.enumerated() {
            guard w.isVisible, w.frame.width > 50 else { continue }
            guard let png = windowPNG(w) else { continue }
            try? png.write(to: dir.appendingPathComponent("win-\(i).png"), options: .atomic)
            lines.append("win-\(i).png\t\(Int(w.frame.width))x\(Int(w.frame.height))\t\(w.title)")
        }
        let meta = "\(Date().timeIntervalSince1970)\n" + lines.joined(separator: "\n") + "\n"
        try? meta.write(to: dir.appendingPathComponent("meta.txt"), atomically: true, encoding: .utf8)
    }

    /// The window's frame view (titlebar included) rendered through the view hierarchy.
    private static func windowPNG(_ w: NSWindow) -> Data? {
        guard let frameView = w.contentView?.superview,
              let rep = frameView.bitmapImageRepForCachingDisplay(in: frameView.bounds) else { return nil }
        frameView.cacheDisplay(in: frameView.bounds, to: rep)
        return rep.representation(using: .png, properties: [:])
    }
}
#endif
