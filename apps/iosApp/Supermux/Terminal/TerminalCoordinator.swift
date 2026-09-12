import SwiftUI
import SwiftTerm
// The iOS app links ONE Kotlin framework, SupermuxKit, which re-exports :shared; linking Shared
// as well would embed the :shared klib twice.
import SupermuxKit
import GameController
import UIKit

protocol TerminalIO: AnyObject {
    func sendInput(_ bytes: [UInt8])
    func resize(cols: Int, rows: Int)
}


/// The persistent terminal's delegate + hardware-keyboard policy. Owned by the long-lived
/// `ComposeTerminalHandle` (tied to its `TerminalView`) rather than a per-mount coordinator, so
/// the policy and the FIFO input wiring survive remounts. Plain `NSObject` (not @MainActor)
/// so it satisfies SwiftTerm's nonisolated `TerminalViewDelegate` — the @MainActor io sink
/// is reached via `assumeIsolated` (we ARE on the main thread when SwiftTerm calls us).
final class TerminalCoordinator: NSObject, TerminalViewDelegate {
    let io: TerminalIO
    /// Suppress SwiftTerm's own `TerminalAccessory` toolbar for good. The Compose shell draws the
    /// SHARED `TerminalKeyBar` above the keyboard instead (one bar, three hosts, one sticky-modifier
    /// state machine), so leaving SwiftTerm's would stack two rows of keys over each other.
    private let hostDrawsAccessoryBar: Bool
    private weak var tv: TerminalView?
    private var savedAccessory: UIView?
    private var suppressed = false
    private lazy var emptyInputView = UIView(frame: .zero)
    // Our own one-finger scroll pan (installTouchScroll). Stored so the gesture-recognizer
    // delegate can identify it, and to carry accumulated sub-row drag pixels across callbacks.
    private var scrollPan: UIPanGestureRecognizer?
    private var scrollAccumPx: Double = 0

    // Predictive local echo: the shared Kotlin engine (via SKIE) + the SwiftTerm op
    // renderer + the keystroke→echo RTT stamp. Mirrors TerminalPane.vue's predictor /
    // predAdapter / lastKeyAt. Created in attach() (once the TerminalView exists), cleared
    // in teardownPrediction().
    private var engine: PredictionEngine?
    private var predAdapter: PredictionAdapter?
    // nowMs() of the last keystroke still awaiting its echo (0 = none). Feeds the latency
    // gate from a real keystroke→echo measurement, INDEPENDENTLY of the prediction path —
    // without it the gate could never open (latency starts at 0, predictions need latency ≥
    // threshold, and latency was otherwise only ever learned from confirmed predictions).
    private var lastKeyAt: Int64 = 0

