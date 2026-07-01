import XCTest
@testable import Supermux

final class WatchSessionStatusTests: XCTestCase {
    func testWorktreeDoneNotDonePristine() {
        XCTAssertEqual(sessionStatus(GitLite(mode: "base", ahead: 0, behind: 0, dirty: 0, touched: true,  unpublished: nil))?.level, .done)
        XCTAssertEqual(sessionStatus(GitLite(mode: "base", ahead: 2, behind: 0, dirty: 0, touched: true,  unpublished: nil))?.level, .notDone)
        XCTAssertEqual(sessionStatus(GitLite(mode: "base", ahead: 0, behind: 0, dirty: 3, touched: true,  unpublished: nil))?.level, .notDone)
        XCTAssertEqual(sessionStatus(GitLite(mode: "base", ahead: 0, behind: 0, dirty: 0, touched: false, unpublished: nil))?.level, .pristine)
    }
    func testRemoteSyncedVsNot() {
        XCTAssertEqual(sessionStatus(GitLite(mode: "remote", ahead: 0, behind: 0, dirty: 0, touched: nil, unpublished: false))?.level, .done)
        XCTAssertEqual(sessionStatus(GitLite(mode: "remote", ahead: 0, behind: 0, dirty: 0, touched: nil, unpublished: true ))?.level, .notDone)
        XCTAssertEqual(sessionStatus(GitLite(mode: "remote", ahead: 1, behind: 0, dirty: 0, touched: nil, unpublished: false))?.level, .notDone)
        XCTAssertEqual(sessionStatus(GitLite(mode: "remote", ahead: 0, behind: 2, dirty: 0, touched: nil, unpublished: false))?.level, .notDone)
    }
    func testNilGit() { XCTAssertNil(sessionStatus(nil)) }
    func testWorkingSet() {
        for p in ["thinking", "running"] { XCTAssertTrue(isWorking(p)) }
        for p in ["idle", "stalled", "working", "tool", "busy", "sending"] { XCTAssertFalse(isWorking(p)) }
        XCTAssertFalse(isWorking(nil))
    }
    func testAttentionBucket() {
        XCTAssertEqual(attentionBucket(phase: "idle",    unread: true),  0)  // needs you
        XCTAssertEqual(attentionBucket(phase: "running", unread: true),  1)  // working wins over unread
        XCTAssertEqual(attentionBucket(phase: "running", unread: false), 1)
        XCTAssertEqual(attentionBucket(phase: "idle",    unread: false), 2)
    }
    func testTsValueOrdersNewerHigher() {
        XCTAssertGreaterThan(tsValue("2026-06-27T05:00:00Z"), tsValue("2026-06-27T04:00:00Z"))
        XCTAssertGreaterThan(tsValue("1782535713328"), 0)
        XCTAssertEqual(tsValue(nil), 0)
    }
}
