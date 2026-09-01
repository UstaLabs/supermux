# Walkthrough Mode — Design (2026-08-31)

- **Date:** 2026-08-31
- **Status:** Approved by the user (all questions answered 2026-08-31).
- **Area:** broker (`src/core/review`, new `src/core/walkthrough`, `src/shim/tools.ts`, `src/channels/web/index.ts`, `src/main.ts`), KMP shared (`apps/shared` — `BrokerApi`, proto frames), **KMP desktop client first** (`apps/desktop` — editor Diff pane). Web PWA, iOS, Android follow later on the same contract.
- **Goal:** Let an agent present its code changes as an ordered, interactive slideshow ("walkthrough"): each step anchors to a diff region with a markdown explanation; the user pages through, comments inline; the agent replies **in-thread** or edits the code, and the walkthrough updates live.

## 1. Decisions made during brainstorming

| Question | Decision |
|---|---|
| Where does it live? | Inside the existing editor **Diff pane** as a Walkthrough mode (data model kept clean so it can become a first-class workspace view later). |
| How does the agent author it? | A new **mux-shim tool `walkthrough(...)`** — structured, validated by the broker, callable again to update. |
| Comment loop | **Instant delivery**: each user comment is delivered to the agent immediately (no Submit batch inside the walkthrough); the agent replies in-thread via a new **`reply_comment`** tool. Agent replies stay **thread-only**, surfaced in chat as a small "new walkthrough replies" chip. The existing plain-diff Submit-review batch path is untouched. |
| What does a step show? | **Diff hunks by default, with on-demand context expansion** (expand ↑/↓, like GitHub), rendered with real syntax highlighting. Text-only steps (no file) are allowed. |
| First client | **KMP desktop** (`apps/desktop`). Code region rendered by **JCEF CodeMirror read-only** (option a), falling back to native Compose diff rows when JCEF is unavailable. |
| Delegation | Backend tasks → grok CLI session; desktop UI tasks → codex CLI session. |

## 2. Data model (broker, SQLite)

New migration `027_walkthroughs.sql`:

```sql
CREATE TABLE walkthroughs (
  id TEXT PRIMARY KEY, session_id TEXT NOT NULL, title TEXT NOT NULL,
  base_spec TEXT NOT NULL,          -- same base grammar as /fs/diff ("", "head", "commit:<sha>", "branch:<name>")
  revision INTEGER NOT NULL,        -- monotonically increasing per session
  created_at TEXT NOT NULL,
  is_current INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE walkthrough_steps (
  id TEXT PRIMARY KEY, walkthrough_id TEXT NOT NULL, ord INTEGER NOT NULL,
  title TEXT NOT NULL, body_md TEXT NOT NULL,
  repo TEXT, path TEXT,             -- NULL ⇒ text-only slide
  side TEXT NOT NULL DEFAULT 'RIGHT',
  anchor_line INTEGER, range_start INTEGER, range_end INTEGER,
  anchor_context TEXT,              -- exact line text at anchor_line, for reanchor()
  anchor_status TEXT NOT NULL DEFAULT 'ok'   -- 'ok' | 'not_in_diff' | 'outdated'
);
CREATE INDEX idx_walkthroughs_session ON walkthroughs(session_id);
CREATE INDEX idx_walkthrough_steps_wt ON walkthrough_steps(walkthrough_id);
```

- One **current** walkthrough per session (`is_current`); a new `walkthrough()` call marks the old one not-current (kept for history) and bumps `revision`.
- Anchor fields deliberately mirror `review_comments` (016) so the existing `src/core/review/anchor.ts` `reanchor()` applies unchanged.
- New `src/core/walkthrough/store.ts` (`WalkthroughStore`), same style as `ReviewStore`.

## 3. Agent-facing shim tools (`src/shim/tools.ts`, all agent kinds)

### `walkthrough`
```jsonc
{ "title": "string",
  "base": "string?",              // default: session-start base (same default as /fs/diff)
  "steps": [ { "title": "string", "body": "markdown string",
               "file": "string?",   // repo-relative path; omit for a text-only slide
               "repo": "string?",   // required only in multi-repo sessions when ambiguous
               "lines": "string?"   // "42" or "12-40", new-side line numbers
             } ] }
```
Behavior: replaces the session's current walkthrough. The broker resolves each `file`/`lines` against the diff at `base` (reusing `computeWorkdirDiff`), fills `anchor_context` from the actual line, and returns per-step `ok | not_in_diff` so the agent can fix bad anchors. A step outside the diff is stored anyway (status `not_in_diff`) — the UI can still open the file. After storing: broadcast `walkthrough_updated` and post a broker-authored "📖 Walkthrough ready — *title* (N steps)" card message into the session chat (broker-sent, like the review-submitted summary message; **not** agent `reply()` text).

### `reply_comment`
```jsonc
{ "comment_id": "string", "body": "string", "resolve": "boolean?" }
```
Inserts a `review_comments` row with `parent_id = comment_id` (root of the thread), `author: 'agent'`; with `resolve: true` also sets the root comment `status: 'resolved', resolvedBy: 'agent'`. Errors clearly on unknown id. Broadcasts `review_comment`. Available in walkthrough and plain-diff contexts alike.

## 4. Instant comment delivery

`POST /sessions/:id/review/comments` gains an optional `deliver: "instant"` flag (the walkthrough UI sends it; the plain diff view does not — its batch Submit flow is untouched). On instant delivery the broker immediately sends the agent a message:

