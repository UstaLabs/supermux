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
    }

    private struct ProbeRow: NSViewRepresentable {
        let id: Int
        let store: ProbeStore

        func makeNSView(context: Context) -> NSView {
            let view = NSView(frame: .zero)
            store.made.insert(id)
            if id == 239 { store.newestView = view }
            return view
        }

        func updateNSView(_ nsView: NSView, context: Context) {}
    }

    private struct Harness: View {
        let store: ProbeStore
        var pinGeneration: Int = 240

        var body: some View {
            // Mirrors SessionTranscript's mac layout: one LazyVStack of timeline rows + a
            // non-lazy footer sentinel. No sliding eager-message tail (that reparented rows).
            MacTranscriptScrollView(pinGeneration: pinGeneration) {
                LazyVStack(spacing: 0) {
                    ForEach(0..<240, id: \.self) { id in
                        ProbeRow(id: id, store: store)
                            .frame(height: CGFloat(28 + (id % 7) * 19))
                    }
                }
                VStack(spacing: 0) {
                    Color.clear.frame(height: 1).id("__bottom__")
                }
            }
            .frame(width: 700, height: 500)
        }
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

    func testPinGenerationChangeKeepsNewestVisibleWhileFollowing() {
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

        // Simulate a live append (new tool/message generation) while still following bottom.
        host.rootView = Harness(store: store, pinGeneration: 2)
        host.layoutSubtreeIfNeeded()
        window.displayIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        host.layoutSubtreeIfNeeded()
        window.displayIfNeeded()

        guard let newest = store.newestView else {
            return XCTFail("newest row missing after pinGeneration change")
        }
        guard let scroll = firstScrollView(in: host) else {
            return XCTFail("missing transcript scroll view")
        }
        let newestRect = newest.convert(newest.bounds, to: scroll.contentView)
        XCTAssertTrue(
            newestRect.intersects(scroll.contentView.bounds),
            "following pinGeneration change left the newest row off-screen"
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
