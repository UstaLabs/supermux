import XCTest
@testable import Supermux

final class LauncherStateStoreTests: XCTestCase {
    private func freshStore() -> UserDefaults { UserDefaults(suiteName: "lss.test.\(UUID().uuidString)")! }

    func testDefaultsWhenStorageIsEmpty() {
        let s = LauncherStateStore(store: freshStore())
        XCTAssertEqual(s.prefs.agent, "claude")
        XCTAssertTrue(s.prefs.models.isEmpty)
        XCTAssertNil(s.draft.workdir)
        XCTAssertTrue(s.draft.useWorktree)
        XCTAssertEqual(s.draft.baseBranch, "")
        XCTAssertEqual(s.draft.text, "")
    }

    func testPrefsPersistAndReload() {
        let store = freshStore()
        let a = LauncherStateStore(store: store)
        a.prefs = LauncherPrefs(agent: "codex", models: ["codex": "gpt-5.4"])
        let b = LauncherStateStore(store: store)
        XCTAssertEqual(b.prefs.agent, "codex")
        XCTAssertEqual(b.prefs.models["codex"], "gpt-5.4")
    }

    func testDraftPersistsAndReloads() {
        let store = freshStore()
        let a = LauncherStateStore(store: store)
        a.draft = LauncherDraft(workdir: "/home/user/project", useWorktree: false, baseBranch: "feature/x", text: "fix the bug")
        let b = LauncherStateStore(store: store)
        XCTAssertEqual(b.draft.workdir, "/home/user/project")
        XCTAssertFalse(b.draft.useWorktree)
        XCTAssertEqual(b.draft.baseBranch, "feature/x")
        XCTAssertEqual(b.draft.text, "fix the bug")
    }

    func testClearDraftResetsToDefaultsAndPersists() {
        let store = freshStore()
        let a = LauncherStateStore(store: store)
        a.draft = LauncherDraft(workdir: "/home/user/project", useWorktree: false, baseBranch: "feature/x", text: "fix the bug")
        a.clearDraft()
        XCTAssertNil(a.draft.workdir)
        XCTAssertTrue(a.draft.useWorktree)
        XCTAssertEqual(a.draft.text, "")

        let b = LauncherStateStore(store: store)
        XCTAssertNil(b.draft.workdir)
        XCTAssertEqual(b.draft.text, "")
    }

    func testClearDraftLeavesPrefsUntouched() {
        let store = freshStore()
        let a = LauncherStateStore(store: store)
        a.prefs = LauncherPrefs(agent: "codex", models: ["codex": "gpt-5.4"])
        a.draft = LauncherDraft(workdir: "/home/user/project", useWorktree: true, baseBranch: "", text: "hello")
        a.clearDraft()
        XCTAssertEqual(a.prefs.agent, "codex")
        XCTAssertEqual(a.prefs.models["codex"], "gpt-5.4")
    }

    func testCorruptStoredDataFallsBackToDefaults() {
        let store = freshStore()
        store.set(Data([0x00, 0x01, 0x02]), forKey: "cmux:launcher-prefs")
        store.set(Data([0x00, 0x01, 0x02]), forKey: "cmux:launcher-draft")
        let s = LauncherStateStore(store: store)
        XCTAssertEqual(s.prefs.agent, "claude")
        XCTAssertNil(s.draft.workdir)
    }
}
