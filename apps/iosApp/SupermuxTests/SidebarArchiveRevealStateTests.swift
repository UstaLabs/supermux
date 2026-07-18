import XCTest
@testable import Supermux

@MainActor
final class SidebarArchiveRevealStateTests: XCTestCase {
    func testTrackClampsPullDistanceWithoutLatching() {
        let state = SidebarArchiveRevealState()

        state.track(top: 12)
        XCTAssertEqual(state.visibleHeight, 0)

        state.track(top: -24)
        XCTAssertEqual(state.visibleHeight, 24)

        state.track(top: -100)
        XCTAssertEqual(state.visibleHeight, SidebarArchiveRevealState.maximumHeight)
        XCTAssertFalse(state.isLatched)
    }

    func testLatchedRevealIgnoresScrollUntilClosed() {
        let state = SidebarArchiveRevealState()

        state.latch()
        state.track(top: 40)

        XCTAssertTrue(state.isLatched)
        XCTAssertEqual(state.visibleHeight, SidebarArchiveRevealState.maximumHeight)

        state.close()
        XCTAssertFalse(state.isLatched)
        XCTAssertEqual(state.visibleHeight, 0)
    }
}
