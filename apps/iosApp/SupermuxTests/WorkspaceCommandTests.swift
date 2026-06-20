import XCTest
@testable import Supermux

final class WorkspaceCommandTests: XCTestCase {
    private func freshStore() -> UserDefaults { UserDefaults(suiteName: "wct.test.\(UUID().uuidString)")! }
    private func freshModel() -> WorkspaceLayoutModel { WorkspaceLayoutModel(store: freshStore()) }

    func testToggleSidebarFlipsCollapsed() {
        let m = freshModel()
        XCTAssertFalse(m.sidebarCollapsed)
        WorkspaceCommand.toggleSidebar.apply(to: m)
        XCTAssertTrue(m.sidebarCollapsed)
        WorkspaceCommand.toggleSidebar.apply(to: m)
        XCTAssertFalse(m.sidebarCollapsed)
    }

    func testToggleTerminalOpensFromDefault() {
        let m = freshModel()
        XCTAssertFalse(m.terminalOpen)
        WorkspaceCommand.toggleTerminal.apply(to: m)
        XCTAssertTrue(m.terminalOpen)
    }

    func testToggleEditorOpensFromDefault() {
        let m = freshModel()
        XCTAssertFalse(m.editorOpen)
        WorkspaceCommand.toggleEditor.apply(to: m)
        XCTAssertTrue(m.editorOpen)
    }

    func testToggleDisplayOpensFromDefault() {
        let m = freshModel()
        XCTAssertFalse(m.displayOpen)
        WorkspaceCommand.toggleDisplay.apply(to: m)
        XCTAssertTrue(m.displayOpen)
    }

    func testToggleChatNoOpWhenNoWorkPaneOpen() {
        let m = freshModel()                               // chat on, no work pane
        XCTAssertTrue(m.chatOpen)
        WorkspaceCommand.toggleChat.apply(to: m)
        XCTAssertTrue(m.chatOpen)                          // can't hide the last visible pane
    }

    func testToggleChatHidesChatWhenWorkPaneOpen() {
        let m = freshModel()
        WorkspaceCommand.toggleEditor.apply(to: m)        // open a work pane first
        XCTAssertTrue(m.editorOpen)
        WorkspaceCommand.toggleChat.apply(to: m)
        XCTAssertFalse(m.chatOpen)
    }

    func testToggleChatReshowsWhenHidden() {
        let m = freshModel()
        WorkspaceCommand.toggleEditor.apply(to: m)
        WorkspaceCommand.toggleChat.apply(to: m)
        XCTAssertFalse(m.chatOpen)
        WorkspaceCommand.toggleChat.apply(to: m)
        XCTAssertTrue(m.chatOpen)                          // re-show always allowed
    }

    func testClosingLastWorkPaneReshowsChat() {
        let m = freshModel()
        WorkspaceCommand.toggleEditor.apply(to: m)        // editor open
        WorkspaceCommand.toggleChat.apply(to: m)          // hide chat
        XCTAssertFalse(m.chatOpen)
        XCTAssertTrue(m.editorOpen)
        WorkspaceCommand.toggleEditor.apply(to: m)        // close the last work pane
        XCTAssertFalse(m.editorOpen)
        XCTAssertTrue(m.chatOpen)                          // forced back on — detail not empty
        // Invariant: at least one pane is visible.
        XCTAssertTrue(m.chatOpen || m.editorOpen || m.terminalOpen || m.displayOpen)
    }

    func testNewSessionApplyIsNoOp() {
        let m = freshModel()
        WorkspaceCommand.newSession.apply(to: m)
        XCTAssertTrue(m.chatOpen)
        XCTAssertFalse(m.editorOpen)
        XCTAssertFalse(m.terminalOpen)
        XCTAssertFalse(m.displayOpen)
        XCTAssertFalse(m.sidebarCollapsed)
    }
}
