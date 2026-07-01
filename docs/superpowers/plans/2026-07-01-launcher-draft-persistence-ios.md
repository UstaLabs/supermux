# New Session Launcher Draft Persistence — iOS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Building/testing this plan's iOS changes requires the **mux:ios-development** skill family (this repo checkout is on Linux; iOS builds/tests run via the Linux toolchain or a remote Mac — see that skill for exact commands).

**Goal:** Persist `NewSessionView`'s project pick, worktree settings, agent/model choice, and typed message text to `UserDefaults`, so they survive leaving the New Session screen and coming back — or fully force-quitting and relaunching the app — and the draft (not the agent/model prefs) clears automatically once a session is actually created. Attachments are explicitly out of scope (ephemeral upload blobs — see the spec's Decisions §2).

**Architecture:** A new `@Observable` `LauncherStateStore` class (`apps/iosApp/Supermux/Sessions/LauncherStateStore.swift`), shaped like the existing `WorkspaceLayoutModel` (injectable `UserDefaults` for testability, `Codable` structs JSON-encoded on write). `NewSessionView` restores from it inside its existing `.task { }` block and persists ongoing changes via `.onChange` — except agent/model, which persist at their explicit Menu-pick call sites (see Task 2's header for why). Two existing `.task(id:)` blocks unconditionally reset `model`/`baseBranch`; since the restore itself happens inside an async `.task`, they need a restore-pending gate (not just a one-time-skip flag) so neither can clobber a just-restored value — see Task 2's header for why a simpler flag isn't enough.

**Tech Stack:** SwiftUI, `@Observable` (Observation framework), `UserDefaults` + `Codable`/`JSONEncoder`/`JSONDecoder`, XCTest.

**Spec:** `docs/superpowers/specs/2026-07-01-launcher-draft-persistence-design.md`

---

### Task 1: `LauncherStateStore`

**Files:**
- Create: `apps/iosApp/Supermux/Sessions/LauncherStateStore.swift`
- Test: `apps/iosApp/SupermuxTests/LauncherStateStoreTests.swift`

- [ ] **Step 1: Write the failing tests**

```swift
// apps/iosApp/SupermuxTests/LauncherStateStoreTests.swift
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
```

- [ ] **Step 2: Confirm the tests fail to compile**

Using the `mux:ios-development` skill's build/test recipe, run `SupermuxTests`. Expected: build FAILS — `LauncherStateStore`, `LauncherPrefs`, `LauncherDraft` are undefined. (There's no `LauncherStateStore.swift` yet, so this is a compile failure rather than a runtime test failure — that's fine, it's still "red" before Step 3 makes it "green.")

- [ ] **Step 3: Write the store**

