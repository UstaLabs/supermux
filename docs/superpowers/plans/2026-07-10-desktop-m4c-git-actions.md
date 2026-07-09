# Windows/Linux Desktop Client — Milestone 4c (Git Actions + Session Menus) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the SessionDetail header's TODO(M4c): the git-badge count menu (Fetch/Pull/Push/Publish), the session-links (proxies) menu, and the ⋮ overflow menu. Ports the git-op + links + overflow portions of `apps/android/.../workspace/SessionWorkspaceDetail.kt`.

**Architecture:** The `session_git` reducer branch already lands live badge updates (M4b). This adds: (1) `DesktopAppState` git-op wrappers (gitFetch/gitPull/gitPush/gitPublish + proxies) via `runApi`; (2) a git-badge affordance in the header rendering `gitBadge()` (with `·dirty` counts — desktop currently shows only the icon-only `SessionStatusRail`) that opens a DropdownMenu with Fetch/Pull and Publish-or-Push; (3) a session-links globe menu over `proxies()` opening URLs via Desktop.browse; (4) a ⋮ overflow with the remaining management rows (git ops if not in the badge menu, and a rename/mute/kill grouping if not already reachable). Rename/mute/kill logic already exists on DesktopAppState — ensure they have a UI affordance (the session list has right-click; the header overflow adds parity).

**Tech Stack:** Compose Desktop DropdownMenu/ContextMenu, shared `BrokerApi.gitFetch/gitPull/gitPush/gitPublish` (→ GitOpResult/GitPushResult/GitPullResult) + `proxies()` (→ List<ProxyDto>) + `proxyUrl`/`proxyDisplayUrl` (util/ProxyUrl.kt) + `gitBadge()`/`sessionStatus()` (proto/GitBadge.kt), all shared/existing.

---

## Ground rules

All prior-milestone rules hold (standard gradle invocation with /home/ahmet/.cache logs + TMPDIR; Xvfb :77 + `SKIKO_RENDER_API=SOFTWARE`; paired config; xwd+Pillow; NO xdotool — env hooks; never restart the broker; snake_case tests; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`; touch ONLY apps/desktop/src, NEVER build). Suite baseline at M4c start: desktop 306 / shared jvmTest 292 / android compile green.

- `runApi` for all new broker calls. Git ops return a result DTO (GitOpResult etc.) — surface success/failure (Android shows a snackbar/toast; desktop has no snackbar host yet → a small inline status or a println + a transient header indicator; keep it simple, document — a snackbar host is a legit M4-polish follow-up).
- **DANGER:** gitPush/gitPublish MUTATE the remote. Live-verify (T3) ONLY against a throwaway session in a temp repo (Fetch/Pull are safe-ish; Push/Publish need a throwaway remote or skip-with-documentation). Prefer verifying the MENU renders + Fetch (read-only-ish) live; Push/Publish can be menu-render-only + unit-tested.
- The git badge with counts: Android's `gitBadge(git)` yields a label like `↑2↓1 ·3` — port the desktop rendering (proto/GitBadge.kt is shared, just the Compose rendering differs). The existing icon-only `SessionStatusRail` stays for the sidebar; the HEADER gets the fuller badge+menu.

---

### Task 1: DesktopAppState git-op + proxies wrappers (TDD)

**Files:** Modify `apps/desktop/.../state/DesktopAppState.kt` + test.

- [ ] Add via `runApi`: `gitFetch(id): GitOpResult?`, `gitPull(id): GitPullResult?`, `gitPush(id): GitPushResult?`, `gitPublish(id): GitPushResult?` (check the real BrokerApi return types), `proxies(): List<ProxyDto>`. Match Android AppViewModel:566-569 semantics (callback/result style). MockEngine-test the request method/path shape for each via the apiOverride seam; assert getOrNull-degrading on failure.

### Task 2: Header git-badge menu + session-links menu + overflow (port)

**Files:** Create `apps/desktop/.../workspace/SessionHeaderMenus.kt` (GitBadgeMenu, SessionLinksMenu, OverflowMenu) + modify `SessionDetail.kt` header (fill the TODO(M4c)).

- [ ] **GitBadgeMenu:** render the header git badge from `gitBadge(session.git)` (counts + dirty), clickable → DropdownMenu with `Fetch`, `Pull`, and `Publish` (if `session.git.unpublished == true`) else `Push`, each → app.gitFetch/gitPull/gitPublish/gitPush with a transient result indicator. Only shown when `session.git != null`.
- [ ] **SessionLinksMenu:** a globe IconButton → DropdownMenu over `app.proxies()` filtered to this session (Android filters by session; check), each row `proxyDisplayUrl(p)` → opens `proxyUrl(p)` via Desktop.browse (the Timeline idiom — reuse or a shared util). Only shown when the session has proxies.
- [ ] **OverflowMenu (⋮):** DropdownMenu with the remaining rows — Rename / Mute-Unmute / Kill (bind to the existing app.rename/setMute/kill; a rename dialog + kill confirm like the session-list's) — giving the header parity with the session-list right-click. (Management-nav rows Settings/Usage/Devices/Proxies/Archived: Usage=M4f, Archived=M4e, others are later — add only the ones whose screens exist; stub the rest with TODO or omit.)
- [ ] Wire all three into SessionDetail's header next to FinishButton/PaneToggleCluster; remove the git-badge-menu/session-links/overflow items from the TODO(M4c) comment (leave any genuinely-still-deferred).
- [ ] UI tests via seams: git badge shows counts + opens the menu with the right Publish-vs-Push item (gated on unpublished); links menu lists proxies + opens a url (assert the browse lambda called); overflow rename/mute/kill fire the right callbacks; menus hidden when git/proxies absent.

### Task 3: Live verification + report

- [ ] `SM_GIT_MENU=<session-name>` hook (optional — or reuse the throwaway session from M4b's pattern): open the header on a session with git state.
- [ ] Live checklist (m4cv-*.png): (1) a session with git changes shows the count badge in the header; (2) clicking it opens Fetch/Pull/Publish-or-Push; (3) Fetch runs live against a throwaway repo (read-ish) → result indicator; (4) a session with a proxy (create one via broker REST createProxy against a throwaway session, or find an existing exposed session) shows the links menu → opening a url calls Desktop.browse (verify the intent, not an actual browser under Xvfb — log it); (5) overflow rename/mute on a throwaway session → confirm via the broker. Push/Publish = menu-render-only + documented (don't push to a real remote). Kill the throwaway session. Suites green. Plan tick, `docs(desktop): M4c plan executed`, report incl. what M4d-g inherit.

## Self-review notes
Spec coverage: this completes the session-management header parity. Git badge with counts is the one genuinely-new rendering (desktop had icon-only). Push/Publish are remote-mutating → live-tested carefully or menu-only. Rename/mute/kill logic pre-exists — this adds the header affordance. Snackbar host for op results is a documented M4-polish follow-up (desktop has no toast yet — same gap the launcher/finish flows noted).
