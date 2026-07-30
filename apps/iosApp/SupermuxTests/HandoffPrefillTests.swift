import XCTest
@testable import Supermux

final class HandoffPrefillTests: XCTestCase {
    func testBuildIncludesSessionNameAndReadSessionInstruction() {
        let text = HandoffPrefill.build(name: "Fix auth race", id: "sess-abc")
        XCTAssertTrue(text.contains("Continue work from the prior supermux session"))
        XCTAssertTrue(text.contains("Session: Fix auth race"))
        XCTAssertTrue(text.contains("Source session id: sess-abc"))
        XCTAssertTrue(text.contains("read_session with session_id \"sess-abc\""))
        XCTAssertTrue(text.contains("workspace files as authoritative"))
    }

    func testBuildFallsBackWhenNameBlank() {
        let text = HandoffPrefill.build(name: "  ", id: "x")
        XCTAssertTrue(text.contains("Session: previous session"))
        XCTAssertTrue(text.contains("Source session id: x"))
    }

    func testDefaultAgentPrefersSourceWhenKnown() {
        XCTAssertEqual(HandoffPrefill.defaultAgent(sourceAgent: "codex"), .codex)
        XCTAssertEqual(HandoffPrefill.defaultAgent(sourceAgent: "GROK"), .grok)
        XCTAssertEqual(HandoffPrefill.defaultAgent(sourceAgent: "unknown"), .claude)
        XCTAssertEqual(HandoffPrefill.defaultAgent(sourceAgent: nil), .claude)
    }
}