```swift
// apps/iosApp/Supermux/Sessions/LauncherStateStore.swift
import Observation
import Foundation

/// Sticky New Session launcher preferences — the agent + its last-used model, keyed per agent.
/// Mirrors the web launcher's `cmux:launcher-prefs` localStorage shape (SessionLauncherView.vue).
struct LauncherPrefs: Codable, Equatable {
    var agent: String = "claude"
    var models: [String: String] = [:]
}

/// In-progress New Session launcher draft — cleared once a session is actually created.
/// `workdir` is nil when nothing was explicitly restored (so NewSessionView's own
/// `projects.first` fallback still applies). Mirrors the web launcher's `cmux:launcher-draft`.
struct LauncherDraft: Codable, Equatable {
    var workdir: String?
    var useWorktree: Bool = true
    var baseBranch: String = ""
    var text: String = ""
}

/// Persists New Session launcher state to UserDefaults so it survives navigating away and a full
/// app relaunch. Two lifecycles: `prefs` persists forever (pre-fills every future launch);
/// `draft` persists only until a session is created, then `clearDraft()`. Injectable `store` for
/// test isolation — same shape as `WorkspaceLayoutModel` (Shell/WorkspaceLayoutModel.swift).
@Observable final class LauncherStateStore {
    @ObservationIgnored private let store: UserDefaults
    private static let prefsKey = "cmux:launcher-prefs"
    private static let draftKey = "cmux:launcher-draft"

    var prefs: LauncherPrefs {
        didSet { if let data = try? JSONEncoder().encode(prefs) { store.set(data, forKey: Self.prefsKey) } }
    }
    var draft: LauncherDraft {
        didSet { if let data = try? JSONEncoder().encode(draft) { store.set(data, forKey: Self.draftKey) } }
    }

    init(store: UserDefaults = .standard) {
        self.store = store
        if let data = store.data(forKey: Self.prefsKey),
           let decoded = try? JSONDecoder().decode(LauncherPrefs.self, from: data) {
            prefs = decoded
        } else {
            prefs = LauncherPrefs()
        }
        if let data = store.data(forKey: Self.draftKey),
           let decoded = try? JSONDecoder().decode(LauncherDraft.self, from: data) {
            draft = decoded
        } else {
            draft = LauncherDraft()
        }
    }

    /// Clears the in-progress draft after a session is created. Leaves `prefs` untouched — a
    /// separate, forever-sticky lifecycle.
    func clearDraft() { draft = LauncherDraft() }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run `SupermuxTests` via the `mux:ios-development` build/test recipe.
Expected: `LauncherStateStoreTests` — 6 tests, all pass.

- [ ] **Step 5: Commit**

```bash
git add apps/iosApp/Supermux/Sessions/LauncherStateStore.swift apps/iosApp/SupermuxTests/LauncherStateStoreTests.swift
git commit -m "feat(ios): add LauncherStateStore for New Session prefs + draft persistence"
```

---

### Task 2: Wire `LauncherStateStore` into `NewSessionView`

**Files:**
- Modify: `apps/iosApp/Supermux/Sessions/NewSessionView.swift`

Two existing effects unconditionally reset state that this task now restores from persistence, so both need a guard — otherwise the restored value gets immediately overwritten the instant the view appears:
- `.task(id: agent)` (line 90-93) always does `model = nil` after fetching that agent's model list — including its very first run, for whatever `agent` a restore just set.
- `.task(id: workdir)` (line 99-104) always does `baseBranch = info?.currentBranch ?? ""` — including its very first run, for whatever `workdir` a restore just set.

**A plain "have I run once" flag is not enough**, because restoring `launcherState` into `agent`/`model`/`workdir` happens inside an `await`-containing `.task { }` (Step 2), not synchronously at view construction — so there's a real window where `.task(id: agent)` fires *first*, for the plain `"claude"` default, and could mark "I've run once" before the restore ever changes `agent` to the persisted value. A subsequent restore-driven change to `agent` would then look like *any other* change and get wrongly reset. The fix needs two flags per guarded effect, not one:
- **`launcherRestoring`** (shared, starts `true`) — blocks *both* effects' bodies outright until Step 2's restore has fully applied every field. Step 2 flips it to `false` as its last assignment, in the same synchronous, non-suspending run as the field restores themselves — SwiftUI batches those into one update, so the two guarded effects only ever see the *final*, fully-restored values the first time they're allowed to do anything at all.
- **`modelResetArmed` / `baseBranchResetArmed`** (one each, start `false`) — now safe to use as a plain "first real run vs. a later genuine change" gate, because that first real run is guaranteed (by `launcherRestoring`) to be the restore-settled one, not a stale default.

This combines two dependencies into one `.task(id:)` key via string interpolation — already this file's own pattern for the launcher-commands task (`.task(id: "\(agent)|\(workdir)")`, line 95).

Agent and model persist from their explicit Menu-pick actions, **not** a generic `onChange(of: agent)`/`onChange(of: model)`. Reason: `.task(id: agent)`'s `model = nil` reset (above) is a real state change too, and a generic `onChange(of: model)` can't tell "the user picked a model" apart from "the code just reset it because the agent changed" — persisting that reset would silently erase the previous agent's remembered model the instant you switch away from it. Persisting only at the two `Button` actions removes the ambiguity entirely. `workdir`/`useWorktree`/`baseBranch`/the composer's `draft` text don't have this problem — for those, "whatever's currently on screen" is exactly what the draft should remember, so persisting on every change (including a programmatic default) is correct, not a bug.

- [ ] **Step 1: Add the store and the guard flags**

Modify `apps/iosApp/Supermux/Sessions/NewSessionView.swift:23-28` (currently):

```swift
    @State private var repoInfo: RepoInfo?
    @State private var useWorktree = true
    @State private var baseBranch = ""
    @State private var worktreeSheet = false
    @State private var worktreeFetching = false
    @State private var fetchedRepos: Set<String> = []
