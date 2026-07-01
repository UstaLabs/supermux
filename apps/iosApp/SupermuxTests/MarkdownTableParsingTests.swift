import XCTest
@testable import Supermux

/// Unit tests for the GFM table parsing added to the native markdown renderer
/// (`MarkdownView.swift`). The parser turns table markdown into `.table(MDTable)`
/// blocks; rendering (the scrollable grid) is verified visually on-device.
final class MarkdownTableParsingTests: XCTestCase {

    private func tables(_ md: String) -> [MDTable] {
        parseMarkdown(md).compactMap { block in
            if case .table(let t) = block { return t }
            return nil
        }
    }

    func testParsesBasicTable() {
        let md = """
        | Name | Age |
        |------|-----|
        | Alice | 30 |
        | Bob | 25 |
        """
        let ts = tables(md)
        XCTAssertEqual(ts.count, 1)
        XCTAssertEqual(ts[0].headers, ["Name", "Age"])
        XCTAssertEqual(ts[0].columnCount, 2)
        XCTAssertEqual(ts[0].rows, [["Alice", "30"], ["Bob", "25"]])
    }

    func testParsesColumnAlignment() {
        let md = """
        | L | C | R |
        |:--|:-:|--:|
        | a | b | c |
        """
        XCTAssertEqual(tables(md)[0].aligns, [.leading, .center, .trailing])
    }

    func testDefaultAlignmentIsLeading() {
        let md = "| A | B |\n|---|---|\n| 1 | 2 |"
        XCTAssertEqual(tables(md)[0].aligns, [.leading, .leading])
    }

    func testTableWithoutOuterPipes() {
        let md = "A | B\n--- | ---\n1 | 2"
        let ts = tables(md)
        XCTAssertEqual(ts.count, 1)
        XCTAssertEqual(ts[0].headers, ["A", "B"])
        XCTAssertEqual(ts[0].rows, [["1", "2"]])
    }

    func testEscapedPipeIsLiteral() {
        let md = "| a | b |\n|---|---|\n| x \\| y | z |"
        XCTAssertEqual(tables(md)[0].rows, [["x | y", "z"]])
    }

    func testRaggedRowsArePaddedToColumnCount() {
        let md = "| a | b | c |\n|---|---|---|\n| 1 | 2 |"
        XCTAssertEqual(tables(md)[0].rows, [["1", "2", ""]])
    }

    func testOverlongRowIsTruncatedToColumnCount() {
        let md = "| a | b |\n|---|---|\n| 1 | 2 | 3 |"
        XCTAssertEqual(tables(md)[0].rows, [["1", "2"]])
    }

    func testHeaderOnlyTableHasNoRows() {
        let md = "| A | B |\n|---|---|"
        let ts = tables(md)
        XCTAssertEqual(ts.count, 1)
        XCTAssertEqual(ts[0].headers, ["A", "B"])
        XCTAssertTrue(ts[0].rows.isEmpty)
    }

    func testInlineMarkdownInCellsIsPreservedRaw() {
        // The parser keeps raw cell text; inline styling is applied at render time.
        let md = "| col |\n|---|\n| **bold** `code` |"
        XCTAssertEqual(tables(md)[0].rows, [["**bold** `code`"]])
    }

    func testNotATableWithoutDelimiterRow() {
        let md = "| just | text |\nno delimiter here"
        XCTAssertTrue(tables(md).isEmpty)
    }

    func testPipesInProseDoNotBecomeATable() {
        let md = "Run `a | b | c` in the shell to pipe output."
        XCTAssertTrue(tables(md).isEmpty)
    }

    func testTableSurroundedByParagraphs() {
        let md = """
        Here is the data:
        | A | B |
        |---|---|
        | 1 | 2 |
        And that's the summary.
        """
        let blocks = parseMarkdown(md)
        XCTAssertEqual(tables(md).count, 1)
        guard case .paragraph(let first) = blocks.first else {
            return XCTFail("expected leading paragraph, got \(String(describing: blocks.first))")
        }
        XCTAssertTrue(first.contains("Here is the data"))
        guard case .paragraph(let last) = blocks.last else {
            return XCTFail("expected trailing paragraph, got \(String(describing: blocks.last))")
        }
        XCTAssertTrue(last.contains("summary"))
    }

    func testTableImmediatelyAfterTextNoBlankLine() {
        // Unlike the web (marked needs a blank line), our parser detects a table
        // that directly follows a text line.
        let md = "Results:\n| A | B |\n|---|---|\n| 1 | 2 |"
        XCTAssertEqual(tables(md).count, 1)
    }

    func testMultipleTablesInOneMessage() {
        let md = """
        | A | B |
        |---|---|
        | 1 | 2 |

        Some text between.

        | C | D |
        |---|---|
        | 3 | 4 |
        """
        XCTAssertEqual(tables(md).count, 2)
    }

    func testGroupSegmentsSplitsAtTables() {
        let md = "intro\n| A |\n|---|\n| 1 |\noutro"
        let segments = groupSegments(parseMarkdown(md))
        // text-run, table, text-run
        XCTAssertEqual(segments.count, 3)
        if case .text = segments[0] {} else { XCTFail("segment 0 should be text") }
        if case .table = segments[1] {} else { XCTFail("segment 1 should be table") }
        if case .text = segments[2] {} else { XCTFail("segment 2 should be text") }
    }

    func testCodeFenceWithPipesIsNotParsedAsTable() {
        let md = """
        ```
        | not | a | table |
        |-----|---|-------|
        ```
        """
        XCTAssertTrue(tables(md).isEmpty)
    }
}
