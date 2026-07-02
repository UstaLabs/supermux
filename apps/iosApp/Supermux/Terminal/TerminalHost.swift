import SwiftUI
import SwiftTerm
import Shared
#if canImport(UIKit)
import GameController
import UIKit
#else
import AppKit
#endif

/// Owns ONE persistent terminal — the `TerminalSession` (websocket) plus the SwiftTerm
/// `TerminalView` that holds the emulator buffer. Cached per (session, kind, terminalId)
/// in `BrokerSession` so the live connection AND on-screen scrollback survive SwiftUI
/// remounts / pane toggles. Keeping the SAME `TerminalView` instance alive is what
/// preserves the scrollback; `SwiftTermView` just re-parents it into the new mount.
@MainActor
final class TerminalHost {
    let session: TerminalSession
    let view: TerminalView

    private let delegate: TerminalCoordinator

    init(broker: BrokerSession, sessionId: String, kind: String, terminalId: String?) {
        // Build the persistent emulator view (the setup that used to live in
        // SwiftTermView.makeUIView). This instance must outlive any single mount.
        let tv = TerminalView(frame: .zero)
        tv.font = PlatformFont.monospacedSystemFont(ofSize: 13, weight: .regular)
        tv.nativeBackgroundColor = PlatformColor(Theme.terminalBackground)
        tv.nativeForegroundColor = PlatformColor(Theme.terminalForeground)
        self.view = tv

        let session = TerminalSession(broker: broker, sessionId: sessionId,
                                      kind: kind, terminalId: terminalId)
        self.session = session

        // Coordinator owns the terminal-view delegate (send/sizeChanged → session), the
        // hardware-keyboard policy, AND the predictive-echo pipeline (engine + adapter),
        // wired once to the persistent view.
        let coordinator = TerminalCoordinator(session: session)
        self.delegate = coordinator
        tv.terminalDelegate = coordinator
        coordinator.attach(tv)

        // Feed pty output through the predictive-echo pipeline on the main actor. The engine
        // reconciles predictions against the authoritative bytes and re-emits them inside a
        // Passthrough op, so there is NO separate tv.feed here — handleOutput does all the
        // writing (mirrors the web output handler that dropped its separate term.write).
        session.onBytes = { [weak coordinator] bytes in
            coordinator?.handleOutput(bytes)
        }
        session.start()
    }

    func stop() {
        session.stop()
        delegate.teardownPrediction()   // drop the engine + adapter (web parity: predictor = null)
    }
}

/// The persistent terminal's delegate + hardware-keyboard policy. Lives in `TerminalHost`
/// (tied to the long-lived `TerminalView`) rather than a per-mount SwiftUI coordinator, so
/// the policy and the FIFO input wiring survive remounts. Plain `NSObject` (not @MainActor)
/// so it satisfies SwiftTerm's nonisolated `TerminalViewDelegate` — the @MainActor session
/// is reached via `assumeIsolated` (we ARE on the main thread when SwiftTerm calls us).
final class TerminalCoordinator: NSObject, TerminalViewDelegate {
    let session: TerminalSession
    private weak var tv: TerminalView?
    #if os(iOS)
    private var savedAccessory: UIView?
    private var suppressed = false
    private lazy var emptyInputView = UIView(frame: .zero)
    // Our own one-finger scroll pan (installTouchScroll). Stored so the gesture-recognizer
    // delegate can identify it, and to carry accumulated sub-row drag pixels across callbacks.
    private var scrollPan: UIPanGestureRecognizer?
    private var scrollAccumPx: Double = 0
    #endif
    #if os(macOS)
    // macOS wheel/trackpad → scrollback bridge (the AppKit analog of installTouchScroll): a
    // local scroll-wheel monitor scoped to this TerminalView, plus the same carried sub-row
    // accumulator the iOS pan uses. Removed in deinit.
    private var scrollMonitor: Any?
    private var scrollAccumPx: Double = 0
    #endif

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