```

Replace with:

```swift
    @State private var repoInfo: RepoInfo?
    @State private var useWorktree = true
    @State private var baseBranch = ""
    @State private var worktreeSheet = false
    @State private var worktreeFetching = false
    @State private var fetchedRepos: Set<String> = []
    // New Session draft persistence (survives navigation + relaunch). See Task 2's header note
    // for why all three flags exist — launcherRestoring gates both guarded effects until the
    // restore in Step 2 has fully landed; the other two then gate first-real-run vs. a later
    // genuine change.
    @State private var launcherState = LauncherStateStore()
    @State private var launcherRestoring = true
    @State private var modelResetArmed = false
    @State private var baseBranchResetArmed = false
```

- [ ] **Step 2: Restore state at the top of the existing `.task { }`**

Modify `apps/iosApp/Supermux/Sessions/NewSessionView.swift:62-79` (currently):

```swift
        .task {
            // No session yet (pre-spawn launcher): the broker's id-less /transcribe cleans the
            // draft off the global glossary/engine/model — the same AI correction the chat
            // composer gets, just without prior-message context.
            composer.setContext(ComposerContext(
                glossary: { (try? await broker.fetchGlossary()) ?? [] },
                cleanupTranscript: { try await broker.transcribeDraft(sessionId: nil, draft: $0) },
                audioFallbackTranscribe: { try await broker.transcribeAudio(sessionId: nil, data: $0, filename: $1) }
            ))
            await composer.loadGlossary()
            projects = await broker.projects()
            // Debug: force the initial project for headless screenshots (e.g. an eligible repo).
            if let forced = ProcessInfo.processInfo.environment["SM_WORKDIR"], !forced.isEmpty {
                workdir = forced
            } else if workdir.isEmpty {
                workdir = projects.first ?? "~"
            }
        }
```

Replace with:

```swift
        .task {
            // Restore persisted launcher state first, synchronously (no `await` on this path),
            // before any of the awaits below — agent/model/workdir/useWorktree/baseBranch/
            // composer.draft all start at their plain defaults (see their @State declarations
            // above), so this only has visible effect when a prior prefs/draft actually exists.
            // launcherRestoring flips false LAST, in this same synchronous run, so the two
            // guarded effects below never see a partially-restored state (see Task 2's header).
            // Validate against the known agent list — web's loadPrefs() does the same
            // (SessionLauncherView.vue:126) — so a future agent type added after this prefs
            // blob was written can't leave `agent` holding a value the Menu below has no
            // matching row for.
            let restoredAgent = launcherState.prefs.agent
            agent = agents.contains(restoredAgent) ? restoredAgent : "claude"
            model = launcherState.prefs.models[agent]
            if let draftWorkdir = launcherState.draft.workdir { workdir = draftWorkdir }
            useWorktree = launcherState.draft.useWorktree
            baseBranch = launcherState.draft.baseBranch
            composer.draft = launcherState.draft.text
            launcherRestoring = false
            // No session yet (pre-spawn launcher): the broker's id-less /transcribe cleans the
            // draft off the global glossary/engine/model — the same AI correction the chat
            // composer gets, just without prior-message context.
            composer.setContext(ComposerContext(
                glossary: { (try? await broker.fetchGlossary()) ?? [] },
                cleanupTranscript: { try await broker.transcribeDraft(sessionId: nil, draft: $0) },
                audioFallbackTranscribe: { try await broker.transcribeAudio(sessionId: nil, data: $0, filename: $1) }
            ))
            await composer.loadGlossary()
            projects = await broker.projects()
            // Debug: force the initial project for headless screenshots (e.g. an eligible repo).
            if let forced = ProcessInfo.processInfo.environment["SM_WORKDIR"], !forced.isEmpty {
                workdir = forced
            } else if workdir.isEmpty {
                workdir = projects.first ?? "~"
            }
        }
