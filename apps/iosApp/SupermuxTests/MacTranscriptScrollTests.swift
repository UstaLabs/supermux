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

    private struct Harness: View {
        let store: ProbeStore
        var pinGeneration: Int = 240
        var total = 240
        var tallNewest = false
        /// Matches `SessionTranscript.macEagerTailCount` — newest rows stay non-lazy.
        private let eagerTail = 8

        var body: some View {
            // Mirrors SessionTranscript's mac layout: lazy history + small eager tail + sentinel.
            // Pure LazyVStack alone blanks on real markdown heights; this landing zone is required.
            MacTranscriptScrollView(pinGeneration: pinGeneration) {
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
            .frame(width: 700, height: 500)
        }
    }

    func testOnlyUserScrollingCanChangeFollowMode() {
        XCTAssertTrue(macTranscriptFollowingState(
            current: true, userScrollActive: false, distanceFromBottom: 2_000
        ), "content growth must not look like a user scroll")
        XCTAssertFalse(macTranscriptFollowingState(
            current: true, userScrollActive: true, distanceFromBottom: 2_000
        ), "a real user scroll away from the bottom must stop following")
        XCTAssertTrue(macTranscriptFollowingState(
            current: false, userScrollActive: true, distanceFromBottom: 20
        ), "scrolling back to the bottom must restore following")
        XCTAssertFalse(macTranscriptFollowingState(
            current: false, userScrollActive: true, distanceFromBottom: 70
        ), "the hysteresis band must preserve the current state")
    }

    func testInitialBottomTargetIsVisibleWithoutRealizingHistory() {
        let store = ProbeStore()
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 700, height: 500),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        let host = NSHostingView(rootView: Harness(store: store))
        host.frame = NSRect(x: 0, y: 0, width: 700, height: 500)
        window.contentView = host
        window.orderFront(nil)
        defer { window.close() }

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
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 700, height: 500),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        let host = NSHostingView(rootView: Harness(store: store, pinGeneration: 1))
        host.frame = NSRect(x: 0, y: 0, width: 700, height: 500)
        window.contentView = host
        window.orderFront(nil)
        defer { window.close() }

        host.layoutSubtreeIfNeeded()
        window.displayIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))

        // Append one reply taller than the viewport. This changes real content size — changing
        // only pinGeneration would not exercise the layout race that used to disable follow.
        host.rootView = Harness(store: store, pinGeneration: 2, total: 241, tallNewest: true)
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

    private func firstScrollView(in view: NSView) -> NSScrollView? {
        if let scroll = view as? NSScrollView { return scroll }
        for child in view.subviews {
            if let scroll = firstScrollView(in: child) { return scroll }
        }
        return nil
    }
}
#endif
