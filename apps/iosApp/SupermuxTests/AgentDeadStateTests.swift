import XCTest
import Shared
@testable import Supermux

/// Unit tests for `BrokerSession`'s `agentDead` tracking (broker `agent_state` frames carry
/// `state: "idle" | "working" | "dead"` — Frames.kt:140 — and `agentDead` mirrors `agentWorking`
/// as a derived `state == "dead"` map). No existing test drives `BrokerSession`'s frame handler
/// (`agentWorking` has none either), so `reduce(_:)` was widened from `private` to internal
/// (see BrokerSession.swift) purely so this suite can feed it frames directly, the same way the
/// real `start()` frame loop does — `BrokerSession` never opens a socket in these tests, since
/// only `reduce(_:)` is called, never `start()`/`run()`.
@MainActor
final class AgentDeadStateTests: XCTestCase {

    private func makeBroker() -> BrokerSession {
        BrokerSession(baseURL: "http://127.0.0.1:0", token: "test-token")
    }

    /// Full positional `AgentStatus` init (mirrors `ComposerModelTests`' `SlashCommand` helper —
    /// avoids relying on SKIE default-arg overloads).
    private func agentStatus(state: String) -> AgentStatus {
        AgentStatus(phase: "idle", state: state, working: false, detail: nil, tool: nil, since: nil, workingSince: nil)
    }

    // MARK: - Live agent_state frames (BrokerSession.swift ~line 133 area)

    func testDeadStateTracked() {
        let broker = makeBroker()

        // A live agent_state frame with state == "dead" for session "s1"…
        broker.reduce(ServerFrameAgentState(session: "s1", phase: "stalled", state: "dead",
                                             working: false, detail: nil, tool: nil,
                                             since: nil, workingSince: nil))
        XCTAssertEqual(broker.agentDead["s1"], true)

        // …then a state == "idle" frame clears it.
        broker.reduce(ServerFrameAgentState(session: "s1", phase: "idle", state: "idle",
                                             working: false, detail: nil, tool: nil,
                                             since: nil, workingSince: nil))
        XCTAssertEqual(broker.agentDead["s1"], false)
    }

    func testWorkingStateIsNotDead() {
        let broker = makeBroker()
        broker.reduce(ServerFrameAgentState(session: "s1", phase: "running", state: "working",
                                             working: true, detail: "running", tool: nil,
                                             since: nil, workingSince: nil))
        XCTAssertEqual(broker.agentDead["s1"], false)
        XCTAssertEqual(broker.agentWorking["s1"], true)
    }

    // MARK: - Snapshot frame (BrokerSession.swift ~line 89 area)

    func testSnapshotMapsDeadState() {
        let broker = makeBroker()
        let snapshot = ServerFrameSnapshot(
            sessions: [], logs: [:], activity: [:],
            agentState: ["s1": agentStatus(state: "dead"), "s2": agentStatus(state: "idle")],
            commands: [:], commandsResolved: [:]
        )
        broker.reduce(snapshot)

        XCTAssertEqual(broker.agentDead["s1"], true)
        XCTAssertEqual(broker.agentDead["s2"], false)
    }
}