```

- [ ] **Step 3: Guard the agent-triggered model reset**

Modify `apps/iosApp/Supermux/Sessions/NewSessionView.swift:90-93` (currently):

```swift
        .task(id: agent) {
            models = await broker.listModels(agent)
            model = nil
        }
```

Replace with:

```swift
        .task(id: "\(agent)|\(launcherRestoring)") {
            guard !launcherRestoring else { return }
            models = await broker.listModels(agent)
            // First real run (launcherRestoring just became false) corresponds to whatever
            // agent Step 2 restored, and model was already restored alongside it — skip the
            // reset. A genuine agent switch (this task re-running because `agent` changed,
            // with launcherRestoring already false) still resets to Default, matching today's
            // behavior. See Task 2's header note for why launcherRestoring must also gate this.
            if modelResetArmed {
                model = nil
            } else {
                modelResetArmed = true
            }
        }
```

- [ ] **Step 4: Guard the workdir-triggered base-branch reset**

Modify `apps/iosApp/Supermux/Sessions/NewSessionView.swift:99-104` (currently):

```swift
        .task(id: workdir) {
            guard !workdir.isEmpty else { repoInfo = nil; return }
            let info = await broker.repoInfo(workdir)
            repoInfo = info
            baseBranch = info?.currentBranch ?? ""
        }
```

Replace with:

```swift
        .task(id: "\(workdir)|\(launcherRestoring)") {
            guard !launcherRestoring else { repoInfo = nil; return }
            guard !workdir.isEmpty else { repoInfo = nil; return }
            let info = await broker.repoInfo(workdir)
            repoInfo = info
            if baseBranchResetArmed {
                baseBranch = info?.currentBranch ?? ""
            } else {
                baseBranchResetArmed = true
                if baseBranch.isEmpty { baseBranch = info?.currentBranch ?? "" }
            }
        }
```

- [ ] **Step 5: Persist workdir/worktree/base-branch/text on every change**

Modify `apps/iosApp/Supermux/Sessions/NewSessionView.swift:123` (currently):

```swift
        .onChange(of: composer.refocusToken) { _, _ in composing = true }
```

Add immediately after it:

```swift
        .onChange(of: workdir) { _, new in
            launcherState.draft.workdir = new.isEmpty ? nil : new
        }
        .onChange(of: useWorktree) { _, new in
            launcherState.draft.useWorktree = new
        }
        .onChange(of: baseBranch) { _, new in
            launcherState.draft.baseBranch = new
        }
        .onChange(of: composer.draft) { _, new in
            launcherState.draft.text = new
        }
```

(These fire once, harmlessly, when Step 2's restore assigns the same value right back — UserDefaults writes are cheap/local, unlike a network sync, so no extra "already loaded" gate is needed here, unlike Steps 3-4's guards which prevent a genuinely *wrong* value, not just a redundant write of the same one.)

- [ ] **Step 6: Persist agent/model only at their explicit pick sites**

Modify `apps/iosApp/Supermux/Sessions/NewSessionView.swift:213-222` (currently):

```swift
                Menu {
                    ForEach(agents, id: \.self) { a in Button(a.capitalized) { agent = a } }
                } label: {
                    HStack(spacing: 5) {
                        AgentLogo(agent: agent, size: 18)
                        Text(agent.capitalized).font(.subheadline.weight(.medium)).lineLimit(1)
                        Image(systemName: "chevron.down").font(.caption2)
                    }.foregroundStyle(.primary)
                }
```

Replace with:

```swift
                Menu {
                    ForEach(agents, id: \.self) { a in
                        Button(a.capitalized) { agent = a; launcherState.prefs.agent = a }
                    }
                } label: {
                    HStack(spacing: 5) {
                        AgentLogo(agent: agent, size: 18)
                        Text(agent.capitalized).font(.subheadline.weight(.medium)).lineLimit(1)
                        Image(systemName: "chevron.down").font(.caption2)
                    }.foregroundStyle(.primary)
                }
