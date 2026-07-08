import XCTest
@testable import Supermux

/// Unit tests for `PushGroupState.render` — the pure builder that turns a chat's recent
/// lines + unread count into the collapsed notification's (title, subtitle, body). The
/// App Group I/O is exercised on-device; this locks the rendering rules.
final class PushGroupStateTests: XCTestCase {
    func testSingleMessageHasNoCountSubtitle() {
        let r = PushGroupState.render(title: "worker-3", lines: ["deploy is green"], count: 1)
        XCTAssertEqual(r.title, "worker-3")
        XCTAssertEqual(r.subtitle, "")
        XCTAssertEqual(r.body, "deploy is green")
    }

    func testMultipleMessagesSummarizeNewestFirst() {
        let r = PushGroupState.render(title: "worker-3",
                                      lines: ["first", "second", "third"],
                                      count: 3)
        XCTAssertEqual(r.subtitle, "3 new messages")
        // Newest first so the collapsed one-line preview shows the latest message.
        XCTAssertEqual(r.body, "third\nsecond\nfirst")
    }

    func testCountCanExceedRetainedLines() {
        // Only the last few lines are retained, but the count reflects every unread message.
        let r = PushGroupState.render(title: "s", lines: ["m4", "m5", "m6", "m7"], count: 9)
        XCTAssertEqual(r.subtitle, "9 new messages")
        XCTAssertEqual(r.body, "m7\nm6\nm5\nm4")
    }

    func testEmptyLinesIsSafe() {
        let r = PushGroupState.render(title: "s", lines: [], count: 1)
        XCTAssertEqual(r.body, "")
    }
}
