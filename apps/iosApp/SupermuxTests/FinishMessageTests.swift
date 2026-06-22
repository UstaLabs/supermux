import XCTest
import Shared
@testable import Supermux

/// Unit tests for `SessionChrome.issueMessage` — the pure "Let the agent fix it" message
/// builder. Must match the web `FinishSheet.vue` `issueMessage` (src/web-app) verbatim so
/// iOS and the PWA hand the agent identical recovery prompts.
///
/// `SessionChrome` is `@MainActor`, so the static builder is main-actor-isolated → these
/// tests run on the main actor. `FinishResult` is the bridged Kotlin DTO; it's built with the
/// full positional initializer (all fields have Kotlin defaults; we pass nil/[] for unused
/// ones) to avoid relying on SKIE default-argument overloads.
@MainActor
final class FinishMessageTests: XCTestCase {

    /// Build a `FinishResult` with only the fields the message builder reads.
    private func result(status: String, files: [String] = [], command: String? = nil,
                        output: String? = nil, message: String? = nil) -> FinishResult {
        FinishResult(
            status: status,
            base: nil,
            branch: nil,
            mergedSha: nil,
            verified: nil,
            files: files,
            command: command,
            output: output,
            message: message,
            prUrl: nil,
            compareUrl: nil,
            prError: nil,
            draft: nil,
            cleanedUp: nil
        )
    }

    func testSyncConflictListsFiles() {
        let o = result(status: "sync_conflict", files: ["a.ts", "b.ts"])
        XCTAssertEqual(
            SessionChrome.issueMessage(o),
            "The Finish step merged the base branch in and hit conflicts in:\n- a.ts\n- b.ts\n\nThe worktree is in a conflicted merge state — please resolve the conflicts and commit, then I'll run Finish again."
        )
    }

    func testTestsFailedEmbedsCommandAndOutput() {
        let o = result(status: "tests_failed", command: "npm test", output: "1 failing")
        XCTAssertEqual(
            SessionChrome.issueMessage(o),
            "The Finish step ran the tests (`npm test`) and they failed:\n\n```\n1 failing\n```\n\nPlease fix them so the branch is green, then I'll run Finish again."
        )
    }

    func testDirtyOverlapJoinsFilesWithCommas() {
        let o = result(status: "dirty_overlap", files: ["x", "y", "z"])
        XCTAssertEqual(
            SessionChrome.issueMessage(o),
            "The base checkout has unsaved changes in: x, y, z — the same files my work touches. Please commit or stash them so Finish can fast-forward."
        )
    }

    func testPushRejectedIncludesMessage() {
        let o = result(status: "push_rejected", message: "remote diverged")
        XCTAssertEqual(
            SessionChrome.issueMessage(o),
            "Pushing the branch for a PR was rejected because the remote has diverged: remote diverged. Please reconcile (pull/rebase) and I'll run Finish again."
        )
    }

    func testDefaultPrefersMessageOverStatus() {
        XCTAssertEqual(
            SessionChrome.issueMessage(result(status: "error", message: "boom")),
            "Finish reported: boom"
        )
    }

    func testDefaultFallsBackToStatusWhenNoMessage() {
        XCTAssertEqual(
            SessionChrome.issueMessage(result(status: "weird_state")),
            "Finish reported: weird_state"
        )
    }
}