    init(session: TerminalSession) {
        self.session = session
        super.init()
        #if os(iOS)
        // A connected hardware keyboard means the on-screen keyboard (soft keys + the
        // terminal accessory toolbar) shouldn't eat the screen — but the terminal stays
        // first responder so HARDWARE keystrokes still reach it. Re-apply on plug/unplug.
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(hardwareKeyboardChanged),
                       name: .GCKeyboardDidConnect, object: nil)
        nc.addObserver(self, selector: #selector(hardwareKeyboardChanged),
                       name: .GCKeyboardDidDisconnect, object: nil)
        #endif
    }
    deinit {
        NotificationCenter.default.removeObserver(self)
        #if os(macOS)
        if let scrollMonitor { NSEvent.removeMonitor(scrollMonitor) }
        #endif
    }

    /// Bind the SwiftTerm view and apply the current keyboard policy.
    func attach(_ terminal: TerminalView) {
        tv = terminal
        #if os(iOS)
        savedAccessory = terminal.inputAccessoryView   // SwiftTerm's TerminalAccessory (set in its setup)
        #endif
        // Predictive local echo (mirror TerminalPane.vue onMounted): pure-logic shared engine
        // + SwiftTerm dim-render adapter. The engine owns all reconcile/cursor math; the
        // adapter just translates its ops to SwiftTerm feeds.
        predAdapter = PredictionAdapter(terminal)
        // SKIE boxes a `() -> Long` closure's return (generic position), so it must yield
        // KotlinLong, not Int64. (setLatencyEstimate's Long *parameter* stays Int64 — only the
        // closure return needs this.)
        engine = PredictionEngine(cfg: PredictiveEchoKt.DEFAULT_CONFIG,
                                  now: { KotlinLong(value: TerminalCoordinator.nowMs()) })
        #if os(iOS)
        applyKeyboardPolicy()
        installTouchScroll(terminal)
        #endif
        #if os(macOS)
        installWheelScroll(terminal)
        #endif
    }

    #if os(iOS)
    /// SwiftTerm forwards a one-finger drag to the app as a pressed-button drag (tmux reads it as a
    /// selection, not a scroll), so swiping never scrolls. We add our own one-finger pan that turns
    /// a vertical drag into SGR mouse-wheel bytes sent to the pty — tmux then scrolls its history.
    /// Mirrors the web PWA (src/web-app/src/lib/touch-scroll.ts) and shares its math (Shared
    /// `TerminalScroll.kt`). A gesture-delegate failure requirement (below) makes SwiftTerm's own
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
                MainActor.assumeIsolated { session.sendInput(bytes.toUInt8()) }
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
            if savedAccessory == nil { savedAccessory = tv.inputAccessoryView }
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
    #endif

    #if os(macOS)
    /// SwiftTerm's AppKit `scrollWheel` only nudges its LOCAL scrollback, which is empty in the
    /// tmux alt-screen + `mouse on` config both terminal kinds use — so a trackpad/wheel scroll
    /// over the pane does nothing. This is the macOS analog of iOS's `installTouchScroll`: a local
    /// scroll-wheel monitor, SCOPED to this TerminalView, that turns vertical wheel deltas into
    /// SGR mouse-wheel bytes sent to the pty (tmux then scrolls its own history). Shares the SAME
    /// shared `TerminalScrollKt` math and the SAME `scrollAccumPx` accumulation + remainder carry
    /// as the iOS pan, so both native platforms drive identical, tested scroll logic.
    private func installWheelScroll(_ terminal: TerminalView) {
        guard scrollMonitor == nil else { return }
        scrollMonitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self, weak terminal] event in
            guard let self, let terminal, let window = terminal.window,
                  event.window === window else { return event }
            // Only OUR view: the pointer must be inside the terminal's bounds.
            let p = terminal.convert(event.locationInWindow, from: nil)
            guard terminal.bounds.contains(p) else { return event }
            self.handleScrollWheel(event, terminal)
            return nil   // consume — SwiftTerm's own (inert) scrollWheel must not also fire
        }
    }

    private func handleScrollWheel(_ event: NSEvent, _ terminal: TerminalView) {
        let core = terminal.getTerminal()
        let rows = core.rows
        let cell = rows > 0 ? Double(terminal.bounds.height) / Double(rows) : 0
        guard cell > 0 else { return }
        // Per-event vertical delta in points. Precise deltas (trackpad / Magic Mouse) are already
        // in points; a classic wheel notch is line-based, so scale it to points by the cell height.
        // `scrollingDeltaY` already carries the user's natural-scroll direction preference in its
        // sign, so we forward it faithfully.
        let dy = event.hasPreciseScrollingDeltas
            ? Double(event.scrollingDeltaY)
            : Double(event.scrollingDeltaY) * cell
        guard dy != 0 else { return }
        // Mirror iOS installTouchScroll EXACTLY (`+= -dy`): a content-down gesture (positive delta)
        // scrolls back into history, a content-up gesture scrolls toward newer output. If interactive
        // testing shows the direction inverted, flip this single sign to `+= dy`.
        scrollAccumPx += -dy
        let step = TerminalScrollKt.linesFromPixels(accumPx: scrollAccumPx, cellHeightPx: cell)
        scrollAccumPx = step.remainderPx
        guard step.lines != 0 else { return }
        let cols = core.cols
        let col = Int32(cols > 1 ? cols / 2 : 1)
        let row = Int32(rows > 1 ? rows / 2 : 1)
        let bytes = TerminalScrollKt.wheelEventsFromLines(lines: step.lines, col: col, row: row)
        MainActor.assumeIsolated { session.sendInput(bytes.toUInt8()) }
    }
    #endif

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
            session.sendInput(bytes)
        }
    }
    func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) {
        MainActor.assumeIsolated { session.resize(cols: newCols, rows: newRows) }
    }

    // MARK: - Predictive local echo (mirror TerminalPane.vue's input / output handlers)

    /// INPUT: decode the keystroke, render the engine's ops, then stamp the RTT clock.
    /// Called from send(...) inside the main-actor block, BEFORE the bytes reach the pty.
    private func handleInput(_ bytes: [UInt8]) {
        guard let engine, let predAdapter else { return }
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

#if os(iOS)
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
#endif
