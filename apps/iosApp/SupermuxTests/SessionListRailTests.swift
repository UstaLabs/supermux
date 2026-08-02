import XCTest
@testable import Supermux

/// UI-contract tests for the leading session-list rail (phone/mac SessionStatusRail + watch).
/// Priority matches KMP `sessionListRailIndicator` / `sessionListShowsUnread`.
final class SessionListRailTests: XCTestCase {

    // MARK: - Pure decision (shared with native rails)

    func testWorkingWinsOverUnread() {
        XCTAssertEqual(sessionListRailKind(working: true, unread: true), .working)
        XCTAssertFalse(sessionListShowsUnreadMark(active: false, working: true, lastMessageTs: "2026-08-01T12:00:00Z", lastReadAt: "2026-08-01T11:00:00Z"))
    }

    func testIdleUnreadIsUnread() {
        XCTAssertTrue(sessionListShowsUnreadMark(active: false, working: false, lastMessageTs: "2026-08-01T12:00:00Z", lastReadAt: "2026-08-01T11:00:00Z"))
        XCTAssertEqual(sessionListRailKind(working: false, unread: true), .unread)
    }

    func testIdleReadIsOther() {
        XCTAssertFalse(sessionListShowsUnreadMark(active: false, working: false, lastMessageTs: "2026-08-01T12:00:00Z", lastReadAt: "2026-08-01T12:00:00Z"))
        XCTAssertEqual(sessionListRailKind(working: false, unread: false), .other)
    }

    func testActiveNeverUnread() {
        XCTAssertFalse(sessionListShowsUnreadMark(active: true, working: false, lastMessageTs: "2026-08-01T12:00:00Z", lastReadAt: nil))
    }

    func testIsSessionUnreadStringCompare() {
        XCTAssertTrue(isSessionUnread(lastMessageTs: "2026-08-01T12:00:00Z", lastReadAt: nil))
        XCTAssertTrue(isSessionUnread(lastMessageTs: "2026-08-01T12:00:00Z", lastReadAt: "2026-08-01T11:00:00Z"))
        XCTAssertFalse(isSessionUnread(lastMessageTs: "2026-08-01T12:00:00Z", lastReadAt: "2026-08-01T12:00:00Z"))
        XCTAssertFalse(isSessionUnread(lastMessageTs: nil, lastReadAt: nil))
    }

    // Watch attentionBucket (working > needs-you) lives in WatchSessionStatusTests —
    // those symbols are Watch/iOS-only and not linked into SupermuxMac.
}
