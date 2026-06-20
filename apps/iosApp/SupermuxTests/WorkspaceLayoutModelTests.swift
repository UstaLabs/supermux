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
        XCTAssertTrue(m.chatOpen)
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
}
