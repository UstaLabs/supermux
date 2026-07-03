# New Session launcher — persist draft state across navigation + relaunch — Design (2026-07-01)

- **Date:** 2026-07-01
- **Status:** Approved (decisions confirmed with user: exclude attachments, two-key split, all 3 platforms)
- **Area:** `src/web-app` (Vue), `apps/iosApp` (SwiftUI), `apps/android` (Compose)
- **Goal:** Leaving the "New Session" launcher (web `/new`, iOS `NewSessionView`, Android `SessionLauncherScreen`) and coming back — or fully closing and reopening the app — should restore what you had: picked project, worktree settings, and typed message. Today only web persists agent+model; everything else, on every platform, resets to defaults the instant you navigate away.

## Context

- Web's `SessionLauncherView.vue:59-132` already persists **agent + model** to `localStorage['cmux:launcher-prefs']` and reloads them on every mount. Nothing else on that page persists.
- iOS and Android persist **nothing** on their launcher screens today — not even agent/model. `NewSessionView.swift:13-40` is plain `@State`; `SessionLauncherScreen.kt:73-95` is plain `remember{}`.
- A week-old spec, `2026-06-24-ios-composer-reuse-design.md`, unified the iOS chat and launcher composers behind one `ComposerModel` (`apps/iosApp/Supermux/Chat/Composer/ComposerModel.swift`) and explicitly punted on this: "Draft persistence stays a chat concern... The launcher keeps no persistence (matches today)" (§5.3). This design is the deferred follow-up.
- All three platforms already have a mature, proven pattern for persisting **per-session** chat drafts (an *existing* session's unsent composer text): web's `drafts.ts` Pinia store + `PromptInputDraftSync.vue` (server-synced over WS `draft_set`/`draft_clear`, cross-device); iOS's `UserDefaults["cmux:draft:<id>"]` in `ChatPane.swift:65-68,129`; Android's Jetpack DataStore (`chat_drafts` store, `AppViewModel.kt:76,323-334`) wired via debounced `LaunchedEffect` in `ChatScreen.kt:297-308`.
- The launcher has no session id yet, so it can't reuse those stores directly — but it should mirror their *pattern* per platform: UserDefaults on iOS, DataStore on Android, localStorage on web (Pinia store shaped like `editorSettings.ts`). No server sync (see Decisions).

## Decisions

1. **Two kinds of state, two lifecycles:**
   - **Sticky prefs** — agent + model. Persist forever; pre-fill every future launch. (Web already does this; iOS/Android need it added.)
   - **Draft** — workdir (only if explicitly picked), worktree toggle, base branch, typed message text. Persists across navigation/relaunch, but **clears the moment a session is actually created from it**.
2. **Attachments excluded.** Ephemeral upload blobs; even the mature per-session chat-draft sync doesn't persist these.
3. **Local device storage only** — localStorage / UserDefaults / DataStore. No server sync, no cross-device follow (unlike the existing per-session web chat drafts, which do sync cross-device). Flagged as a possible future upgrade, not built now.
4. **Two storage keys per platform**, not one blob: `cmux:launcher-prefs` (sticky, web's existing shape/key reused) and `cmux:launcher-draft` (new).
5. **No new shared KMP abstraction.** `apps/shared` has no general key-value settings layer today (only an encrypted-token-specific `SecureTokenStore`), and `NewSessionView.swift` doesn't consume KMP state at all. Building a shared abstraction just for this is a bigger architectural change than the feature needs — three native implementations instead, each mirroring that platform's own proven chat-draft pattern. Revisit if more shared persisted state accumulates later.
6. **Restoring a draft's workdir freezes it** using each platform's existing "don't auto-follow recency" mechanism — web/Android's `workdirTouched` flag (`SessionLauncherView.vue:52`, `SessionLauncherScreen.kt:74`), which `chooseDefaultProject` (`default-project.ts:22`) and the Android `LaunchedEffect(sessions)` (`SessionLauncherScreen.kt:106-110`) already respect. iOS has no recency-follow to fight (`NewSessionView.swift:73-78` only sets `workdir` once, if empty), so restoring there is just a direct assignment.

## Shape

```
LauncherPrefs { agent: string; models: { [agent]: string } }        // sticky — web's existing shape, unchanged
LauncherDraft { workdir: string | null; useWorktree: bool; baseBranch: string; text: string }
// workdir: null means "nothing explicit was in flight" — don't override the recency default on restore.
```

## Web (`src/web-app`)

- New `src/web-app/src/stores/launcherDraft.ts` — Pinia store shaped like `editorSettings.ts:44-57` (reactive state + deep `watch` → `localStorage['cmux:launcher-draft']`, defensive `load()`/`defaults()` parsing). Exposes `state`, `setWorkdir`, `setWorktree`, `setBaseBranch`, `setText`, `clear()`.
- New renderless `src/web-app/src/components/LauncherDraftSync.vue`, mirroring `PromptInputDraftSync.vue:1-63`: lives inside `<PromptInput>`, reads `textInput` via `usePromptInput()`, watches it (debounced, same 800ms), and writes into the new store instead of sending a WS frame. No cross-device apply-remote branch needed (no server sync).
- `SessionLauncherView.vue`:
  - On setup, alongside the existing `loadPrefs()` (line 124), load the draft. If `draft.workdir` is non-null: `workdir.value = draft.workdir; workdirTouched.value = true`. Always restore `useWorktree.value`/`baseBranch.value` from the draft (harmless if the repo turns out ineligible — the worktree picker just won't render).
  - Pass the draft's `text` as `<PromptInput initial-input="...">` (prop already exists, `PromptInput.vue:18`) instead of leaving it empty.
  - Add `<LauncherDraftSync />` next to the existing `<LauncherComposeLock />` (`SessionLauncherView.vue:310`).
  - `watch([workdir when touched, useWorktree, baseBranch], ...)` → store the draft's non-text fields (debounced, matching the store's own deep-watch or a local debounce — implementer's call).
  - In `onPromptSubmit`, right after `sessions.add(...)` succeeds (`SessionLauncherView.vue:233`), call the draft store's `clear()`. Leave the existing `savePrefs()` (agent/model) untouched — different lifecycle, already correct.

## iOS (`apps/iosApp`)

- `NewSessionView.swift` gains UserDefaults-backed persistence, mirroring `ChatPane.swift:65-68,129`'s `onChange` pattern exactly (a small helper type or just inline `onChange` handlers — either is ~20 lines, implementer's call). No debounce needed — `ChatPane` saves on every `onChange` since UserDefaults writes are cheap and local (unlike Android's DataStore file I/O or a network send), so the launcher mirrors that as-is.
- **Sticky prefs** (new): `.onChange(of: agent)` / `.onChange(of: model)` write a JSON-encoded `Codable` struct to `UserDefaults` key `cmux:launcher-prefs` (same shape as web). Load before the `.task(id: agent)` block (`NewSessionView.swift:90-93`) that depends on `agent`, so the restored agent drives the first model fetch.
- **Draft**: `.onChange(of: workdir)` / `.onChange(of: useWorktree)` / `.onChange(of: baseBranch)` write to `UserDefaults` key `cmux:launcher-draft`. Since iOS's default-workdir logic only fires when `workdir.isEmpty` (`NewSessionView.swift:73-78`), restoring is a direct `workdir = restored` before that fallback check — no "touched" flag needed.
- **Draft text**: pass the loaded draft's text as `ComposerModel(context:initialDraft:)`'s `initialDraft:` parameter (constructor already supports this, `ComposerModel.swift:45-48`) instead of the current implicit `""` at `NewSessionView.swift:32-36`. Add `.onChange(of: composer.draft)` to persist keystrokes into the same `cmux:launcher-draft` blob — identical mechanism to `ChatPane`'s per-session persistence, just keyed globally instead of per-session.
- **Clear on submit**: at the top of `spawn()` (`NewSessionView.swift:264`), where `composer.consume()` already clears the in-memory draft text (which will naturally persist `""` through the same `onChange` hook), also clear the `cmux:launcher-draft` key's workdir/worktree/baseBranch fields. Leave `cmux:launcher-prefs` (agent/model) untouched.

## Android (`apps/android`)

- `AppViewModel.kt`: new `Context.launcherDataStore by preferencesDataStore(name = "launcher_state")` (separate file from `chat_drafts` — different concept/lifecycle, mirrors the naming style of the existing store at line 76).
- New methods mirroring `loadDraft`/`saveDraft` (`AppViewModel.kt:327-334`): `loadLauncherPrefs()/saveLauncherPrefs(agent, model)` and `loadLauncherDraft()/saveLauncherDraft(...)/clearLauncherDraft()`.
- `SessionLauncherScreen.kt` gains callback params in its existing all-callbacks style (it already takes `loadModels`, `transcribeDraft`, etc. at lines 54-68) — e.g. `loadLauncherState: suspend () -> LauncherState`, `onLauncherStateChange: (LauncherState) -> Unit` — wired from both `MainActivity.kt` call sites (lines 290, 314) to the new `vm.*` methods, exactly like `ChatScreen.kt`'s `loadDraft`/`saveDraft` params are wired today.
- Inside the composable: a `LaunchedEffect(Unit)` loads prefs+draft once and seeds `agent`, `model`, and — if the draft had a non-null workdir — `workdir` + `workdirTouched = true` (so `LaunchedEffect(sessions)` at line 106-110 doesn't override it), plus `useWorktree`, `baseBranch`, `message`. A debounced save effect mirrors `ChatScreen.kt:297-308`'s `draftLoaded`-gated, 400ms-`delay` pattern for `agent`/`model` (sticky) and `workdir`(if touched)/`useWorktree`/`baseBranch`/`message` (draft).
- **Clear on submit**: inside the submit `Button`'s `onClick`, alongside the existing `onSubmit(...)` success path (~`SessionLauncherScreen.kt:274-283`), clear the draft (not prefs).

## Error handling

- Persistence is best-effort everywhere: wrap in `try/catch` (web), `runCatching` (Android — already the existing style at `AppViewModel.kt:328,332`), or rely on UserDefaults' inherent best-effort nature (iOS). A failed load/save never blocks session creation — it just silently doesn't restore or persist.
- Corrupt/unparseable stored JSON falls back to defaults, mirroring `editorSettings.ts:27-42`'s defensive `load()` exactly.

## Testing

- **Web**: unit tests for `launcherDraft.ts` (load/save/clear, malformed-JSON fallback) mirroring `editorSettings.test.ts`; typecheck/build via `cd src/web-app && bun run build`.
- **Android**: unit tests for the new `AppViewModel` load/save/clear methods if there's existing coverage for `loadDraft`/`saveDraft` to mirror; otherwise a compile check (`:android:compileDebugKotlin`) plus manual smoke test.
- **iOS**: no changes needed to existing `ComposerModelTests`; build via the remote-Mac simulator recipe. No existing automated coverage of `ChatPane`'s draft persistence to mirror, so manual smoke test is the practical bar here too.
- **All three, manual smoke test**: type into New Session (pick a project, toggle worktree, type text), navigate away and back → confirm restored. Fully close/reopen the app → confirm still restored. Create a session from a restored draft → reopen New Session → confirm the draft is gone but agent/model prefs remain.

## Out of scope

- Attachments.
- Cross-device / server-synced launcher drafts (unlike the existing per-session web chat draft sync). Possible future upgrade, not built now.
- Any change to the existing recency-follow default-project logic itself (`chooseDefaultProject` on web, the Android `LaunchedEffect(sessions)` equivalent, iOS's simpler one-shot default) — this feature only adds a persisted override on top, following each platform's existing rules for what freezes it.
- A shared KMP settings abstraction (see Decision 5) — three native implementations instead.

## Known accepted difference (found post-implementation)

A final cross-platform review (2026-07-01, after all 3 platforms shipped) found that iOS persists `workdir` on *any* non-empty value via `.onChange(of: workdir)`, including the impersonal `projects.first` fallback default — not gated on an explicit-pick flag the way web (`workdirTouched`) and Android (`workdirTouched`) are. Practical effect: once any workdir is set (even via the fallback, not a real pick), iOS treats it as sticky until a session is created, whereas web/Android would keep following a fresh default if the underlying project list changes. Web's `/projects` list is sorted alphabetically (not by recency), so in practice this is narrow and low-severity — it self-heals the moment the user explicitly picks a project or creates a session. Deliberately left as a known, accepted platform difference rather than a required fix, given the cost of another full iOS device-verification round for a narrow edge case; revisit if it turns out to matter more in practice than expected.

## Open questions

None outstanding — scope (exclude attachments), storage shape (two-key split), and rollout (all 3 platforms in one pass) were confirmed with the user before writing this spec.
