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
            agentState: [:], commands: [:], commandsResolved: [:]))

        broker.reduce(ServerFrameSessionRenamed(
            id: "s1", old: "debug-session-renaming", newName: "Fix Session Renaming"))

        XCTAssertEqual(broker.sessions.first?.name, "Fix Session Renaming")
    }
}
