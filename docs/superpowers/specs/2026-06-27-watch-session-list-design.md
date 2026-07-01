# Watch Session List — Phone Parity & Wrist Triage (Design 2026-06-27)

## Goal

Turn the Apple Watch session list from a **directory** (a connection dot + name + agent)
into a **glanceable triage surface** that answers the one question a wrist is for:
*which sessions need me right now?* — by reaching parity with the iPhone's already-shipped
session row and adding a wrist-appropriate flat, attention-sorted layout plus quick actions.

This amends nothing in the prior watch specs' transport story
(`2026-06-22-apple-watch-app-design.md`, `2026-06-24-watch-phone-relay-fallback-design.md`);
it builds on the same REST-poll + phone-relay path. It only makes that path carry *more
signal* and renders it natively.

## Background — what the two apps show today

**iPhone** (`apps/iosApp/Supermux/Sessions/SessionsListView.swift`,
`SessionStatusRail.swift`) — a rich row:
- **Leading status rail** (`SessionStatusRail`): a *working spinner* (top priority); else
  git/cloud state (✓ worktree-done / ⎇ not-done / ☁︎ remote ↑N↓N); else a neutral dot.
- **Last-message preview** as the subtitle (`broker.messages[id].last.text`), falling back to
  the agent name.
- **Muted** bell-slash icon, **swipe actions** (Kill / Rename / Mute), context menu, and
  collapsible **project grouping**.

**Apple Watch** (`apps/iosApp/SupermuxWatch/Watch/SessionsListView.swift`) — a bare row:
- A single colored dot that means only "*broker reachable*" (`session.connected`), the session
  name, and the agent string. No working state, no preview, no unread, no actions.

## Root cause — transport, not design

The phone gets its rich signals **live over WebSocket** (`BrokerSession.reduce`,
`apps/iosApp/Supermux/Broker/BrokerSession.swift:75`): the snapshot frame carries every
session's `agentState` (phase), `logs` (messages), and `activity` (tool calls), kept live by
`agentState` / `messageAppend` frames.

**WebSockets are blocked on real watchOS devices** (Apple TN3135 — see the note atop
`WatchBrokerSession.swift`). So the watch polls `GET /sessions` every 3s, which returns the
**lean** `getSessionsSnapshot()` (`src/main.ts:1032`): `id, name, workdir, mute, connected,
agent, role, model, status, git, finish_job`. It has **no agent phase, no message preview, no
unread**. The data all exists in-process on the broker; the REST payload simply doesn't carry
it.

So the fix is **"enrich the snapshot, then render at phone parity,"** not invent a new system.

## Prior art this completes

This is not a new indicator — it is the **one surface the existing rollout skipped**. The unified
per-session state indicator (working spinner → git/cloud status → neutral, in one leading slot)
was designed and shipped to **web, iOS, and Android** in:
- `2026-06-25-glanceable-session-status-design.md` (done-vs-not, ✓/⎇ rail)
- `2026-06-25-unified-session-state-indicator-design.md` (folds working + git + remote-cloud,
  drops the agent avatar and the connected/suspended dots, adds `touched` → pristine vs done)

Both specs end their test plans with **"iOS … Watch excluded."** The watch never got it. This
design brings the watch up to that same indicator, plus the wrist-specific layout, within the
watch's two hard constraints:
- **No KMP on the watch.** The shared rule `GitBadgeKt.sessionStatus(git:)` is Kotlin/Native and
  unavailable on the watch arch (arm64_32). The watch must **re-derive that small rule in pure
  Swift** (mirrored + unit-pinned to the same cases), not call the shared helper.
- **No WebSocket** (above) — hence the REST-snapshot enrichment.

## Decisions (and why)

- **Parity over invention.** Reuse the iPhone's proven row vocabulary (working-spinner
  priority, status glyphs, preview subtitle) and its exact "working" phase set, so the phone
  and watch never disagree about who's working. New design surface is kept to the wrist-specific
  layout only.
- **One enabling broker change unlocks everything.** Enrich `getSessionsSnapshot()` with a few
  cheap per-session fields (below). Everything in the watch UI rides on this; it is the linchpin
  and the first implementation step.
- **Flat, attention-sorted list — the one justified divergence from the phone.** The phone
  groups by project (good on a big screen); the wrist has scarce vertical space and a different
  question ("who needs me," not "browse my projects"). So the watch shows a **flat** list
  **sorted by attention** (needs-you → working → rest, recency within each).
- **"Needs you" = finished **and** unseen.** `unread` alone is noisy (a working agent appends
  messages constantly → always "unread"). The triage signal is **not-working AND unread**: the
  agent stopped and left output you haven't read = genuinely your move. Working sessions sort
  into their own bucket regardless of unread.
