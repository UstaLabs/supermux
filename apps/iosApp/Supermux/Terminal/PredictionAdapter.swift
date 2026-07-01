import SwiftTerm
import Shared

/// Renders the shared `PredictionEngine`'s Step-2 `DisplayOp`s against a SwiftTerm
/// `TerminalView`. The engine (shared Kotlin, reached via SKIE) owns ALL reconcile
/// logic and cursor math; this adapter is the thin, mechanical translator — the iOS
/// twin of the web `xterm-adapter.ts`. It feeds the SAME ANSI escapes the web adapter
/// writes to xterm (SwiftTerm parses them identically), so the two platforms render
/// predictions the same way.
///
/// The adapter owns the pre-prediction cell snapshots: it captures a cell's prior
/// character on `DrawDim` (keyed by prediction id, captured BEFORE the dim write) and
/// restores it on `RestoreCell`. Confirmed predictions never emit a `RestoreCell` (their
/// echo paints over the dim cell via a `Passthrough`), so the snapshot map would grow
/// unbounded; it is capped by eviction — the cap (64) sits above the engine's maxPending
/// (50), so a live snapshot is never wrongly evicted.
final class PredictionAdapter {
    private let tv: TerminalView
    /// prediction id → the character that occupied the cell before the dim glyph.
    private var snapshots: [Int32: String] = [:]

    init(_ tv: TerminalView) { self.tv = tv }

    /// Current caret position, viewport-relative (matches CUP coordinates). SwiftTerm's
    /// `getCursorLocation()` returns `(x: col, y: row)`, zero-based relative to the visible
    /// display — the same coordinate space as the web adapter's `buffer.active` cursor.
    func cursor() -> CursorPos {
        let loc = tv.getTerminal().getCursorLocation()
        return CursorPos(row: Int32(loc.y), col: Int32(loc.x))
    }

    /// Render a batch of engine ops. SwiftTerm coalesces the feeds issued in one turn into
    /// a single paint, so a whole op batch lands in one frame with no intermediate caret
    /// flicker (the engine also brackets reconcile batches with Hide/ShowCaret ops).
    func render(_ ops: [DisplayOp]) {
        for op in ops {
            // SKIE turns the Kotlin sealed `DisplayOp` into an exhaustive Swift switch via
            // onEnum(of:) — the exact pattern the app uses for `ServerFrame` in BrokerSession.
            switch onEnum(of: op) {
            case .hideCaret:
                feed(Self.hide)
            case .showCaret:
                feed(Self.show)
            case .moveCaret(let o):
                feed(Self.cup(o.row, o.col))
            case .drawDim(let o):
                // Snapshot the pre-prediction cell BEFORE the dim write (mirror web order).
                snapshots[o.id] = readCell(o.row, o.col)
                evictIfNeeded()
                feed(Self.cup(o.row, o.col) + Self.dim + o.char + Self.undim)
            case .restoreCell(let o):
                let prev = snapshots[o.id] ?? " "
                snapshots[o.id] = nil
                feed(Self.cup(o.row, o.col) + prev)
            case .passthrough(let o):
                // Authoritative server bytes, written as-is (lossless, NOT via feed(text:)
                // — mid-stream chunks need not be valid UTF-8). Confirmed echoes paint over
                // their dim cells here — that IS the confirm.
                tv.feed(byteArray: ArraySlice(o.bytes.toUInt8()))
            }
        }
    }

    // MARK: - Escapes (identical to the web adapter's)

    private static let dim = "\u{1b}[2m"
    private static let undim = "\u{1b}[22m"   // un-dim only (not [0m) — preserves other cell attrs
    private static let hide = "\u{1b}[?25l"
    private static let show = "\u{1b}[?25h"
    /// Absolute cursor position (CUP). Row/col are 0-based here, 1-based in the escape.
    private static func cup(_ row: Int32, _ col: Int32) -> String { "\u{1b}[\(row + 1);\(col + 1)H" }

    /// Feed an escape/text string to SwiftTerm (parsed exactly like xterm's `term.write`).
    private func feed(_ s: String) { tv.feed(text: s) }

    /// Read the character in a cell for the snapshot. SwiftTerm's `getCharacter(col:row:)`
    /// is zero-based but resolves relative to the SCROLLED display (`yDisp`), whereas our
    /// `DrawDim`/CUP paint the active screen (`yBase`). These coincide whenever the view sits
    /// at the bottom (`yDisp == yBase`) — which is ALWAYS the case under tmux's mouse-on
    /// alternate buffer (no local scrollback), the default for both terminal kinds. The only
    /// gap: a PLAIN-shell session scrolled up into local history *mid-prediction* would snapshot
    /// a wrong (visible-but-not-cursor) cell, so a rollback may repaint a stale glyph until the
    /// next server redraw — the iOS analogue of the web adapter's accepted "viewport scroll
    /// between predict and restore" caveat; self-heals. An empty/unwritten cell reads back as
    /// NUL; treat NUL (and out-of-bounds nil) as a space, mirroring the web's `|| " "`.
    private func readCell(_ row: Int32, _ col: Int32) -> String {
        guard let ch = tv.getTerminal().getCharacter(col: Int(col), row: Int(row)), ch != "\0" else {
            return " "
        }
        return String(ch)
    }

    /// Keep the snapshot map bounded. Confirmed predictions never emit `RestoreCell`, so
    /// their snapshots would otherwise linger. Engine ids are monotonic, so the smallest
    /// key is the oldest snapshot — evicting it mirrors the web adapter's insertion-order
    /// "drop the oldest". The cap (64) sits above the engine's maxPending (50), so a live
    /// snapshot (id among the outstanding ≤50) is never the smallest, hence never evicted.
    private func evictIfNeeded() {
        guard snapshots.count > 64 else { return }
        if let oldest = snapshots.keys.min() { snapshots[oldest] = nil }
    }
}
