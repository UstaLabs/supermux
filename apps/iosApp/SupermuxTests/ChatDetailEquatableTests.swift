import XCTest
import Shared
@testable import Supermux

/// Regression: SessionTranscript is wrapped in `.equatable()` so composer keystrokes skip
/// transcript re-evaluation. Chat detail density MUST be part of that equality — otherwise
/// selecting High/Low/Medium from the ⋯ menu leaves the transcript stuck on the previous
/// density until the next broker-driven invalidation (looks like "High does nothing" on macOS).
@MainActor
final class ChatDetailEquatableTests: XCTestCase {

    private func session(_ id: String = "s1") -> SessionInfo {
        SessionInfo(
            id: id, name: "n", workdir: "/w", agent: "claude",
            status: nil, mute: nil, connected: nil, model: nil, reasoningLevel: nil,
            repo_root: nil, role: nil, session_branch: nil, git: nil, finish_job: nil,
            userStatus: nil, sortOrder: 0, draftPayload: nil)
    }

    private func transcript(session: SessionInfo, detail: String) -> SessionTranscript {
        SessionTranscript(
            broker: BrokerSession(baseURL: "http://127.0.0.1:0", token: "t"),
            session: session,
            chatDetailRaw: detail)
    }

    func testEqualWhenSameSessionAndDetail() {
        let s = session()
        XCTAssertEqual(transcript(session: s, detail: "medium"),
                       transcript(session: s, detail: "medium"))
        XCTAssertEqual(transcript(session: s, detail: "high"),
                       transcript(session: s, detail: "high"))
    }

    func testNotEqualWhenDetailChanges() {
        let s = session()
        // Medium → High must invalidate the equatable gate so ToolRowView gets highDetail: true.
        XCTAssertNotEqual(transcript(session: s, detail: "medium"),
                          transcript(session: s, detail: "high"))
        XCTAssertNotEqual(transcript(session: s, detail: "low"),
                          transcript(session: s, detail: "medium"))
    }

    func testNotEqualWhenSessionChanges() {
        XCTAssertNotEqual(transcript(session: session("a"), detail: "high"),
                          transcript(session: session("b"), detail: "high"))
    }

    func testParseChatDetailLevel() {
        XCTAssertEqual(ChatDetailLevel.parse("high"), .high)
        XCTAssertEqual(ChatDetailLevel.parse("low"), .low)
        XCTAssertEqual(ChatDetailLevel.parse("medium"), .medium)
        XCTAssertEqual(ChatDetailLevel.parse("nope"), .medium)
        XCTAssertEqual(ChatDetailLevel.parse(nil), .medium)
    }

    /// High only paints terminal/diff for Bash/Edit/Write (or structured body); pin the
    /// block merge still surfaces those tool names so high-detail resolution can match.
    func testBuildChatBlocksPreservesBashBodyForHighDetail() {
        let bashBody = ActivityToolBody(
            kind: "bash", command: "ls -la", output: nil, exitCode: nil,
            path: nil, rawPath: nil, mode: nil, diff: nil, oldText: nil, newText: nil,
            content: nil, input: nil)
        let started = ActivityEvent(
            ts: "2026-07-30T10:00:00Z", kind: "tool", title: "Bash: ls -la",
            seq: KotlinInt(int: 1), tool: "Bash", detail: "ls -la",
            description: "List files", phase: "started", callId: "c1",
            truncated: nil, body: bashBody)
        let blocks = buildChatBlocks(messages: [], activity: [started])
        guard case .tools(let rows) = blocks.first, let row = rows.first else {
            return XCTFail("expected a tool block")
        }
        XCTAssertEqual(row.toolName, "Bash")
        XCTAssertEqual(row.body?.kind, "bash")
        XCTAssertEqual(row.body?.command, "ls -la")
        XCTAssertEqual(row.description, "List files")
    }
}