    init(io: TerminalIO, hostDrawsAccessoryBar: Bool = false) {
        self.io = io
        self.hostDrawsAccessoryBar = hostDrawsAccessoryBar
        super.init()
        // A connected hardware keyboard means the on-screen keyboard (soft keys + the
        // terminal accessory toolbar) shouldn't eat the screen — but the terminal stays
        // first responder so HARDWARE keystrokes still reach it. Re-apply on plug/unplug.
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(hardwareKeyboardChanged),
                       name: .GCKeyboardDidConnect, object: nil)
        nc.addObserver(self, selector: #selector(hardwareKeyboardChanged),
                       name: .GCKeyboardDidDisconnect, object: nil)
    }
    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    /// Bind the SwiftTerm view and apply the current keyboard policy.
    func attach(_ terminal: TerminalView) {
        tv = terminal
        if hostDrawsAccessoryBar {
            // Drop SwiftTerm's toolbar and remember NOTHING, so applyKeyboardPolicy's restore path
            // (`inputAccessoryView = savedAccessory`) keeps it suppressed rather than bringing it
            // back when a hardware keyboard is unplugged.
            terminal.inputAccessoryView = nil
            savedAccessory = nil
        } else {
            savedAccessory = terminal.inputAccessoryView   // SwiftTerm's TerminalAccessory (set in its setup)
        }
        // Predictive local echo (mirror TerminalPane.vue onMounted): pure-logic shared engine
        // + SwiftTerm dim-render adapter. The engine owns all reconcile/cursor math; the
        // adapter just translates its ops to SwiftTerm feeds.
        predAdapter = PredictionAdapter(terminal)
        // SKIE boxes a `() -> Long` closure's return (generic position), so it must yield
        // KotlinLong, not Int64. (setLatencyEstimate's Long *parameter* stays Int64 — only the
        // closure return needs this.)
        engine = PredictionEngine(cfg: PredictiveEchoKt.DEFAULT_CONFIG,
                                  now: { KotlinLong(value: TerminalCoordinator.nowMs()) })
        applyKeyboardPolicy()
        installTouchScroll(terminal)
    }

    /// SwiftTerm forwards a one-finger drag to the app as a pressed-button drag (tmux reads it as a
    /// selection, not a scroll), so swiping never scrolls. We add our own one-finger pan that turns
    /// a vertical drag into SGR mouse-wheel bytes sent to the pty — tmux then scrolls its history.
    /// Mirrors the retired Vue PWA's touch-scroll behaviour (retired Vue PWA; see git history before 2026-09-12)
    /// and shares its math (Shared `TerminalScroll.kt`). A gesture-delegate failure requirement (below) makes SwiftTerm's own
    /// pan recognizers yield to ours, so we win the drag WITHOUT disabling them (SwiftTerm toggles
    /// them on mouse-mode changes, which would defeat a one-time disable). Taps / long-press /
    /// double-tap (selection) and pinch (font zoom) are not pans, so they — and click-forwarding to
    /// the TUI — keep working.
    private func installTouchScroll(_ terminal: TerminalView) {
        guard scrollPan == nil else { return }
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handleScrollPan(_:)))
        pan.minimumNumberOfTouches = 1
        pan.maximumNumberOfTouches = 1
        pan.delegate = self
        terminal.addGestureRecognizer(pan)
        scrollPan = pan
    }

    @objc private func handleScrollPan(_ gesture: UIPanGestureRecognizer) {
        guard let tv else { return }
        switch gesture.state {
        case .began:
            scrollAccumPx = 0
        case .changed:
            // Incremental delta since the last callback (then zero it for the next).
            let dy = gesture.translation(in: tv).y
            gesture.setTranslation(.zero, in: tv)
            let terminal = tv.getTerminal()
            let rows = terminal.rows
            let cell = rows > 0 ? Double(tv.bounds.height) / Double(rows) : 0
            guard cell > 0 else { return }
            // finger up (dy < 0) → scroll toward newer output (positive accumulator).
            scrollAccumPx += Double(-dy)
            let step = TerminalScrollKt.linesFromPixels(accumPx: scrollAccumPx, cellHeightPx: cell)
            scrollAccumPx = step.remainderPx
            if step.lines != 0 {
                let cols = terminal.cols
                let col = Int32(cols > 1 ? cols / 2 : 1)
                let row = Int32(rows > 1 ? rows / 2 : 1)
                let bytes = TerminalScrollKt.wheelEventsFromLines(lines: step.lines, col: col, row: row)
                MainActor.assumeIsolated { io.sendInput(bytes.toUInt8()) }
            }
        default:
            break
        }
    }

    @objc private func hardwareKeyboardChanged() { applyKeyboardPolicy() }

    /// Hardware keyboard present → suppress the soft keyboard (empty inputView) and the
    /// accessory toolbar; absent → restore both. Reloads input views live when focused so a
    /// connect/disconnect takes effect immediately. No-op when already in the right state.
    private func applyKeyboardPolicy() {
        guard let tv else { return }
        let hardware = GCKeyboard.coalesced != nil
        if hardware, !suppressed {
            if savedAccessory == nil, !hostDrawsAccessoryBar { savedAccessory = tv.inputAccessoryView }
            tv.inputView = emptyInputView
            tv.inputAccessoryView = nil
            suppressed = true
        } else if !hardware, suppressed {
            tv.inputView = nil
            tv.inputAccessoryView = savedAccessory
            suppressed = false
        } else {
            return
        }
        if tv.isFirstResponder { tv.reloadInputViews() }
    }

    // SwiftTerm invokes these delegate methods on the main thread, but the protocol
    // is nonisolated while `TerminalSession` is @MainActor. Use assumeIsolated (we ARE
    // on the main thread) to call SYNCHRONOUSLY in delivery order — a Task hop here
    // could reorder keystrokes.
    func send(source: TerminalView, data: ArraySlice<UInt8>) {
        let bytes = Array(data)
        MainActor.assumeIsolated {
            // Predictive local echo BEFORE the send (mirror TerminalPane.vue term.onData):
            // show the keystroke instantly + advance the caret, then send as today.
            handleInput(bytes)
            io.sendInput(bytes)
        }
    }
    func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) {
        MainActor.assumeIsolated { io.resize(cols: newCols, rows: newRows) }
    }

    // MARK: - Predictive local echo (mirror TerminalPane.vue's input / output handlers)

    /// INPUT: decode the keystroke, render the engine's ops, then stamp the RTT clock.
    /// Called from send(...) inside the main-actor block, BEFORE the bytes reach the pty.
    ///
    /// Skipped entirely while a host key-bar modifier is armed (`modsArmed`): Kotlin re-encodes
    /// that keystroke as a control code on the way through, so echoing the LETTER would paint a
    /// glyph the server never sends. Android skips it on the same condition (web parity).
    private func handleInput(_ bytes: [UInt8]) {
        guard !modsArmed, let engine, let predAdapter else { return }
        let str = String(decoding: bytes, as: UTF8.self)
        predAdapter.render(engine.onInput(ev: PredictiveEchoKt.decodeInput(data: str),
                                          serverCursor: predAdapter.cursor()))
        lastKeyAt = TerminalCoordinator.nowMs()   // mark for the keystroke→echo RTT
    }

    /// OUTPUT: bootstrap the latency estimate from the keystroke→echo RTT, then let the
    /// engine reconcile and re-emit the server bytes via its ops. There is NO separate
    /// tv.feed — the Passthrough op carries the bytes (web parity). Only after teardown
    /// (engine gone) do we feed the bytes directly. Runs on the main actor.
    func handleOutput(_ bytes: KotlinByteArray) {
        // Runs on the MainActor today (invoked from onBytes' @MainActor Task). The engine is
        // NOT thread-safe, so assert isolation explicitly (same guard as send / sizeChanged) —
        // a future off-main caller then traps loudly instead of silently racing.
        MainActor.assumeIsolated {
            guard let engine, let predAdapter else {
                // Teardown / no engine: the ONE conversion needed for a direct feed.
                tv?.feed(byteArray: ArraySlice(bytes.toUInt8()))
                return
            }
            if lastKeyAt > 0 {
                engine.setLatencyEstimate(ms: TerminalCoordinator.nowMs() - lastKeyAt)
                lastKeyAt = 0
            }
            // Pass the raw KotlinByteArray straight to the engine (no round-trip bridge); it
            // re-emits the bytes inside a Passthrough op, where the adapter does the single
            // KotlinByteArray→[UInt8] conversion the SwiftTerm feed actually needs.
            predAdapter.render(engine.onServerData(bytes: bytes))
        }
    }

    /// Whether the host's shared key bar currently has Ctrl or Alt armed. Set from Kotlin through
    /// `IosTerminalHandle.setMods`; read only by `handleInput`.
    var modsArmed = false

    /// Drop the engine + adapter (teardown). Later output falls back to a direct feed.
    func teardownPrediction() {
        engine = nil
        predAdapter = nil
        lastKeyAt = 0
    }

    /// Monotonic millisecond clock (does not jump on wall-clock changes) — the iOS twin of
    /// the web's performance.now(). Drives the engine timing AND the keystroke→echo RTT, so
    /// both measurements share one source.
    private static func nowMs() -> Int64 {
        Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
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

extension TerminalCoordinator: UIGestureRecognizerDelegate {
    /// Make SwiftTerm's own pan recognizers (mouse-drag / selection) yield to ours: they are
    /// required to fail when our scroll pan recognizes, so a vertical drag scrolls instead of
    /// selecting. Only gates other PAN recognizers — taps and pinch are untouched.
    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        gestureRecognizer == scrollPan
            && otherGestureRecognizer !== scrollPan
            && otherGestureRecognizer is UIPanGestureRecognizer
    }
}
