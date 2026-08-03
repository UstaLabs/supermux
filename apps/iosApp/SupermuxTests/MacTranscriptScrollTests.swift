#if os(macOS)
import AppKit
import SwiftUI
import XCTest
@testable import Supermux

@MainActor
final class MacTranscriptScrollTests: XCTestCase {
    private final class ProbeStore {
        var made: Set<Int> = []
        weak var newestView: NSView?
        weak var bottomView: NSView?
        weak var floatingView: NSView?
    }

    private struct ProbeRow: NSViewRepresentable {
        let id: Int
        let isNewest: Bool
        let store: ProbeStore

        func makeNSView(context: Context) -> NSView {
            let view = NSView(frame: .zero)
            store.made.insert(id)
            if isNewest { store.newestView = view }
            return view
        }

        func updateNSView(_ nsView: NSView, context: Context) {}
    }

    private struct BottomProbe: NSViewRepresentable {
        let store: ProbeStore

        func makeNSView(context: Context) -> NSView {
            let view = NSView(frame: .zero)
            store.bottomView = view
            return view
        }

        func updateNSView(_ nsView: NSView, context: Context) {}
    }

    /// Stands in for the jump-to-bottom button, so its *placement* can be measured. Carries the
    /// same `macTranscriptFloatingControl()` rule the real button uses.
    private struct FloatingProbe: NSViewRepresentable {
        let store: ProbeStore

        func makeNSView(context: Context) -> NSView {
            let view = NSView(frame: .zero)
            store.floatingView = view
            return view
        }

        func updateNSView(_ nsView: NSView, context: Context) {}
    }

    private struct Harness: View {
        let store: ProbeStore
        var total = 240
        var tallNewest = false
        /// Height of a stand-in composer dock, attached exactly as `ChatPane` attaches the real
        /// one. 0 = no dock (the scroll-behaviour cases don't need it).
        var composerInset: CGFloat = 0
        /// Matches `SessionTranscript.macEagerTailCount` — newest rows stay non-lazy.
        private let eagerTail = 8

        var body: some View {
            // Mirrors SessionTranscript's mac layout: lazy history + small eager tail + sentinel.
            // Pure LazyVStack alone blanks on real markdown heights; this landing zone is required.
            MacTranscriptScrollView {
                let eagerStart = max(0, total - eagerTail)
                LazyVStack(spacing: 0) {
                    ForEach(0..<eagerStart, id: \.self) { id in
                        ProbeRow(id: id, isNewest: id == total - 1, store: store)
                            .frame(height: CGFloat(28 + (id % 7) * 19))
                    }
                }
                VStack(spacing: 0) {
                    ForEach(eagerStart..<total, id: \.self) { id in
                        ProbeRow(id: id, isNewest: id == total - 1, store: store)
                            .frame(height: tallNewest && id == total - 1
                                ? 2_000
                                : CGFloat(28 + (id % 7) * 19))
                    }
                    BottomProbe(store: store).frame(height: 1).id("__bottom__")
                }
            }
            // Mirrors the real geometry: the floating control is overlaid on the scroller, and the
            // composer dock is inset OUTSIDE it (`ChatPane.body`) — so the scroll view's frame runs
            // under the dock and the overlay has to compensate.
            .overlay(alignment: .bottom) {
                FloatingProbe(store: store)
                    .frame(width: 30, height: 30)
                    .macTranscriptFloatingControl()
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                Color.clear.frame(height: composerInset)
            }
            .frame(width: 700, height: 500)
        }
    }

    func testFollowModeHysteresis() {
        XCTAssertTrue(macTranscriptFollowingState(current: false, distanceFromBottom: 20),
                      "arriving at the live edge must engage following")
        XCTAssertFalse(macTranscriptFollowingState(current: true, distanceFromBottom: 2_000),
                       "scrolling away from the bottom must stop following")
        XCTAssertTrue(macTranscriptFollowingState(current: true, distanceFromBottom: 70),
                      "the hysteresis band must preserve a following viewport")
        XCTAssertFalse(macTranscriptFollowingState(current: false, distanceFromBottom: 70),
                       "the hysteresis band must preserve a released viewport")
    }