- **Quick actions reuse endpoints that already exist.** Mute (`POST /sessions/{id}/mute`),
  Interrupt (`POST /sessions/{id}/interrupt`), and a canned reply (`POST /sessions/{id}/message`)
  are all live broker routes — no new send path.
- **Keep 3s REST polling.** The richer payload makes each poll far more useful; streaming
  (SSE/long-poll) liveness is a possible later upgrade, out of scope here.

## The enabling broker change (linchpin)

**`git` (with `touched`) is already in this payload** (`src/main.ts:1048`,
`gitStatusService.get(s.id)`) — the watch just never decoded it. So the git/cloud half of the
indicator needs **no broker change**; only the genuinely-missing signals below do.

Add optional fields to `SessionSnapshot` (`src/channels/web/index.ts:80`) and populate them in
`getSessionsSnapshot()` (`src/main.ts:1032`). All sources are already in scope in that closure
(`agentStateStore`, `messageLog`, `registry.sessions.allReads()`):

| Field | Source | Notes |
|---|---|---|
| `phase?: string` | `agentStateStore.get(s.id).phase` | idle/sending/thinking/running/stalled |
| `tool?: string` | same `.tool` | present when phase === running |
| `lastText?: string` | last entry of `messageLog.get(s.id)` | **truncated ~120 chars** to bound payload |
| `lastTs?: string` | same entry `.ts` | drives recency sort |
| `lastFrom?: "in"\|"out"` | same entry `.direction` prefix | who spoke last |
| `unread?: boolean` | `lastTs > allReads()[s.id]` | server-authoritative read pointer |

All fields are **optional** → existing internal callers of `getSessionsSnapshot()` (the
`.find()` lookups, the web's REST `/sessions`) are unaffected; the web ignores them (it uses
WS). Fields are kept tiny (one truncated string + flags), so payload growth is bounded.

**Mark-read:** add a thin `POST /sessions/{id}/read` that calls the existing `markRead(id)`
(`src/main.ts:1121`). The watch calls it when a session detail opens, so `unread` clears and
syncs to the user's other devices via the existing `session_read` broadcast.

## Watch UI

### Row (mirror the phone)

A new `WatchSessionRow` mirroring `SessionRow` + `SessionStatusRail`, sized for the wrist:
- **Leading (the unified indicator):** **replaces today's bare `connected` dot** (connected is
  system-managed and was dropped from the row on every other platform). Priority, mirroring the
  phone's `SessionStatusRail`: working spinner (when `phase` is a working phase — reuse the
  phone's exact list verbatim: `working/thinking/running/tool/busy/sending`) → else the git/cloud
  glyph (✓ done / ⎇ not-done / pristine→neutral / ☁︎ remote — **no ↑↓ counts** on the watch, to
  save width) → else a neutral dot. The git glyph is driven by a **pure-Swift `sessionStatus`
  mirror** of `GitBadge.kt` (the watch can't call KMP), fed by the `git` (incl. `touched`) the
  snapshot already carries. Reuse the phone's SF Symbols (`checkmark` / `arrow.triangle.branch` /
  `checkmark.icloud` / `icloud`).
- **Title:** session name + muted bell-slash icon when muted.
- **Subtitle:** `lastText` (one line, already truncated server-side) — fallback to the agent
  name when absent.
- **Unread emphasis:** **bold session name + a small dot** — the exact deconfliction the
  unified-indicator spec chose, because the left edge now belongs to the status indicator, not
  unread (`2026-06-25-unified-session-state-indicator-design.md`). Subtle; the bucket + sort carry
  most of the signal.

### Layout & sort (the wrist divergence)

Flat `List` (no grouping). Sort into three buckets, recency (`lastTs` desc) within each:
1. **Needs you** — `!working && unread` (finished with unseen output).
2. **Working** — `phase ∈ working set`.
3. **Rest** — idle and read.

The ordering is a pure function `orderedSessions(sessions)` (each session already carries
`phase`, `unread`, `lastTs`) so it is unit-testable without a device.

### Glance header (optional polish)

A tiny summary line atop the list — e.g. `2 need you · 1 working` — derived from the bucket
counts. Hidden when both are zero.

### Swipe actions (parity, wrist-trimmed)

watchOS `List` supports `.swipeActions`. Trailing-edge actions:
- **Continue** — `POST /sessions/{id}/message {text:"continue"}` (the highest-value nudge: keep
  an agent moving without opening + dictating). v1 ships one canned reply.
- **Mute/Unmute** — `POST /sessions/{id}/mute`.
- **Interrupt** — `POST /sessions/{id}/interrupt` (stop a runaway agent from the wrist).

