import CoreGraphics
import Foundation

/// Pure input-mapping helpers shared by both Display transports (VNC + scrcpy).
/// No UI / no session state — just geometry + keysym tables so they are trivially
/// testable and reused by `DisplayPane` and the management `fullScreenCover` viewer.
enum DisplayInput {

    // MARK: - Letterbox coordinate map

    /// Map a point taken in a view of `viewSize` onto the `remote` framebuffer/device
    /// pixel space, accounting for the aspect-fit (letterbox/pillarbox) the render views
    /// apply. The result is clamped to `[0, w] × [0, h]`. Mirrors the Android touch math
    /// in `DisplayPanel.kt` and the Metal aspect-fit in `VncMetalView`.
    static func mapToRemote(point: CGPoint, viewSize: CGSize, remote: (Int, Int)) -> (x: Int, y: Int) {
        let w = remote.0
        let h = remote.1
        guard w > 0, h > 0, viewSize.width > 0, viewSize.height > 0 else { return (0, 0) }

        // Aspect-fit: the remote image is uniformly scaled to fit, then centered.
        let scale = min(viewSize.width / CGFloat(w), viewSize.height / CGFloat(h))
        guard scale > 0 else { return (0, 0) }
        let dispW = CGFloat(w) * scale
        let dispH = CGFloat(h) * scale
        let offX = (viewSize.width - dispW) / 2
        let offY = (viewSize.height - dispH) / 2

        let rx = Int(((point.x - offX) / scale).rounded())
        let ry = Int(((point.y - offY) / scale).rounded())
        return (x: min(max(rx, 0), w), y: min(max(ry, 0), h))
    }

    // MARK: - VNC keysyms (X11 / RFB KeyEvent)

    /// Special (non-character) key. `enter` is Return/Enter (named to avoid the `return`
    /// keyword). The keysyms below are X11 (RFC 6143 §7.5.4).
    enum SpecialKey {
        case enter, backspace, tab, escape
        case arrowLeft, arrowUp, arrowRight, arrowDown
    }

    /// X11 keysym for a special key, for `VncSession.sendKey(keysym:down:)`.
    static func vncKeysym(forSpecial key: SpecialKey) -> Int64? {
        switch key {
        case .enter: return 0xFF0D
        case .backspace: return 0xFF08
        case .tab: return 0xFF09
        case .escape: return 0xFF1B
        case .arrowLeft: return 0xFF51
        case .arrowUp: return 0xFF52
        case .arrowRight: return 0xFF53
        case .arrowDown: return 0xFF54
        }
    }

    /// X11 keysym for a typed character. For printable ASCII (and Latin-1) the keysym is
    /// the Unicode codepoint itself (RFC 6143 §7.5.4 / the X11 Latin-1 range). Newline /
    /// carriage-return / tab / delete map onto their named keysyms so Return etc. work
    /// when delivered as characters by the text field.
    static func vncKeysym(forCharacter ch: Character) -> Int64? {
        if ch == "\n" || ch == "\r" { return vncKeysym(forSpecial: .enter) }
        if ch == "\t" { return vncKeysym(forSpecial: .tab) }
        // ASCII DEL / Backspace delivered as a character.
        if let s = ch.unicodeScalars.first, s.value == 0x7F || s.value == 0x08 {
            return vncKeysym(forSpecial: .backspace)
        }
        guard let scalar = ch.unicodeScalars.first, ch.unicodeScalars.count == 1 else { return nil }
        // Printable ASCII + Latin-1 supplement map straight to the codepoint.
        if scalar.value >= 0x20 && scalar.value <= 0xFF { return Int64(scalar.value) }
        return nil
    }

    // MARK: - scrcpy key names (Android keycodes by name; mirrors the web/Android encoders)

    /// Android key name for a special key, for `ScrcpySession.sendKey(name:down:)`.
    /// (Printable characters go through `sendText` instead.)
    static func scrcpyKeyName(forSpecial key: SpecialKey) -> String? {
        switch key {
        case .enter: return "Enter"
        case .backspace: return "Backspace"
        case .tab: return "Tab"
        case .escape: return "Escape"
        case .arrowLeft: return "ArrowLeft"
        case .arrowUp: return "ArrowUp"
        case .arrowRight: return "ArrowRight"
        case .arrowDown: return "ArrowDown"
        }
    }
}
