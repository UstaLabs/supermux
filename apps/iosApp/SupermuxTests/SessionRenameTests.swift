import XCTest
import Shared
@testable import Supermux

@MainActor
final class SessionRenameTests: XCTestCase {
    func testSessionRenamedUpdatesTheDisplayNameLive() {
        let broker = BrokerSession(baseURL: "http://127.0.0.1:0", token: "test-token")
        let session = SessionInfo(
            id: "s1", name: "debug-session-renaming", workdir: "/w", agent: "codex",
            status: nil, mute: nil, connected: nil, model: nil, reasoningLevel: nil,
            repo_root: nil, role: nil, session_branch: nil, git: nil, finish_job: nil,
            userStatus: nil, sortOrder: 0, draftPayload: nil)
        broker.reduce(ServerFrameSnapshot(
            sessions: [session], logs: [:], activity: [:], bgTasks: [:],
            agentState: [:], commands: [:], commandsResolved: [:], reads: [:]))

        broker.reduce(ServerFrameSessionRenamed(
            id: "s1", old: "debug-session-renaming", newName: "Fix Session Renaming"))

        XCTAssertEqual(broker.sessions.first?.name, "Fix Session Renaming")
    }

    func testSessionsReorderedRenumbersSortOrderLive() {
        let broker = BrokerSession(baseURL: "http://127.0.0.1:0", token: "test-token")
        func sess(_ id: String, order: Int32) -> SessionInfo {
            SessionInfo(
                id: id, name: id, workdir: "/w", agent: "codex",
                status: nil, mute: nil, connected: nil, model: nil, reasoningLevel: nil,
                repo_root: nil, role: nil, session_branch: nil, git: nil, finish_job: nil,
                userStatus: "in_progress", sortOrder: order, draftPayload: nil)
        }
        broker.reduce(ServerFrameSnapshot(
            sessions: [sess("s1", order: 0), sess("s2", order: 1), sess("s3", order: 2)],
            logs: [:], activity: [:], bgTasks: [:],
            agentState: [:], commands: [:], commandsResolved: [:], reads: [:]))

        broker.reduce(ServerFrameSessionsReordered(orderedIds: ["s3", "s1", "s2"]))

        XCTAssertEqual(broker.sessions.first { $0.id == "s3" }?.sortOrder, 0)
        XCTAssertEqual(broker.sessions.first { $0.id == "s1" }?.sortOrder, 1)
        XCTAssertEqual(broker.sessions.first { $0.id == "s2" }?.sortOrder, 2)
    }
}