    /// The eager tail must stay put while a chat is live (no reparent per append) *and* stay
    /// bounded, so a long-running session never realizes its whole history non-lazily.
    func testEagerTailBoundaryIsStableAndBounded() {
        let start = SessionTranscript.macEagerStart(blockCount: 240)
        for appended in 1..<8 {
            XCTAssertEqual(
                SessionTranscript.macEagerStart(blockCount: 240 + appended), start,
                "appending a block moved the Lazy↔VStack boundary"
            )
        }
        for count in 0...400 {
            let eager = count - SessionTranscript.macEagerStart(blockCount: count)
            XCTAssertLessThanOrEqual(eager, 16, "eager tail grew unbounded at \(count) blocks")
            if count >= 8 {
                XCTAssertGreaterThanOrEqual(eager, 8, "too few realized rows at \(count) blocks")
            }
        }
    }

    /// One window for the whole class, never closed.
    ///
    /// A window per case crashed the test host: `-[_NSWindowTransformAnimation dealloc]` releases
    /// into a freed object on a later CA commit, killing the process partway through the suite.
    /// Reproduced on macOS 26.5 against the pre-simplification code too, so it is the harness, not
    /// the scroller — and `animationBehavior = .none` makes it *worse* (every case dies, not the
    /// third). Reusing one window removes window teardown from the suite entirely; each case still
    /// gets a fresh `NSHostingView`, so no SwiftUI state carries over.
    private static var sharedWindow: NSWindow?

