import XCTest
@testable import Supermux

@MainActor
final class EditorStateTests: XCTestCase {

    /// Build a state whose `fsRead` echoes a deterministic body per path and whose
    /// `fsWrite` returns a fixed result (recording the last write).
    private func makeState(
        readSucceeds: Bool = true,
        writeSucceeds: Bool = true,
        lastWrite: ((String, String) -> Void)? = nil
    ) -> EditorState {
        EditorState(
            sessionId: "s",
            fsRead: { path in
                if readSucceeds { return "body:\(path)" }
                throw NSError(domain: "fs", code: 415, userInfo: [NSLocalizedDescriptionKey: "binary"])
            },
            fsWrite: { path, content in
                lastWrite?(path, content)
                return writeSucceeds
            }
        )
    }

    func testOpenFileAppendsAndActivates() async {
        let state = makeState()
        await state.openFile("a.swift")

        XCTAssertEqual(state.tabs.count, 1)
        XCTAssertEqual(state.tabs.first?.path, "a.swift")
        XCTAssertEqual(state.tabs.first?.content, "body:a.swift")
        XCTAssertEqual(state.activeTabPath, "a.swift")
        XCTAssertFalse(state.tabs.first?.isDirty ?? true)
        XCTAssertNil(state.loadingPath)
        XCTAssertNil(state.loadError)
    }

    func testOpeningExistingFileJustActivatesNoDuplicate() async {
        let state = makeState()
        await state.openFile("a.swift")
        await state.openFile("b.swift")
        await state.openFile("a.swift")

        XCTAssertEqual(state.tabs.count, 2)
        XCTAssertEqual(state.activeTabPath, "a.swift")
    }

    func testTwelveDistinctFilesKeepAllTwelveNoCap() async {
        let state = makeState()
        for i in 0..<12 {
            await state.openFile("file\(i).txt")
        }

        XCTAssertEqual(state.tabs.count, 12)
        XCTAssertEqual(state.tabs.first?.path, "file0.txt")
        XCTAssertEqual(state.tabs.last?.path, "file11.txt")
        XCTAssertEqual(state.activeTabPath, "file11.txt")
    }

    func testOpenFileFailureSetsLoadError() async {
        let state = makeState(readSucceeds: false)
        await state.openFile("x.bin")

        XCTAssertTrue(state.tabs.isEmpty)
        XCTAssertNil(state.activeTabPath)
        XCTAssertNotNil(state.loadError)
        XCTAssertNil(state.loadingPath)
    }

    func testCloseTabRemovesAndSelectsNeighbor() async {
        let state = makeState()
        await state.openFile("a.txt")
        await state.openFile("b.txt")
        await state.openFile("c.txt")

        // Close the active (last) tab → neighbor coerced to the new last.
        state.closeTab("c.txt")
        XCTAssertEqual(state.tabs.map(\.path), ["a.txt", "b.txt"])
        XCTAssertEqual(state.activeTabPath, "b.txt")

        // Close a middle/leading tab while it is active → same-index neighbor.
        state.activeTabPath = "a.txt"
        state.closeTab("a.txt")
        XCTAssertEqual(state.tabs.map(\.path), ["b.txt"])
        XCTAssertEqual(state.activeTabPath, "b.txt")

        // Closing the last remaining tab clears the active selection.
        state.closeTab("b.txt")
        XCTAssertTrue(state.tabs.isEmpty)
        XCTAssertNil(state.activeTabPath)
    }

    func testClosingInactiveTabKeepsActive() async {
        let state = makeState()
        await state.openFile("a.txt")
        await state.openFile("b.txt")
        state.activeTabPath = "b.txt"

        state.closeTab("a.txt")
        XCTAssertEqual(state.tabs.map(\.path), ["b.txt"])
        XCTAssertEqual(state.activeTabPath, "b.txt")
    }

    func testUpdateContentFlipsIsDirty() async {
        let state = makeState()
        await state.openFile("a.txt")
        XCTAssertFalse(state.activeTab?.isDirty ?? true)

        state.updateContent("a.txt", "changed")
        XCTAssertEqual(state.activeTab?.content, "changed")
        XCTAssertTrue(state.activeTab?.isDirty ?? false)
    }

    func testSaveActiveClearsDirtyWhenWriteSucceeds() async {
        var written: (path: String, content: String)?
        let state = makeState(writeSucceeds: true) { written = ($0, $1) }
        await state.openFile("a.txt")
        state.updateContent("a.txt", "edited")
        XCTAssertTrue(state.activeTab?.isDirty ?? false)

        await state.saveActive()

        XCTAssertFalse(state.activeTab?.isDirty ?? true)
        XCTAssertEqual(state.activeTab?.savedContent, "edited")
        XCTAssertEqual(state.activeTab?.content, "edited")
        XCTAssertFalse(state.saving)
        XCTAssertEqual(written?.path, "a.txt")
        XCTAssertEqual(written?.content, "edited")
    }

    func testSaveActiveKeepsDirtyWhenWriteFails() async {
        let state = makeState(writeSucceeds: false)
        await state.openFile("a.txt")
        state.updateContent("a.txt", "edited")

        await state.saveActive()

        XCTAssertTrue(state.activeTab?.isDirty ?? false)
        XCTAssertEqual(state.activeTab?.savedContent, "body:a.txt")
        XCTAssertFalse(state.saving)
    }

    func testReloadRefreshesContentAndClearsDirty() async {
        let state = makeState()
        await state.openFile("a.txt")
        state.updateContent("a.txt", "local edit")
        XCTAssertTrue(state.activeTab?.isDirty ?? false)

        await state.reload("a.txt")

        XCTAssertEqual(state.activeTab?.content, "body:a.txt")
        XCTAssertEqual(state.activeTab?.savedContent, "body:a.txt")
        XCTAssertFalse(state.activeTab?.isDirty ?? true)
    }

    func testSetScrollStoresPerTab() async {
        let state = makeState()
        await state.openFile("a.txt")
        state.setScroll("a.txt", 42)

        XCTAssertEqual(state.activeTab?.scrollTop, 42)
    }
}