```

Modify `apps/iosApp/Supermux/Sessions/NewSessionView.swift:226-234` (currently):

```swift
                Menu {
                    Button("Default") { model = nil }
                    ForEach(models, id: \.id) { m in Button(m.displayName) { model = m.id } }
                } label: {
                    HStack(spacing: 4) {
                        Text(modelLabel).font(.subheadline.weight(.medium)).lineLimit(1)
                        Image(systemName: "chevron.down").font(.caption2)
                    }.foregroundStyle(.secondary)
                }
```

Replace with:

```swift
                Menu {
                    Button("Default") {
                        model = nil
                        launcherState.prefs.models.removeValue(forKey: agent)
                    }
                    ForEach(models, id: \.id) { m in
                        Button(m.displayName) {
                            model = m.id
                            launcherState.prefs.models[agent] = m.id
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(modelLabel).font(.subheadline.weight(.medium)).lineLimit(1)
                        Image(systemName: "chevron.down").font(.caption2)
                    }.foregroundStyle(.secondary)
                }
```

- [ ] **Step 7: Clear the draft when a session is created**

Modify `apps/iosApp/Supermux/Sessions/NewSessionView.swift:264-266` (currently):

```swift
    private func spawn() {
        spawning = true
        let (raw, toUpload) = composer.consume()
```

Replace with:

```swift
    private func spawn() {
        spawning = true
        launcherState.clearDraft()
        let (raw, toUpload) = composer.consume()
```

- [ ] **Step 8: Regenerate the Xcode project and build**

The project is XcodeGen with directory-globbed sources — `LauncherStateStore.swift` (Task 1) is picked up automatically, but re-run generation before building if your workflow requires it (see the `mux:ios-development` skill). Build `Supermux` + run `SupermuxTests` via that skill's recipe.
Expected: build succeeds, all tests (including `LauncherStateStoreTests`) pass.

- [ ] **Step 9: Commit**

```bash
git add apps/iosApp/Supermux/Sessions/NewSessionView.swift
git commit -m "feat(ios): restore + persist New Session draft (project, worktree, agent/model, text)"
```

---

### Task 3: Manual verification

No existing automated coverage exercises `ChatPane`'s per-session draft persistence either (per `2026-06-24-ios-composer-reuse-design.md`'s testing section), so there's no precedent to mirror for `NewSessionView`'s full wiring — `LauncherStateStoreTests` (Task 1) covers the actual persistence logic; this is a manual pass over the view, via the `mux:ios-development` skill's simulator/device recipe.

- [ ] **Step 1: Smoke-test restore across in-app navigation**

1. Open New Session. Pick a specific project that's a git repo, open the worktree sheet and pick a base branch that is **not** the repo's current branch, pick a specific agent + model (not the defaults), and type a message like `testing draft persistence`.
2. Navigate away (back to the session list) and open New Session again.
3. Confirm: same project, same base branch (not silently reverted to the repo's actual current branch), same agent + model, and the typed text is still there.

- [ ] **Step 2: Smoke-test restore across a full relaunch**

1. With the same in-progress draft from Step 1, force-quit and relaunch the app.
2. Confirm the draft is still restored.

- [ ] **Step 3: Smoke-test agent switching doesn't erase the other agent's remembered model**

1. On New Session, pick agent Claude and a specific (non-default) model.
2. Switch to agent Codex — confirm the model resets to "Default" (existing behavior, unchanged).
3. Switch back to Claude — confirm your earlier model choice for Claude is still selected (this is the scenario Task 2 Step 6 exists for; a naive `onChange(of: model)` implementation would fail this check by erasing Claude's saved model the moment you switched away from it).

- [ ] **Step 4: Smoke-test clearing on submit**

1. From a restored draft, submit to actually create a session.
2. Open New Session again.
3. Confirm: project reverts to the default (`projects.first`), worktree/base-branch/text are cleared — but agent/model still show your last pick (unaffected by the clear).
