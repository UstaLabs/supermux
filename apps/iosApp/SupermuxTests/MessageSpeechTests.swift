import XCTest
@testable import Supermux

@MainActor
final class MessageSpeechTests: XCTestCase {
    func testPlainTextStripsMultilineMarkdownPrefixes() {
        let markdown = """
        # Heading
        - item
        > quote

        1. second
        """

        XCTAssertEqual(
            MessageSpeech.plainTextForSpeech(markdown),
            "Heading item quote second"
        )
    }
}
