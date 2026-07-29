import XCTest
@testable import Supermux
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// Pins the pure-Swift inline markdown path that replaced Foundation's
/// `AttributedString(markdown:)` on the chat hot path.
final class MarkdownInlineTests: XCTestCase {

    private var baseFont: PlatformFont {
        #if os(macOS)
        .preferredFont(forTextStyle: .body)
        #else
        .preferredFont(forTextStyle: .subheadline)
        #endif
    }

    private func plain(_ s: String) -> String {
        MarkdownInline.nsAttributed(s, baseFont: baseFont, color: .smLabel, paragraph: nil).string
    }

    func testStripsMarkersButKeepsText() {
        XCTAssertEqual(plain("hello **bold** world"), "hello bold world")
        XCTAssertEqual(plain("a *i* b"), "a i b")
        XCTAssertEqual(plain("run `ls -la` now"), "run ls -la now")
        XCTAssertEqual(plain("~~gone~~ stay"), "gone stay")
        XCTAssertEqual(plain("see [docs](https://example.com) ok"), "see docs ok")
    }

    func testUnmatchedMarkersStayLiteral() {
        XCTAssertEqual(plain("a **b"), "a **b")
        XCTAssertEqual(plain("x `y"), "x `y")
    }

    func testNestedBoldItalic() {
        XCTAssertEqual(plain("**bold and *italic* end**"), "bold and italic end")
    }

    func testPlainProseIsIdentity() {
        let s = "Just a normal sentence with no markers at all."
        XCTAssertEqual(plain(s), s)
    }

    /// The whole point of the hand parser: it must stay dramatically cheaper than
    /// Foundation's markdown initializer on realistic agent-message prose.
    func testHandParserBeatsFoundationMarkdown() {
        let text = """
        Looked at the session-switch path. The merge lives in \
        `apps/iosApp/Supermux/Chat/ChatActivity.swift:39` and runs over the whole history.

        - It parses every timestamp
        - It sorts, then clusters consecutive **tool** rows

        See [the pane](https://example.com/ChatPane) and ~~old~~ notes.
        """
        let n = 200
        let opts = AttributedString.MarkdownParsingOptions(
            interpretedSyntax: .inlineOnlyPreservingWhitespace,
            failurePolicy: .returnPartiallyParsedIfPossible
        )

        let t0 = CFAbsoluteTimeGetCurrent()
        for _ in 0..<n {
            _ = MarkdownInline.nsAttributed(text, baseFont: baseFont, color: .smLabel, paragraph: nil)
        }
        let handMs = (CFAbsoluteTimeGetCurrent() - t0) * 1000 / Double(n)

        let t1 = CFAbsoluteTimeGetCurrent()
        for _ in 0..<n {
            _ = (try? AttributedString(markdown: text, options: opts)).map { NSAttributedString($0) }
                ?? NSAttributedString(string: text)
        }
        let foundationMs = (CFAbsoluteTimeGetCurrent() - t1) * 1000 / Double(n)

        print(String(format: "INLINE hand=%.4f ms  foundation=%.4f ms (%.0fx)",
                     handMs, foundationMs, foundationMs / max(handMs, 0.0001)))
        XCTAssertLessThan(handMs, foundationMs,
                          "hand parser should beat AttributedString(markdown:)")
    }
}
