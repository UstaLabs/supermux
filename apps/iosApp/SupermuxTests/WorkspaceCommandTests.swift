import XCTest
@testable import Supermux

final class WorkspaceCommandTests: XCTestCase {
    private func freshStore() -> UserDefaults { UserDefaults(suiteName: "wct.test.\(UUID().uuidString)")! }
    private func freshModel() -> WorkspaceLayoutModel { WorkspaceLayoutModel(store: freshStore()) }
    private let sid = "session-under-test"

    func testToggleSidebarFlipsCollapsed() {
        let m = freshModel()
        XCTAssertFalse(m.sidebarCollapsed)
        WorkspaceCommand.toggleSidebar.apply(to: m, session: sid)
        XCTAssertTrue(m.sidebarCollapsed)
        WorkspaceCommand.toggleSidebar.apply(to: m, session: sid)
        XCTAssertFalse(m.sidebarCollapsed)
    }

    func testToggleTerminalOpensFromDefault() {
        let m = freshModel()
        XCTAssertFalse(m.panes(for: sid).terminalOpen)
        WorkspaceCommand.toggleTerminal.apply(to: m, session: sid)
        XCTAssertTrue(m.panes(for: sid).terminalOpen)
    }

    func testToggleEditorOpensFromDefault() {
        let m = freshModel()
        XCTAssertFalse(m.panes(for: sid).editorOpen)
        WorkspaceCommand.toggleEditor.apply(to: m, session: sid)
        XCTAssertTrue(m.panes(for: sid).editorOpen)
    }

    func testToggleDisplayOpensFromDefault() {
        let m = freshModel()
        XCTAssertFalse(m.panes(for: sid).displayOpen)
        WorkspaceCommand.toggleDisplay.apply(to: m, session: sid)
        XCTAssertTrue(m.panes(for: sid).displayOpen)
    }

    func testToggleChatNoOpWhenNoWorkPaneOpen() {
        let m = freshModel()                               // chat on, no work pane
        XCTAssertTrue(m.panes(for: sid).chatOpen)
        WorkspaceCommand.toggleChat.apply(to: m, session: sid)
        XCTAssertTrue(m.panes(for: sid).chatOpen)          // can't hide the last visible pane
    }

    func testToggleChatHidesChatWhenWorkPaneOpen() {
        let m = freshModel()
        WorkspaceCommand.toggleEditor.apply(to: m, session: sid)   // open a work pane first
        XCTAssertTrue(m.panes(for: sid).editorOpen)
        WorkspaceCommand.toggleChat.apply(to: m, session: sid)
        XCTAssertFalse(m.panes(for: sid).chatOpen)
    }

    func testToggleChatReshowsWhenHidden() {
        let m = freshModel()
        WorkspaceCommand.toggleEditor.apply(to: m, session: sid)
        WorkspaceCommand.toggleChat.apply(to: m, session: sid)
        XCTAssertFalse(m.panes(for: sid).chatOpen)
        WorkspaceCommand.toggleChat.apply(to: m, session: sid)
        XCTAssertTrue(m.panes(for: sid).chatOpen)          // re-show always allowed
    }

    func testClosingLastWorkPaneReshowsChat() {
        let m = freshModel()
        WorkspaceCommand.toggleEditor.apply(to: m, session: sid)   // editor open
        WorkspaceCommand.toggleChat.apply(to: m, session: sid)     // hide chat
        XCTAssertFalse(m.panes(for: sid).chatOpen)
        XCTAssertTrue(m.panes(for: sid).editorOpen)
        WorkspaceCommand.toggleEditor.apply(to: m, session: sid)   // close the last work pane
        XCTAssertFalse(m.panes(for: sid).editorOpen)
        XCTAssertTrue(m.panes(for: sid).chatOpen)          // forced back on — detail not empty
        // Invariant: at least one pane is visible.
        let v = m.panes(for: sid)
        XCTAssertTrue(v.chatOpen || v.editorOpen || v.terminalOpen || v.displayOpen)
    }

    func testNewSessionApplyIsNoOp() {
        let m = freshModel()
        WorkspaceCommand.newSession.apply(to: m, session: sid)
        let v = m.panes(for: sid)
        XCTAssertTrue(v.chatOpen)
        XCTAssertFalse(v.editorOpen)
        XCTAssertFalse(v.terminalOpen)
        XCTAssertFalse(v.displayOpen)
        XCTAssertFalse(m.sidebarCollapsed)
    }

    /// The core of the per-session refactor: a pane toggle on session A must not touch session B.
    func testToggleOnOneSessionDoesNotAffectAnother() {
        let m = freshModel()
        let a = "A", b = "B"
        WorkspaceCommand.toggleTerminal.apply(to: m, session: a)
        WorkspaceCommand.toggleEditor.apply(to: m, session: a)
        XCTAssertTrue(m.panes(for: a).terminalOpen)
        XCTAssertTrue(m.panes(for: a).editorOpen)
        // B was never toggled — it stays at the fresh default (chat on, work panes off).
        XCTAssertFalse(m.panes(for: b).terminalOpen)
        XCTAssertFalse(m.panes(for: b).editorOpen)
        XCTAssertTrue(m.panes(for: b).chatOpen)
    }

    /// Hiding chat on A (allowed because A has a work pane) must not hide chat on B.
    func testHidingChatOnOneSessionDoesNotHideChatOnAnother() {
        let m = freshModel()
        let a = "A", b = "B"
        WorkspaceCommand.toggleEditor.apply(to: m, session: a)    // give A a work pane…
        WorkspaceCommand.toggleChat.apply(to: m, session: a)      // …then hide A's chat
        XCTAssertFalse(m.panes(for: a).chatOpen)
        XCTAssertTrue(m.panes(for: b).chatOpen)                   // B's chat is untouched
    }
}
