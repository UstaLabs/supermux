import XCTest
@testable import Supermux

/// The session-list preview line is plain text (Mail/Messages parity) — agent markdown must not
/// leak into it. These pin the cases actually seen in the live list.
final class SessionPreviewTextTests: XCTestCase {

    func testStripsBoldAndInlineCode() {
        // Verbatim shape of a real row: an ordered-list marker at a line start, inline code,
        // and bold — the list joins the lines, so all three have to go.
        XCTAssertEqual(
            sessionPreviewPlainText("Merged and pushed to `dev`.\n1. **Committed** on `main`"),
            "Merged and pushed to dev. Committed on main"
        )
    }

    /// A list marker is only a marker at a line start. Mid-sentence it's ordinary prose and
    /// must survive verbatim.
    func testMidSentenceMarkersSurvive() {
        XCTAssertEqual(sessionPreviewPlainText("shipped v1. 2 more to go"),
                       "shipped v1. 2 more to go")
        XCTAssertEqual(sessionPreviewPlainText("the flag is set - see the docs"),
                       "the flag is set - see the docs")
    }

    func testStripsLeadingBoldRun() {
        XCTAssertEqual(
            sessionPreviewPlainText("**Mac needed real product changes** — the green unread rail"),
            "Mac needed real product changes — the green unread rail"
        )
    }

    func testCollapsesNewlinesAndWhitespace() {
        XCTAssertEqual(
            sessionPreviewPlainText("first line\n\n   second    line\n"),
            "first line second line"
        )
    }

    func testStripsHeadingsBlockquotesAndBullets() {
        XCTAssertEqual(sessionPreviewPlainText("### Result\n- one\n- two"), "Result one two")
        XCTAssertEqual(sessionPreviewPlainText("> quoted reply"), "quoted reply")
        XCTAssertEqual(sessionPreviewPlainText("1. first\n2. second"), "first second")
    }

    func testLinkKeepsLabelDropsURL() {
        XCTAssertEqual(
            sessionPreviewPlainText("see [the PR](https://github.com/a/b/pull/1) for details"),
            "see the PR for details"
        )
    }

    func testCodeFenceKeepsTheCode() {
        XCTAssertEqual(sessionPreviewPlainText("```bash\nbun test\n```"), "bun test")
    }

    func testLeavesPlainTextAndIdentifiersAlone() {
        XCTAssertEqual(sessionPreviewPlainText("Got it — 16:57:23, round-trip working."),
                       "Got it — 16:57:23, round-trip working.")
        // snake_case must survive: only `*emphasis*` is unwrapped, never `_`.
        XCTAssertEqual(sessionPreviewPlainText("renamed last_read_at to lastReadAt"),
                       "renamed last_read_at to lastReadAt")
        // A lone `2 * 3` is arithmetic, not emphasis.
        XCTAssertEqual(sessionPreviewPlainText("2 * 3 = 6"), "2 * 3 = 6")
    }

    func testSingleAsteriskEmphasisIsUnwrapped() {
        XCTAssertEqual(sessionPreviewPlainText("this is *really* important"),
                       "this is really important")
    }

    func testBoundsVeryLongInput() {
        let long = String(repeating: "a", count: 5_000)
        XCTAssertLessThanOrEqual(sessionPreviewPlainText(long).count, 300)
    }
}