Kill/Rename stay phone-only (destructive/fiddly on a tiny screen). If swipe ergonomics feel
cramped on smaller watches, fall back to a long-press context menu or move these into the
detail screen — decided at verify time.

## Non-goals (v1)

Watch-face **complication** / Smart Stack widget; **permission-approval** from the wrist (needs
a *new* broker signal — pending-approval isn't tracked anywhere today); project grouping on the
watch; git ↑↓ counts; streaming/SSE liveness; any change to the detail or voice screens.

## Files

**Broker (TypeScript)**
- `src/channels/web/index.ts` — extend `SessionSnapshot` (interface, ~line 80); add the
  `POST /sessions/{id}/read` route alongside the existing `/mute` handler (~line 1595).
- `src/main.ts` — populate the new fields in `getSessionsSnapshot()` (~line 1032); wire the
  read route to `markRead`.

**Watch (Swift)**
- `apps/iosApp/SupermuxWatch/Watch/WatchModels.swift` — decode the new fields on `SessionInfo`
  (`phase, tool, lastText, lastTs, lastFrom, unread`) **and the already-sent `git`** (a plain-Swift
  `GitLite` mirror incl. `mode, ahead, behind, dirty, touched, unpublished`).
- `apps/iosApp/SupermuxWatch/Watch/WatchSessionStatus.swift` *(new)* — **pure-Swift mirror of
  `GitBadge.kt`**: `sessionStatus(git) -> (kind, level)` (worktree DONE/NOT_DONE/PRISTINE, remote
  DONE/NOT_DONE) + the `isWorking(phase)` set. Unit-pinned to the same cases as the shared tests.
- `apps/iosApp/SupermuxWatch/Watch/SessionsListView.swift` — new row, flat attention-sort,
  glance header, swipe actions.
- `apps/iosApp/SupermuxWatch/Watch/WatchSessionRow.swift` *(new)* — the parity row + status rail
  (consumes `WatchSessionStatus`).
- `apps/iosApp/SupermuxWatch/Watch/WatchBrokerSession.swift` — `orderedSessions` bucket sort;
  `mute(_:)`, `interrupt(_:)`, `quickReply(_:)`, `markRead(_:)` over the existing `transport`;
  call `markRead` from `openSession`.
- `apps/iosApp/project.yml` — target membership for the new file (XcodeGen).

**Tests**
- Broker unit test: `getSessionsSnapshot()` includes `phase` + `lastText`/`lastTs`/`unread` for a
  session with state + messages + a read pointer.
- Watch unit tests (in `SupermuxTests`, pure-Swift): (a) `orderedSessions` bucketing + recency,
  including the working-and-unread edge case; (b) `WatchSessionStatus.sessionStatus` pinned to the
  same cases as the shared `:shared:jvmTest` (worktree DONE/NOT_DONE/PRISTINE, remote
  DONE/NOT_DONE, null).

## Testing

- **Unit:** the two above (no devices).
- **Simulator:** screenshot the list with a mix of states (working / needs-you / idle / muted),
  verify the spinner, preview, glance header, and sort order.
- **Device (source of truth):** against the live broker — a working agent shows the spinner and
  sorts under "working"; when it stops and leaves a reply it jumps to "needs you"; opening it
  clears unread; swipe → Continue keeps it moving; Mute/Interrupt take effect.

## Risks & fallbacks

- **Payload growth on `/sessions`.** Mitigated by truncating `lastText` server-side (~120 chars)
  and shipping only flags besides. If still heavy, split to a `GET /watch/sessions` projection.
- **watchOS swipe ergonomics** on 40mm watches — fallback to context menu / detail actions.
- **Duplicated status rule (Swift vs KMP).** `WatchSessionStatus.sessionStatus` re-states
  `GitBadge.kt`; they can drift. *Mitigation:* a Swift unit test pinned to the same cases as the
  shared `:shared:jvmTest`; a comment in both pointing at each other. *Scope lever:* if we want a
  leaner v1, the git glyph is the **first thing to cut** — the core triage (working / needs-you /
  recency) needs only `phase` + `unread`, no git rule at all.
- **Unread read-pointer wiring.** If `markRead` proves awkward to expose over REST, ship the row
  + working-status + preview first and fast-follow unread (the headline triage still works via
  the working bucket + recency).
- **Build/verify needs the remote Mac** — watchOS can't compile on this Linux host; build +
  simulator/device verify run on the established remote-Mac SSH recipe (located in the plan step).

## Open items for the plan step

- Confirm the exact `messageLog` last-entry accessor + truncation point, and the `allReads()`
  return shape (`Record<sessionId, ts>`).
- Confirm watchOS `.swipeActions` feel on the target watch; pick swipe vs context-menu.
- Locate the remote-Mac SSH host + watchOS build/sign recipe (memory: `infra` / `claudemux`).
