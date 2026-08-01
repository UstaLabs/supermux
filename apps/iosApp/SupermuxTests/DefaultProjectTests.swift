import XCTest
@testable import Supermux
import Shared

/// Smoke tests for the shared KMP default-project helpers used by NewSessionView
/// (web/Android chooseDefaultProject parity). Full table coverage lives in
/// apps/shared/.../DefaultProjectTest.kt.
final class DefaultProjectTests: XCTestCase {
    func testChooseDefaultFollowsMostRecentUntilEngaged() {
        XCTAssertEqual(
            chooseDefaultProject(current: "~", recent: ["/first"], picked: false, composing: false),
            "/first"
        )
        XCTAssertEqual(
            chooseDefaultProject(
                current: "/first",
                recent: ["/second", "/first"],
                picked: false,
                composing: false
            ),
            "/second"
        )
    }

    func testChooseDefaultFreezesWhenPickedOrComposing() {
        XCTAssertEqual(
            chooseDefaultProject(
                current: "/chosen",
                recent: ["/latest"],
                picked: true,
                composing: false
            ),
            "/chosen"
        )
        XCTAssertEqual(
            chooseDefaultProject(
                current: "/first",
                recent: ["/second", "/first"],
                picked: false,
                composing: true
            ),
            "/first"
        )
    }

    private func session(
        id: String,
        workdir: String,
        repo: String? = nil
    ) -> SessionInfo {
        SessionInfo(
            id: id, name: id, workdir: workdir, agent: "claude",
            status: nil, mute: nil, connected: nil, model: nil, reasoningLevel: nil,
            repo_root: repo, role: nil, session_branch: nil, git: nil, finish_job: nil,
            userStatus: nil, sortOrder: 0, draftPayload: nil
        )
    }

    func testRecentWorkdirsPrefersRepoRootAndDedupes() {
        let sessions: [SessionInfo] = [
            session(id: "a", workdir: "/home/u/.mux/worktrees/x", repo: "/home/u/projects/foo"),
            session(id: "b", workdir: "/home/u/projects/foo"),
            session(id: "c", workdir: "/home/u/projects/bar"),
        ]
        XCTAssertEqual(
            recentWorkdirs(sessionsNewestFirst: sessions),
            ["/home/u/projects/foo", "/home/u/projects/bar"]
        )
    }

    func testOrderProjectsByRecencyPutsRecentFirst() {
        XCTAssertEqual(
            orderProjectsByRecency(recent: ["/b", "/a"], known: ["/a", "/b", "/c"]),
            ["/b", "/a", "/c"]
        )
    }

    func testSessionsByRecencyNewestFirst() {
        let a = session(id: "a", workdir: "/a")
        let b = session(id: "b", workdir: "/b")
        let c = session(id: "c", workdir: "/c")
        let ts = [
            "a": "2026-06-01T08:00:00Z",
            "b": "2026-06-01T12:00:00Z",
            "c": "2026-06-01T10:00:00Z",
        ]
        let sorted = sessionsByRecency(sessions: [a, b, c]) { ts[$0.id] ?? "" }
        XCTAssertEqual(sorted.map(\.id), ["b", "c", "a"])
    }
}
