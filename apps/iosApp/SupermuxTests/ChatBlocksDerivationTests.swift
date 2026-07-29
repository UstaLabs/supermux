import XCTest
import Shared
@testable import Supermux

/// `SessionChatBuffer` derives the transcript timeline (`blocks`) when its `messages`/`activity`
/// change, instead of `SessionTranscript.body` rebuilding it on every SwiftUI evaluation.
///
/// That body also reads `agentPhase` / `agentWorking` / `pendingSend` / `bgTasks` for its working
/// indicator, so a running agent's phase ticks used to re-sort and re-cluster the entire history —
/// allocating a `ToolRow` per activity event, each reading several SKIE-bridged Kotlin properties.
/// Measured at 5.5 ms/s of blocked main thread while switching sessions.
@MainActor
final class ChatBlocksDerivationTests: XCTestCase {

    private func makeBroker() -> BrokerSession {
        BrokerSession(baseURL: "http://127.0.0.1:0", token: "test-token")
    }

    private func msg(_ id: String, _ ts: String, _ text: String) -> LogEntry {
        LogEntry(id: id, ts: ts, direction: "out", text: text,
                 op: nil, channel: nil, chat_id: nil, message_id: nil, attachments: nil)
    }

    private func tool(_ ts: String, seq: Int32, name: String, callId: String) -> ActivityEvent {
        ActivityEvent(ts: ts, kind: "tool", title: "\(name): thing", seq: KotlinInt(int: seq),
                      tool: name, detail: nil, description: nil, phase: nil,
                      callId: callId, truncated: nil, body: nil)
    }

    private func isMessage(_ b: ChatBlock) -> Bool {
        if case .message = b { return true } else { return false }
    }

    // MARK: - Derivation

    /// The buffer's derived blocks must equal what the view used to compute inline.
    func testBlocksDerivedFromBufferContents() {
        let broker = makeBroker()
        let messages = [msg("m1", "2026-07-29T10:00:00Z", "first"),
                        msg("m2", "2026-07-29T10:00:30Z", "second")]
        let activity = [tool("2026-07-29T10:00:10Z", seq: 1, name: "Read", callId: "c1"),
                        tool("2026-07-29T10:00:20Z", seq: 2, name: "Edit", callId: "c2")]

        broker.reduce(ServerFrameSnapshot(
            sessions: [], logs: ["s1": messages], activity: ["s1": activity],
            bgTasks: [:], agentState: [:], commands: [:], commandsResolved: [:]))

        let derived = broker.chatBuffer(for: "s1").blocks
        let expected = buildChatBlocks(messages: messages, activity: activity)
        XCTAssertEqual(derived.map(\.id), expected.map(\.id))
        // message, tool-cluster (both tools merge — they are adjacent), message
        XCTAssertEqual(derived.count, 3)
        XCTAssertTrue(isMessage(derived[0]))
        XCTAssertFalse(isMessage(derived[1]))
        XCTAssertTrue(isMessage(derived[2]))
    }

    /// A later append re-derives — stale blocks would silently freeze the transcript.
    func testBlocksRederiveOnAppend() {
        let broker = makeBroker()
        broker.reduce(ServerFrameSnapshot(
            sessions: [], logs: ["s1": [msg("m1", "2026-07-29T10:00:00Z", "first")]],
            activity: [:], bgTasks: [:], agentState: [:], commands: [:], commandsResolved: [:]))
        XCTAssertEqual(broker.chatBuffer(for: "s1").blocks.count, 1)

        broker.reduce(ServerFrameMessageAppend(
            session: "s1", entry: msg("m2", "2026-07-29T10:01:00Z", "second")))
        XCTAssertEqual(broker.chatBuffer(for: "s1").blocks.count, 2)
    }

    // MARK: - Low-detail equivalence

    /// `SessionTranscript` now renders low chat-detail by dropping `.tools` clusters from the full
    /// timeline rather than rebuilding with `hideTools: true`. Pin that those are the same thing —
    /// if `buildChatBlocks` ever gains hideTools-specific clustering, this fails instead of
    /// silently changing what Low shows.
    func testHideToolsEqualsFilteringMessagesFromFullTimeline() {
        let messages = [msg("m1", "2026-07-29T10:00:00Z", "first"),
                        msg("m2", "2026-07-29T10:00:30Z", "second"),
                        msg("m3", "2026-07-29T10:02:00Z", "third")]
        let activity = [tool("2026-07-29T10:00:10Z", seq: 1, name: "Read", callId: "c1"),
                        tool("2026-07-29T10:01:00Z", seq: 2, name: "Edit", callId: "c2")]

        let hidden = buildChatBlocks(messages: messages, activity: activity, hideTools: true)
        let filtered = buildChatBlocks(messages: messages, activity: activity).filter(isMessage)

        XCTAssertEqual(hidden.map(\.id), filtered.map(\.id))
        XCTAssertEqual(hidden.count, messages.count)
    }
}