```
💬 Walkthrough comment <id> on <repo?>/<path>:<line> (step <n> "<step title>"):
"<body>"
Reply in-thread with the reply_comment tool (comment_id=<id>), or edit the code — the walkthrough re-anchors automatically. resolve:true marks it resolved.
```

Status stays `open` until resolved (the `submitted` state is a batch-flow concept and is not used here). Delivery uses the same inbound-message path as chat, so a mid-turn agent just gets it queued.

## 5. WS frames (broker → all clients)

- `walkthrough_updated { sessionId, walkthrough: { id, title, baseSpec, revision, steps: [...] } }` — on tool call and whenever a re-anchor pass changes step anchors.
- `review_comment { sessionId, comment: ReviewComment }` — on every comment insert/update from any author (also fixes today's gap where another device only sees new comments on diff reload).

Rule honored: **any mutation that writes SQLite must fan out over the WS.**

REST for initial load: `GET /sessions/:id/walkthrough` → current walkthrough (steps re-anchored on read, same pattern as `/fs/diff` does for comments) or `404`-shaped `{ walkthrough: null }`.

## 6. KMP shared (`apps/shared`)

- `dev.supermux.net`: `Walkthrough`, `WalkthroughStep` DTOs; `BrokerApi.getWalkthrough(sessionId)`; `AddCommentBody` gains `deliver`.
- `dev.supermux.proto`: `ServerFrame.WalkthroughUpdated`, `ServerFrame.ReviewCommentFrame` + reducers.
- Web/iOS/Android clients are **out of scope** now; the contract above is what they will consume later.

## 7. Desktop UI (`apps/desktop`, Compose)

Current facts: `editor/DiffView.kt` is the native diff with inline review comments; `editor/DiffState.kt` holds diff+comments per session; the code editor is CodeMirror in JCEF (`WebCodeEditor.kt`, `EditorSurface`) with a native fallback path.

- **Entry:** Diff pane header shows a "📖 Walkthrough · N steps" toggle when the session has one; the chat "Walkthrough ready" card opens it. `WalkthroughUpdated` frames flip the toggle live.
- **`editor/WalkthroughView.kt`** (sibling of `DiffView.kt`):
  - Top bar: `‹ 3 / 7 ›`, step title, progress dots; keys `←/→` and `j/k`; horizontal-swipe/trackpad paging.
  - Step-list drawer (☰) with per-step open-comment counts; jump to any step.
  - Body: step `body_md` through the existing chat markdown renderer, then the code region.
- **Code region (option a):** JCEF CodeMirror **read-only diff mode** — a new editor-bridge message `showDiffRegion { path, content, ranges, language }` renders the file slice with syntax highlighting, added/changed line backgrounds and +/- gutter, "expand ↑ 20" / "expand ↓ 20" at the edges, and reports line clicks back to Compose for the comment composer. Native fallback: reuse `DiffRows` from `DiffView.kt` when JCEF is unavailable (`fallbackReason` path).
- **Comments:** line click → composer below the line (same UX/components as `DiffView.kt`), posts with `deliver: "instant"`. Threads render natively below their line (root + replies, agent replies with agent avatar); resolved threads collapse to one line. Live via `ReviewCommentFrame`.
- **Updates:** a new revision or re-anchor shows a "Step N updated" badge and re-renders in place; scroll position and an in-progress composer (keyed by comment anchor, not step index) survive.
- **Chat chip:** when agent replies land while the walkthrough is closed, chat shows "💬 N new walkthrough replies" → opens the right step. (Local unread count, cleared on open; not persisted.)
- **Text-only steps:** markdown-only slide, no code region.
- **Multi-repo:** steps carry `repo`; the code region shows `repo/path` in its header.

## 8. Edge cases

- **Vanished anchor** (agent reverted/moved code): step gets `anchor_status: 'outdated'`; UI shows "code changed since authoring" with nearest-match rendering and an "open file" escape hatch. Never a crash, never silently the wrong region.
- **`walkthrough()` mid-composition:** composer state is keyed by comment anchor, so a revision swap does not eat typed text.
- **Instant delivery while the agent is mid-turn:** normal inbound queueing; nothing new.
- **Streamed agents (codex/cursor/opencode/grok):** both tools are orchestration-style shim tools and work for all kinds; the "Walkthrough ready" chat card is broker-authored, so the double-send guard is not involved.
- **Unknown `comment_id` in `reply_comment`:** clear error text back to the agent.

## 9. Testing

- **Broker:** unit tests for `WalkthroughStore`, anchor validation in the `walkthrough` tool (good/bad file+lines), `reply_comment` (threading, resolve, unknown id), instant delivery message content, and WS fan-out of both frames. Migration test via existing harness.
- **Shared KMP:** frame decode/reduce tests (`WalkthroughUpdated`, `ReviewCommentFrame`).
- **Desktop:** state tests in the style of `DesktopDiffReviewTest` (MockEngine) for load/navigate/comment/instant-deliver/live-frame-reduce; step-navigation and anchor-badge logic tests; a JCEF bridge smoke in the style of `JcefEditorSmoke` for `showDiffRegion`.
- Gates: `bun test --isolate`, `tsc --noEmit`, desktop Gradle test task.

## 10. Delegation plan (user's instruction)

- **Backend (broker + shim + shared DTO/frames):** grok CLI session, model grok 4.6, low effort.
- **Desktop UI:** codex CLI session, model sol 5.6, high effort.
- Backend lands first (contract is this spec); UI develops against the spec and integrates once frames/REST exist.
