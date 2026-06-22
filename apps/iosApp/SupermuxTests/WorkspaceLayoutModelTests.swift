import XCTest
@testable import Supermux

final class WorkspaceLayoutModelTests: XCTestCase {
    private func freshStore() -> UserDefaults { UserDefaults(suiteName: "wlm.test.\(UUID().uuidString)")! }

    func testDefaultsMatchPWA() {
        let m = WorkspaceLayoutModel(store: freshStore())
        XCTAssertEqual(m.chatPct, 25, accuracy: 0.001)
        XCTAssertEqual(m.editorTermPct, 75, accuracy: 0.001)
        XCTAssertEqual(m.workDisplayPct, 55, accuracy: 0.001)
        XCTAssertEqual(m.sidebarWidth, 320, accuracy: 0.001)
        XCTAssertFalse(m.sidebarCollapsed)
    }

    func testRatiosClampToPWABounds() {
        let m = WorkspaceLayoutModel(store: freshStore())
        m.chatPct = 5;    XCTAssertEqual(m.chatPct, 20, accuracy: 0.001)
        m.chatPct = 95;   XCTAssertEqual(m.chatPct, 80, accuracy: 0.001)
        m.workDisplayPct = 10; XCTAssertEqual(m.workDisplayPct, 25, accuracy: 0.001)
        m.workDisplayPct = 99; XCTAssertEqual(m.workDisplayPct, 75, accuracy: 0.001)
        m.editorTermPct = 5;   XCTAssertEqual(m.editorTermPct, 20, accuracy: 0.001)
        m.editorTermPct = 95;  XCTAssertEqual(m.editorTermPct, 80, accuracy: 0.001)
        m.sidebarWidth = 50;   XCTAssertEqual(m.sidebarWidth, 220, accuracy: 0.001)
        m.sidebarWidth = 9999; XCTAssertEqual(m.sidebarWidth, 560, accuracy: 0.001)
    }

    func testPersistsAndReloads() {
        let store = freshStore()
        let a = WorkspaceLayoutModel(store: store)
        a.chatPct = 40
        a.sidebarCollapsed = true
        let b = WorkspaceLayoutModel(store: store)
        XCTAssertEqual(b.chatPct, 40, accuracy: 0.001)
        XCTAssertTrue(b.sidebarCollapsed)
    }

    func testClampedValuePersists() {
        let store = freshStore()
        let a = WorkspaceLayoutModel(store: store)
        a.chatPct = 999                       // clamps to 80, and the CLAMPED value must persist
        let b = WorkspaceLayoutModel(store: store)
        XCTAssertEqual(b.chatPct, 80, accuracy: 0.001)
    }

    // MARK: - Per-session pane visibility

    func testPanesDefaultForUnseenSession() {
        let m = WorkspaceLayoutModel(store: freshStore())
        let v = m.panes(for: "never-touched")
        XCTAssertTrue(v.chatOpen)             // chat defaults on…
        XCTAssertFalse(v.editorOpen)          // …work panes default off (PWA defaultPanelState)
        XCTAssertFalse(v.terminalOpen)
        XCTAssertFalse(v.displayOpen)
    }

    func testPanesReadOnlyAccessorDoesNotInsert() {
        let store = freshStore()
        let a = WorkspaceLayoutModel(store: store)
        _ = a.panes(for: "ephemeral")         // reading a default must NOT persist anything
        let b = WorkspaceLayoutModel(store: store)
        // A fresh model still returns the default (the read above didn't write a customized value).
        XCTAssertEqual(b.panes(for: "ephemeral"), PaneVisibility())
    }

    func testSetPanesPersistsPerSession() {
        let store = freshStore()
        let a = WorkspaceLayoutModel(store: store)
        var v = a.panes(for: "s1")
        v.terminalOpen = true
        a.setPanes(v, for: "s1")
        // A new model over the same store reloads the per-session map from UserDefaults.
        let b = WorkspaceLayoutModel(store: store)
        XCTAssertTrue(b.panes(for: "s1").terminalOpen)
        XCTAssertTrue(b.panes(for: "s1").chatOpen)
    }

    func testTwoSessionsHoldIndependentState() {
        let m = WorkspaceLayoutModel(store: freshStore())
        var a = m.panes(for: "A")
        a.editorOpen = true
        a.chatOpen = false
        m.setPanes(a, for: "A")

        var b = m.panes(for: "B")
        b.displayOpen = true
        m.setPanes(b, for: "B")

        // Each session keeps its own pane state; neither leaks into the other.
        XCTAssertTrue(m.panes(for: "A").editorOpen)
        XCTAssertFalse(m.panes(for: "A").chatOpen)
        XCTAssertFalse(m.panes(for: "A").displayOpen)

        XCTAssertFalse(m.panes(for: "B").editorOpen)
        XCTAssertTrue(m.panes(for: "B").chatOpen)     // B untouched chat → still default on
        XCTAssertTrue(m.panes(for: "B").displayOpen)
    }
}
