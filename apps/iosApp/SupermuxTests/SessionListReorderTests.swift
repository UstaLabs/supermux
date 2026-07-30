import XCTest
@testable import Supermux

final class SessionListReorderTests: XCTestCase {
    func testMoveSessionIdByIndex() {
        let ids = ["a", "b", "c", "d"]
        // Move b (1) to index 3 → a,c,d,b
        XCTAssertEqual(moveSessionId(ids, from: 1, to: 3), ["a", "c", "d", "b"])
        // Move d (3) to front → d,a,b,c
        XCTAssertEqual(moveSessionId(ids, from: 3, to: 0), ["d", "a", "b", "c"])
        XCTAssertEqual(moveSessionId(ids, from: 1, to: 1), ids)
    }

    func testMoveSessionIdById() {
        let ids = ["a", "b", "c", "d"]
        XCTAssertEqual(moveSessionId(ids, fromId: "b", toId: "d"), ["a", "c", "d", "b"])
        XCTAssertEqual(moveSessionId(ids, fromId: "d", toId: "a"), ["d", "a", "b", "c"])
        XCTAssertNil(moveSessionId(ids, fromId: "b", toId: "b"))
        XCTAssertNil(moveSessionId(ids, fromId: "x", toId: "a"))
    }

    func testReorderedSessionIdsMovesSingleItem() {
        let ids = ["a", "b", "c", "d"]
        // Move "b" (index 1) to before "d" (toOffset 3) → a, c, b, d
        XCTAssertEqual(reorderedSessionIds(ids, from: IndexSet(integer: 1), to: 3), ["a", "c", "b", "d"])
    }

    func testReorderedSessionIdsNoopWhenUnchanged() {
        let ids = ["a", "b", "c"]
        XCTAssertEqual(reorderedSessionIds(ids, from: IndexSet(integer: 1), to: 1), ids)
        XCTAssertEqual(reorderedSessionIds(ids, from: IndexSet(integer: 1), to: 2), ids)
    }

    @MainActor
    func testSectionReorderStateLiveMoveAndCommit() {
        let state = SessionSectionReorderState()
        state.begin(sectionKey: "flat:in_progress", id: "b", ids: ["a", "b", "c"])
        XCTAssertTrue(state.isDragging)
        XCTAssertEqual(state.displayOrder(sectionKey: "flat:in_progress", fallback: ["a", "b", "c"]), ["a", "b", "c"])

        XCTAssertTrue(state.moveOver(targetId: "c"))
        XCTAssertEqual(state.displayOrder(sectionKey: "flat:in_progress", fallback: ["a", "b", "c"]), ["a", "c", "b"])

        let committed = state.finish(commit: true)
        XCTAssertEqual(committed, ["a", "c", "b"])
        XCTAssertFalse(state.isDragging)
    }

    @MainActor
    func testSectionReorderStateCancelDoesNotCommit() {
        let state = SessionSectionReorderState()
        state.begin(sectionKey: "g", id: "a", ids: ["a", "b"])
        _ = state.moveOver(targetId: "b")
        XCTAssertNil(state.finish(commit: false))
        XCTAssertFalse(state.isDragging)
    }
}