    private func mount(_ host: NSView) -> NSWindow {
        let window = Self.sharedWindow ?? {
            let w = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 700, height: 500),
                styleMask: [.titled],
                backing: .buffered,
                defer: false
            )
            Self.sharedWindow = w
            return w
        }()
        host.frame = NSRect(x: 0, y: 0, width: 700, height: 500)
        window.contentView = host
        window.orderFront(nil)
        return window
    }

    func testInitialBottomTargetIsVisibleWithoutRealizingHistory() {
        let store = ProbeStore()
        let host = NSHostingView(rootView: Harness(store: store))
        let window = mount(host)
        // Release the hosting view for the next case without tearing the window down.
        defer { window.contentView = NSView() }

        host.layoutSubtreeIfNeeded()
        window.displayIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        host.layoutSubtreeIfNeeded()
        window.displayIfNeeded()

        guard let newest = store.newestView else {
            return XCTFail("the newest transcript row was not realized at the initial bottom position")
        }
        guard let scroll = firstScrollView(in: host) else {
            return XCTFail("missing transcript scroll view")
        }
        let newestRect = newest.convert(newest.bounds, to: scroll.contentView)
        XCTAssertTrue(
            newestRect.intersects(scroll.contentView.bounds),
            "the newest row is outside the viewport until user scroll"
        )
        XCTAssertLessThan(
            store.made.count,
            60,
            "initial positioning eagerly realized too much transcript history"
        )
    }

    func testTallAppendKeepsBottomVisibleWhileFollowing() {
        let store = ProbeStore()
        let host = NSHostingView(rootView: Harness(store: store))
        let window = mount(host)
        // Release the hosting view for the next case without tearing the window down.
        defer { window.contentView = NSView() }

        host.layoutSubtreeIfNeeded()
        window.displayIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))

        // Append one reply taller than the viewport. Nothing issues a scroll here: this asserts
        // that `defaultScrollAnchor(.bottom, for: .sizeChanges)` alone holds the live edge, which
        // is the whole premise of the single-mechanism scroller.
        host.rootView = Harness(store: store, total: 241, tallNewest: true)
        host.layoutSubtreeIfNeeded()
        window.displayIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        host.layoutSubtreeIfNeeded()
        window.displayIfNeeded()

        guard let bottom = store.bottomView else {
            return XCTFail("bottom sentinel missing after tall append")
        }
        guard let scroll = firstScrollView(in: host) else {
            return XCTFail("missing transcript scroll view")
        }
        let bottomRect = bottom.convert(bottom.bounds, to: scroll.contentView)
        XCTAssertTrue(
            bottomRect.intersects(scroll.contentView.bounds),
            "a tall appended reply detached the following viewport from the bottom"
        )
    }

    /// The complaint this scroller was rewritten for: scroll up to read history, and live traffic
    /// must leave you where you are. Follow is released by the scroll itself (geometry), and the
    /// `.top` size-change anchor then lets new content grow *below* the viewport.
    func testScrollingUpReleasesFollowSoAppendsDoNotYankBack() {
        let store = ProbeStore()
        let host = NSHostingView(rootView: Harness(store: store))
        let window = mount(host)
        // Release the hosting view for the next case without tearing the window down.
        defer { window.contentView = NSView() }

        host.layoutSubtreeIfNeeded()
        window.displayIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))

        guard let scroll = firstScrollView(in: host) else {
            return XCTFail("missing transcript scroll view")
        }
        // Scroll up into history the way a wheel/trackpad would: move the clip view and let the
        // scroll view publish it, which is what `onScrollGeometryChange` observes.
        scroll.contentView.scroll(to: NSPoint(x: 0, y: 0))
        scroll.reflectScrolledClipView(scroll.contentView)
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        let readingOffset = scroll.contentView.bounds.origin.y

        // Live traffic arrives while the user reads: a new, viewport-tall reply.
        host.rootView = Harness(store: store, total: 241, tallNewest: true)
        host.layoutSubtreeIfNeeded()
        window.displayIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))

        XCTAssertEqual(
            scroll.contentView.bounds.origin.y, readingOffset, accuracy: 1,
            "an append moved the viewport while the user was reading history"
        )
        guard let bottom = store.bottomView else {
            return XCTFail("bottom sentinel missing after append")
        }
        let bottomRect = bottom.convert(bottom.bounds, to: scroll.contentView)
        XCTAssertFalse(
            bottomRect.intersects(scroll.contentView.bounds),
            "the transcript yanked back to the live edge while the user was reading history"
        )
    }

    /// The jump-to-bottom button floats over the transcript, and the composer dock is a safe-area
    /// inset the scroll view extends *under* — so a plain bottom overlay would sit behind the
    /// composer, invisible. Measures the placement rule the button actually uses.
    func testFloatingControlClearsTheComposerDock() {
        let dock: CGFloat = 120
        let store = ProbeStore()
        let host = NSHostingView(rootView: Harness(store: store, composerInset: dock))
        let window = mount(host)
        // Release the hosting view for the next case without tearing the window down.
        defer { window.contentView = NSView() }

        host.layoutSubtreeIfNeeded()
        window.displayIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))

        guard let floating = store.floatingView, let content = window.contentView else {
            return XCTFail("floating control probe was never realized")
        }
        // `NSHostingView` is flipped (y grows downward), so the gap above the dock is measured
        // from the content view's bottom edge, not from `minY`.
        let frame = floating.convert(floating.bounds, to: content)
        let gapAboveWindowBottom = content.bounds.height - frame.maxY
        XCTAssertGreaterThanOrEqual(
            gapAboveWindowBottom, dock,
            "the floating control sits behind the composer dock (gap \(gapAboveWindowBottom), dock \(dock))"
        )
        XCTAssertLessThan(
            gapAboveWindowBottom, dock + 40,
            "the floating control drifted far above the composer dock (gap \(gapAboveWindowBottom))"
        )
    }

    private func firstScrollView(in view: NSView) -> NSScrollView? {
        if let scroll = view as? NSScrollView { return scroll }
        for child in view.subviews {
            if let scroll = firstScrollView(in: child) { return scroll }
        }
        return nil
    }
}
#endif
