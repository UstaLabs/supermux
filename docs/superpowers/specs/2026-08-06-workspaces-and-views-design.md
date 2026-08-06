# Workspaces and views — Design (2026-08-06)

- **Date:** 2026-08-06
- **Status:** Approved. The user answered all of the questions on 2026-08-06 (sections 18 and 19). No question is open.
- **Area:** broker (`src/core/session-manager`, `src/core/storage/migrations`, `src/channels/web/index.ts`), shared KMP (`apps/shared`), and all four clients (`src/web-app`, `apps/iosApp`, `apps/android`, `apps/desktop`).
- **Goal:** Replace the flat session model with **workspaces**. A workspace holds **views**. A view is a terminal, a display, an agent chat, or an editor. The user arranges the views in tabs and in splits.

> **Language.** This document uses ASD-STE100 Simplified Technical English. Sentences are short.
> The document uses each word with only one meaning. Code identifiers are technical names.
> The document keeps them in their source form, for example `SessionRecord`.

---

## 1. Words in this document

The word "workspace" is already in the code with a different meaning. Read this list first.

| Word | Meaning in this document |
|---|---|
| **Workspace** | The new server-side container. It has a name, a work directory, a source directory, a layout, and a set of views. |
| **View** | One item of content in a workspace. A view is a chat, a terminal, an editor, or a display. |
| **Group** | An area that holds one or more views. The group shows its views as tabs. One view in a group is active. |
| **Split** | A layout node that divides an area into two or more parts. A split is horizontal or vertical. |
| **Layout** | The tree of splits and groups in one workspace. |
| **Shell** | The client window frame. The shell holds the session list, the title bar, and the layout area. |
| **Session** | An agent process, as today. A session keeps its `id`, its agent kind, and its transcript. |
| **Project** | A repository path. The clients calculate the projects from the session paths. This does not change. |
| **Settle** | The same operation as **archive**. The two words name one thing. `SessionStore.archive()` writes `status = 'archived'` and `user_status = 'settled'` in one SQL statement (`src/core/session-manager/session-store.ts:139`). Migration `026_user_status.sql` states the rule: "settled — user marked it done; ALWAYS implies status='archived'". `setUserStatus()` has no caller outside the store. This document uses **archive**. |
| **Migration** | A numbered SQL file in `src/core/storage/migrations`. |

⚠ **Name collision.** The code uses "workspace" today for the client layout shell:

- `apps/desktop/.../workspace/WorkspaceRoot.kt`, `WorkspaceLayout.kt`, `WorkspaceStateStore.kt`
- `apps/android/.../workspace/WorkspaceLayout.kt`, `SessionWorkspaceDetail.kt`
- `apps/iosApp/Supermux/Shell/IPadWorkspace.swift`, `WorkspaceLayoutModel.swift`
- `src/web-app/src/views/WorkspaceWelcomeView.vue`, `useWorkspaceShortcuts.ts`

Section 12 gives the rename plan. Do the rename first. If you do not do the rename, two different
things keep the same name in one codebase.

---

## 2. What the system does today

### 2.1 The session is the only container

`SessionRecord` in `src/core/session-manager/types.ts` holds all of the context:

```ts
{ id, name, status, agent, workdir, model, reasoningLevel, …,
  repo_root, base_branch, session_branch, base_commit, base_commits, … }
```

The `sessions` table (migration `003_sessions.sql`, changed by `013`, `015`, `020`, `025`, `026`)
stores these fields. The newest migration is `026_user_status.sql`.

### 2.2 Projects are calculated, not stored

There is no project table. The broker calculates the project list in `GET /projects`
(`src/channels/web/index.ts:2259`). It reads `repo_root ?? workdir` from each session. It then
removes the worktree paths.

The clients group the sessions in the same way. `groupSessions()` in
`apps/shared/.../session/SessionGrouping.kt` groups by `repo_root ?: workdir`. The web app does the
same in `usePathGroups.ts`.

### 2.3 Each content type binds to one session

| Content | How it binds today |
|---|---|
| Agent chat | The session **is** the chat. |
| Terminal | `/ws/term?session=<name>&kind=<agent\|scratch>&terminal=<id>`. The broker owns the identity `(session, terminalId)`. An agent terminal has the fixed id `agent`. |
| Editor | `fs_read`, `fs_list`, and `fs_diff` take a session. The broker limits the paths to the session work directory. `ClientFrame.EditorOpen(session)` and `ServerFrame.FsChanged(session, paths)` also take a session. |
| Display | `POST /displays` takes a `sessionName` tag. The stream id is host-global. `/ws/display?id=<id>` uses only the stream id. |

The display is therefore almost free of the session already. The terminal, the editor, and the LSP
are not.

### 2.4 The layout is fixed and local

Each client has one fixed layout: `Sessions │ Chat │ (Editor / Terminal) │ Display`. The client
stores four booleans for each session:

```kotlin
data class PaneVisibility(chat: Boolean, editor: Boolean, terminal: Boolean, display: Boolean)
```

The client stores the split fractions and the pane booleans on the device only
(`WorkspaceStateStore.kt`, `WorkspaceLayoutModel.swift`). The layout does not move between devices.
The user cannot make two terminals side by side. The user cannot put two chats in one window.

---

## 3. Problems with the flat model

1. **The container is too small.** One session gives one chat, one editor scope, and one terminal
   scope. The user cannot make a second agent in the same work tree without a new, unrelated row.
2. **The layout is not flexible.** The four panes are fixed. The user cannot add tabs. The user
   cannot put a view at the bottom.
3. **The layout does not follow the user.** The pane state is on the device. A phone and a desktop
   do not agree.
4. **The content types are not equal.** A display is almost independent. A terminal is not. The code
   treats them in different ways for no product reason.
5. **A view cannot move.** There is no object to move. A pane is a boolean on a session.

---

## 4. Decisions

The user approved these decisions on 2026-08-06. Section 18 records the five answers.

1. **Add the workspace as a new server-side entity. Do not remove the session.** A session keeps its
   id, its transcript, and its lifecycle. A session gets a new parent: `workspace_id`.
2. **Make the view a stored object with its own id.** A view is not a boolean. A view has a kind, a
   title, and a small state object. The client can move a view to a different workspace.
3. **Store the layout as a tree on the server.** The tree holds splits and groups. The server sends
   the tree to all devices. Section 8.3 gives the small-screen rule.
4. **Keep the new-session screen.** The user asked for this. `POST /sessions` gets one new optional
   field, `workspaceId`. If the field is absent, the broker makes a workspace first. The screen does
   not change.
5. **Make the work directory a property of the workspace.** A new chat view in a workspace uses the
   workspace work directory by default. The user can change it. The rule is a default, not a limit.
6. **Move the terminal scope and the editor scope from the session to the workspace.** Keep the
   session forms of the routes for old clients. The agent terminal stays on the session, because it
   shows the agent process pane.
7. **Keep the project as a calculated value.** The clients group the workspaces by
   `repo_root ?? workdir`, as they group the sessions today. Do not add a project table.
8. **Make the change additive on the wire.** Old clients (App Store iOS 1.1, the sideload APKs)
   must continue to work. Every session route and every session frame stays.
9. **Put the layout tree logic in `apps/shared` and write a parity test in TypeScript.** This
   follows the `PredictiveEcho` precedent and the `TerminalKeys` precedent. One engine, two
   languages, locked tests.
10. **The session list becomes a workspace list.** (User answer 4.) The sidebar shows one row for
    each workspace. The sessions of the workspace show below the row. Section 13.6 gives the rules.
11. **The workspace name follows its primary session.** (User answer 5.) At the start, the workspace
    takes the name of its first chat session. When the agent renames that session, the broker also
    renames the workspace. Section 9.5 gives the rules.

### Rejected alternatives

- **A client-only workspace.** The layout would stay on the device. Decision 3 needs the server.
- **A workspace that replaces the session row.** This breaks every client at the same time.
- **A project table.** The user did not ask for it. It adds a second source of truth for a path.

---

## 5. Data model

### 5.1 Workspace

```ts
export type WorkspaceRecord = {
  id: string                 // uuid
  name: string
  status: "active" | "archived"
  workdir: string            // the directory the views use
  repo_root?: string         // the source checkout, if the workdir is a worktree
  base_branch?: string
  branch?: string            // the workspace branch (today: session_branch)
  layout: LayoutNode         // the split/group tree
  active_view_id?: string
  primary_session_id?: string // the session that gives the name (section 9.5)
  name_locked: boolean        // true after the user renames the workspace by hand
  sort_order: number
  created_at: string
  archived_at?: string
}
```

The four path fields have the same meaning as the session fields with the same names. The user asked
for this. A workspace that uses a worktree has `workdir` under `~/.mux/worktrees` and `repo_root` at
the real checkout.

`primary_session_id` and `name_locked` control the name. Section 9.5 gives the rules.

### 5.2 View

```ts
export type ViewKind = "chat" | "terminal" | "editor" | "display"

export type ViewRecord = {
  id: string                 // uuid
  workspace_id: string
  kind: ViewKind
  title?: string             // null = the client calculates the title
  state: ViewState           // JSON, see the table below
  created_at: string
}
```

| Kind | `state` | Notes |
|---|---|---|
| `chat` | `{ sessionId }` | The session must exist. Two chat views cannot point to one session. |
| `terminal` | `{ scope: "workspace", terminalId }` **or** `{ scope: "session", sessionId, terminalId: "agent" }` | The workspace scope starts a shell in the workspace work directory. The session scope shows the agent pane. |
| `editor` | `{ path?, mode: "tree" \| "file" \| "diff", line?, diffBase? }` | The paths are relative to the workspace work directory. |
| `display` | `{ displayId }` | The display stream stays host-global. The view only points to it. |

The `state` object is small. Do not put file content or scroll positions in it.

### 5.3 Layout tree

```ts
export type LayoutNode =
  | { type: "split", direction: "row" | "column", sizes: number[], children: LayoutNode[] }
  | { type: "group", id: string, viewIds: string[], activeViewId?: string }
```

Rules:

- The `sizes` array holds fractions. The fractions add up to 1. The `sizes` length equals the
  `children` length.
- A `group` shows its views as tabs. `activeViewId` must be in `viewIds`.
- Each view id occurs in exactly one group.
- A split with `direction: "row"` puts its children side by side. A split with
  `direction: "column"` puts its children one above the other.
- An empty group is not valid. Remove the group when the last view leaves it. Then remove a split
  that has only one child.

Example:

```json
{ "type": "split", "direction": "row", "sizes": [0.5, 0.5], "children": [
  { "type": "group", "id": "g1", "viewIds": ["v-chat-1"], "activeViewId": "v-chat-1" },
  { "type": "split", "direction": "column", "sizes": [0.6, 0.4], "children": [
    { "type": "group", "id": "g2", "viewIds": ["v-editor-1", "v-editor-2"], "activeViewId": "v-editor-1" },
    { "type": "group", "id": "g3", "viewIds": ["v-term-1"], "activeViewId": "v-term-1" }
  ]}
]}
```

### 5.4 The session change

`SessionRecord` gets one field:

```ts
workspace_id: string
```

The field is not optional after the migration. The migration gives every session a workspace.

---

## 6. Database changes

Add **`027_workspaces.sql`**. The file does three things.

**Step 1 — Make the tables.**

```sql
CREATE TABLE workspaces (
  id            TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','archived')),
  workdir       TEXT NOT NULL,
  repo_root     TEXT,
  base_branch   TEXT,
  branch        TEXT,
  layout        TEXT NOT NULL,          -- JSON LayoutNode
  active_view_id TEXT,
  primary_session_id TEXT REFERENCES sessions(id),
  name_locked   INTEGER NOT NULL DEFAULT 0,
  sort_order    INTEGER NOT NULL DEFAULT 0,
  created_at    TEXT NOT NULL,
  archived_at   TEXT
);

CREATE TABLE views (
  id           TEXT PRIMARY KEY,
  workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  kind         TEXT NOT NULL CHECK(kind IN ('chat','terminal','editor','display')),
  title        TEXT,
  state        TEXT NOT NULL,           -- JSON ViewState
  created_at   TEXT NOT NULL
);
CREATE INDEX views_workspace ON views(workspace_id);

ALTER TABLE sessions ADD COLUMN workspace_id TEXT REFERENCES workspaces(id);
CREATE INDEX sessions_workspace ON sessions(workspace_id);
```

**Step 2 — Give each existing session a workspace.** Make one workspace for each session. Do not
group the sessions by path. A one-to-one move is lossless. A grouped move can put two agents in one
container without the user's agreement.

For each session row:

- `workspaces.id` = a new uuid
- `name` = the session name
- `workdir`, `repo_root`, `base_branch` = the same session fields
- `branch` = `sessions.session_branch`
- `status` = `'archived'` if the session is archived, else `'active'`
- `sort_order` = `sessions.sort_order`
- `primary_session_id` = the session id
- `name_locked` = `0`, so a later agent rename reaches the workspace

`name_locked` is `0` for every moved row. The database does not record which of the old names came
from the user and which came from the agent. A `0` keeps the names together from this day.

**Step 3 — Make the first view and the first layout.** For each new workspace, make one `chat` view
with `state = {"sessionId": "<session id>"}`. Set the layout to one group that holds that view:

```json
{ "type": "group", "id": "<uuid>", "viewIds": ["<view id>"], "activeViewId": "<view id>" }
```

The migration is a pure move. No session changes its behaviour after it runs.

---

## 7. HTTP API

All of the routes are in `src/channels/web/index.ts`. All of them need authentication, as the
current routes do.

### 7.1 New routes

| Method | Path | Body / result |
|---|---|---|
| `GET` | `/workspaces` | `{ workspaces: WorkspaceDto[] }`. Each item holds its views. |
| `POST` | `/workspaces` | `{ name?, workdir, worktree?, baseBranch? }` → the new workspace. |
| `PATCH` | `/workspaces/:id` | `{ name?, layout?, activeViewId? }` |
| `DELETE` | `/workspaces/:id` | Archives the workspace. It also archives the sessions of the chat views. |
| `PATCH` | `/workspaces/reorder` | `{ orderedIds: string[] }`, as `/sessions/reorder` does. |
| `POST` | `/workspaces/:id/views` | `{ kind, state, groupId? }` → the new view. |
| `PATCH` | `/workspaces/:id/views/:viewId` | `{ title?, state? }` |
| `DELETE` | `/workspaces/:id/views/:viewId` | Removes the view **and ends the work behind it**. Section 9.3 gives the effect for each kind. |
| `POST` | `/views/:viewId/move` | `{ toWorkspaceId, toGroupId? }` |

### 7.2 Changed routes

- **`POST /sessions`** gets an optional `workspaceId`. If the client sends it, the broker puts the
  new session in that workspace and adds a `chat` view. If the client does not send it, the broker
  makes a workspace from the `workdir`, as the migration does. The old request body still works.
- **`POST /displays`** gets an optional `workspaceId`. The broker keeps the `sessionName` field for
  old clients.
- **`GET /api/term/list`** and **`POST /api/term/close`** accept `workspace=<id>` in addition to
  `session=<name>`. Exactly one of the two is needed.
- **`GET /projects`** also reads the workspace paths, not only the session paths. Without this
  change, an empty workspace does not show its project.

### 7.3 Changed WebSocket upgrade paths

- **`/ws/term`** accepts `workspace=<id>` in addition to `session=<name>`. With `workspace`, the
  broker starts the terminal in the workspace work directory. The `kind=agent` form still needs
  `session`, because an agent pane belongs to an agent.
- **`/ws/display`** does not change. It already uses only the stream id.

### 7.4 Changed frame payloads for the editor

`fs_read`, `fs_list`, `fs_diff`, and the LSP frames take a `session` today. Add an optional
`workspace` field to each of them. The broker limits the paths to the workspace work directory when
the client sends `workspace`. **Keep the server-side path limit.** The digest records that the
broker returns HTTP 400 for a path outside the work directory. That rule must apply to the workspace
work directory in the same way.

---

## 8. WebSocket frames

### 8.1 New server frames

Add these to `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt` and to the broker.

| `@SerialName` | Payload |
|---|---|
| `workspace_added` | `{ workspace: WorkspaceDto }` |
| `workspace_removed` | `{ id: String }` |
| `workspace_changed` | `{ workspace: WorkspaceDto }` — the name, the layout, or the paths changed. |
| `workspaces_reordered` | `{ orderedIds: List<String> }` |
| `view_added` | `{ workspaceId, view: ViewDto }` |
| `view_removed` | `{ workspaceId, viewId }` |
| `view_changed` | `{ workspaceId, view: ViewDto }` |
| `view_moved` | `{ viewId, fromWorkspaceId, toWorkspaceId }` |

`ServerFrame.Snapshot` gets a `workspaces` list. The list is empty for a broker without workspaces.

⚠ **A write to SQLite alone is a bug.** The digest records this rule from the `sessions_reordered`
defect: a mutation that writes only the database leaves every other device stale. Every route in
section 7.1 must send its frame after the write.

⚠ **An unknown frame is dropped in silence.** The digest records this for `session_state` and for
`session_renamed`. An old client that does not know `workspace_added` ignores it and keeps its flat
list. This is the wanted behaviour. It also means a new frame alone never repairs an old client.

### 8.2 Client frames

No new client frame is needed. The layout changes go over `PATCH /workspaces/:id` and come back over
`workspace_changed`. This matches the current pattern for `sessions/reorder`.

`ClientFrame.Viewing(session, visible)` keeps its shape. Section 11 explains the change of meaning.

### 8.3 The small-screen rule

The server holds one layout for each workspace. A phone cannot show a four-part split.

- A large client (desktop, macOS, tablet, web on a wide window) reads the layout and writes it back.
- A small client (phone, narrow web window) reads the layout, shows **one group at a time**, and
  **does not write the layout back**. The user moves between the views with tabs.

This keeps one source of truth. It also stops a phone from destroying a desktop arrangement.

---

## 9. Behaviour

### 9.1 Make a new session

1. The user opens the new-session screen. The screen does not change.
2. The client sends `POST /sessions` without `workspaceId`.
3. The broker makes the workspace. It sets `workdir`, `repo_root`, `base_branch`, and `branch`.
4. The broker starts the session and sets `sessions.workspace_id`.
5. The broker makes a `chat` view and a one-group layout.
6. The broker sends `workspace_added`, then `session_added`.

### 9.2 Add a view to an open workspace

The user opens a new tab in the workspace. The client shows the view kinds.

- **Chat.** The client sends `POST /sessions` **with** `workspaceId`. The broker uses the workspace
  `workdir` as the default. The new session shares the work tree with the other sessions of the
  workspace. The user can change the directory in the screen. The rule is a default, not a limit.
- **Terminal.** The client sends `POST /workspaces/:id/views` with
  `{ kind: "terminal", state: { scope: "workspace", terminalId } }`. The terminal starts in the
  workspace work directory. No agent is needed.
- **Editor.** The client sends `POST /workspaces/:id/views` with `{ kind: "editor", state: {…} }`.
- **Display.** The client starts or selects a display, then adds a view that points to the stream.

### 9.3 Close a view

The user answered on 2026-08-06: **a close of a view also ends the work behind it.** A view is not a
window on to a thing that lives longer than the view. The view **is** the thing.

| Kind | Effect of `DELETE /workspaces/:id/views/:viewId` | Ask first |
|---|---|---|
| `chat` | The broker **archives the session**. This is the same operation as `DELETE /sessions/:id` today. | **Yes** |
| `terminal` | The broker **kills the tmux terminal**. This is the same operation as `POST /api/term/close`. | **Yes** |
| `editor` | The view goes. Nothing else stops. | No |
| `display` | The broker **stops the display stream**. This is the same operation as `DELETE /displays/:id`. | **Yes** |

**The confirmation.** The user answered yes: the client asks before a close that ends work. Rules:

1. The client asks for a `chat` view, a `terminal` view, and a `display` view. The client does not
   ask for an `editor` view.
2. The question names the thing that stops. For example: "Close this chat? This archives the session
   *Fix Session Renaming*."
3. The question is short. It is one question with two buttons. It is **not** the Finish flow.
4. The confirmation is client-side. The route always acts. The broker does not hold a second state.

**No Finish flow on a close.** The user was clear on 2026-08-06: a close of a chat view settles only
that view. **The client must not open the Finish flow** (Merge locally / Open PR / Keep / Discard).
A close is a small, fast action. The Finish flow is a large action with a test gate. Do not join
them.

The close therefore does this and nothing more:

1. It asks the short question of the rule above.
2. It archives the session. Archive and settle are one operation, not two — see section 1. The row
   moves to the **Settled** section as a result.
3. It removes the chat view and sends the frames.

**The work tree stays on disk.** A close does not merge the branch. It does not remove the branch.
It does not remove the work tree directory. The user can run the Finish flow later, from the
archived row or from the workspace menu. Keep both of those entry points.

⚠ This means `~/.mux/worktrees` grows. The Finish flow is now always a separate, later action.
`isWorktreeReclaimable()` in `src/core/worktree/gc.ts` already tests a work tree for a safe reclaim.
Check that the garbage collector runs for an archived session that never went through Finish.

⚠ The Finish flow itself does not change. It keeps its **Run tests / Skip tests** choice,
`.mux/verify.sh` as the gate, and the hidden Skip on the PR path for a repo with `prRequiresGreen`.

**The invariant this creates.** Every live session has exactly one chat view, in exactly one
workspace. There is no session without a view:

- `POST /sessions` always makes a chat view (section 9.1 and 9.2).
- A close of a chat view archives the session.
- A move of a chat view keeps the view (section 9.4).

The broker must still self-heal. If it finds a live session with no chat view at startup, it makes a
workspace and a chat view for it. Log this event. It means a defect, not a normal state.

### 9.4 Move a view

`POST /views/:viewId/move` changes `views.workspace_id`. It also changes both layouts. The user can
move a chat to a different workspace. The session work directory does **not** change. A chat view in
a workspace with a different work directory is valid. The client must show this difference clearly.

### 9.5 Rename a workspace

The user answered: the workspace takes the name of the session, and an agent rename also renames the
workspace.

The broker uses `primary_session_id` and `name_locked`:

1. At the start, the broker sets `primary_session_id` to the session of the first chat view. It sets
   `name_locked` to `false`. It copies the session name to the workspace name.
2. When the primary session is renamed, the broker renames the workspace to the same name. This
   applies to an agent rename (the `rename_session` shim tool) and to a user rename of that session.
3. The broker then sends `session_renamed` **and** `workspace_changed`. Both frames are needed. An
   old client reads only the first one.
4. A rename of a session that is **not** the primary session does not touch the workspace name.
5. When the user renames the **workspace** by hand, the broker sets `name_locked` to `true`. After
   that, a session rename never changes the workspace name.
6. When the primary session is archived and the workspace holds other chat views, the broker moves
   `primary_session_id` to the oldest live chat session. The name does not change at that moment.
   The next rename of the new primary session moves the name.

⚠ The digest records that the shim rename tool must ask for a natural, human-readable title, not a
slug. The workspace name inherits this rule, because it is the same string.

⚠ An unknown frame is dropped in silence. A client that does not know `workspace_changed` keeps the
old workspace name until its next snapshot. Send both frames; do not replace `session_renamed`.

### 9.6 Archive a workspace

`DELETE /workspaces/:id` sets `status = 'archived'`. It archives the sessions of the chat views. It
does not remove the work tree. The current Finish flow (Merge / Open PR / Keep / Discard) keeps its
place and its meaning. The Finish flow acts on the branch of the workspace.

---

## 10. What shares a work tree

The user asked for this default: a new chat in a workspace starts in the same work directory.

⚠ **Two agents in one work tree write the same files at the same time.** This is a real risk, not a
theory. Git holds one branch for one work tree. Two agents therefore share one branch, one index,
and one set of files. The `.git/index.lock` file can also cause a failure in one agent when the
other agent runs a git command.

The design accepts the risk, because the user asked for the default. The design reduces the risk in
three ways:

1. The new-session screen shows the workspace work directory and the branch. The user sees the share
   before the start.
2. The client shows a small mark on a workspace that has two or more live agent sessions.
3. The user can select a new work tree for the new chat. The default is not a limit.

Section 16 asks the user to confirm the branch rule.

---

## 11. Notifications and read state

`ClientFrame.Viewing(session, visible)` tells the broker which chat the user reads. The broker uses
it to stop a push for the open chat.

With workspaces, more than one chat can be visible at the same time. The rule becomes:

- The client sends one `Viewing` frame for **each visible chat view**.
- A chat view in a background tab is **not** visible.
- The 60-second heartbeat and the re-assert on reconnect do not change.

The frame shape does not change. The broker must accept more than one live `Viewing` for one device.
Check `src/core/session-manager` for a one-session assumption before you build this.

---

## 12. The rename (do this first)

Phase 0 is a pure rename. It changes no behaviour. It stops one name from meaning two things.

| Today | New name |
|---|---|
| `apps/desktop/.../workspace/WorkspaceRoot.kt` | `.../shell/AppShell.kt` |
| `apps/desktop/.../workspace/WorkspaceLayout.kt` | `.../shell/ShellLayout.kt` |
| `apps/desktop/.../workspace/WorkspaceStateStore.kt` | `.../shell/ShellStateStore.kt` |
| `apps/desktop/.../workspace/WorkspaceShortcuts.kt` | `.../shell/ShellShortcuts.kt` |
| `apps/android/.../workspace/WorkspaceLayout.kt` | `.../shell/ShellLayout.kt` |
| `apps/android/.../workspace/SessionWorkspaceDetail.kt` | `.../shell/SessionShellDetail.kt` |
| `apps/iosApp/Supermux/Shell/IPadWorkspace.swift` | `PadShell.swift` |
| `apps/iosApp/Supermux/Shell/WorkspaceLayoutModel.swift` | `ShellLayoutModel.swift` |
| `src/web-app/src/composables/useWorkspaceShortcuts.ts` | `useShellShortcuts.ts` |
| `src/web-app/src/views/WorkspaceWelcomeView.vue` | `ShellWelcomeView.vue` |

The test files move with them. The desktop copy of `WorkspaceLayout.kt` says in its header comment
that it is a verbatim copy of the Android file. Keep that comment true.

After Phase 0, the word "workspace" in the code means only the new entity.

---

## 13. Client work

The client matrix in the memory digest gives the owner of each platform. A cross-client feature must
land in the broker, in `src/web-app`, in `apps/iosApp` (this covers iOS **and** macOS), in
`apps/android`, and in `apps/desktop`.

### 13.1 Shared KMP (`apps/shared`)

- `dev/supermux/workspace/LayoutTree.kt` — the tree model and the pure operations:
  `addView`, `removeView`, `moveView`, `splitGroup`, `setSizes`, `normalize`.
- `dev/supermux/workspace/Workspace.kt` — the DTOs.
- The new frames in `proto/Frames.kt`.
- The new calls in `net/BrokerApi.kt`.
- `commonTest` for every tree operation, with the invariants of section 5.3.

### 13.2 Web (`src/web-app`)

- `src/lib/layout-tree.ts` — a port of `LayoutTree.kt`, with a parity test. This follows the
  `PredictiveEcho` precedent and the `terminal-keys.ts` precedent.
- A new route `/w/:id`. Keep `/s/:id`. The old route finds the workspace of the session and opens
  it with that chat view active.
- A layout component with drag-to-resize splits and drag-to-move tabs.

### 13.3 Desktop (`apps/desktop`)

Build the layout tree here first. A desktop window is always wide. The window has a pointer.

⚠ **The terminal and the editor are heavy AWT children.** The digest records this: JediTerm and KCEF
are `SwingPanel` children. Compose paints nothing above them. Build them only when the pane is real
and its size is not zero. A tab that is not active must **not** hold a live KCEF instance. Swap the
pane; do not put a Compose overlay on it.

### 13.4 iOS and macOS (`apps/iosApp`)

- macOS is a wide window. It gets the full tree.
- The iPad `PadShell` gets the full tree.
- The iPhone gets the small-screen rule of section 8.3.

### 13.5 Android (`apps/android`)

- The tablet and the folded-open device (width ≥ 600 dp) get the full tree.
- The phone gets the small-screen rule.

### 13.6 The sidebar shows workspaces

The user answered: the list shows workspaces from now on. This applies to all four clients.

**The row.** One row is one workspace. The row shows:

- the workspace name (section 9.5)
- the path label from `formatWorkdir(repo_root ?? workdir, home)`
- the branch and the git status, as the session rows show them today
- the state of the workspace: the busiest state of its live chat sessions
  (`running` > `thinking` > `idle` > `dead`)
- a small mark when the workspace holds two or more live chat sessions (section 10)

**The children.** A workspace with one chat view shows no children. The row is enough. A workspace
with two or more views shows its chat sessions below the row. The client can hide them behind a
disclosure control.

⚠ **Keep the rows lean.** The digest records a durable rule from the reverted Android session-list
redesign: no large per-row avatars. The small status rail is the wanted design. A workspace row must
not become heavier than a session row is today.

⚠ **Keep the git data.** The digest records the user's hard rejection of any list that drops the
branch and the git status. The workspace row must show them.

**The groups.** The project groups do not change. `groupSessions()` groups by
`repo_root ?: workdir`. Change it to group **workspaces** by the same key. The Personal Assistants
group stays pinned at the top. A personal assistant keeps one workspace with one chat view.

**The order.** `PATCH /workspaces/reorder` replaces `PATCH /sessions/reorder` for the drag order.
Keep the session route for old clients. `sort_order` moves from the session to the workspace.

**The tasks.** The In Progress / Drafts / Settled sections keep their meaning. A workspace takes the
`user_status` of its primary session.

---

## 14. Compatibility

| Client | Behaviour after the change |
|---|---|
| App Store iOS 1.1 / 1.2 | It calls `GET /sessions` and reads the flat list. It ignores the workspace frames. It works as today. |
| Sideload APK 0.9.x | The same. |
| Web PWA | The user gets the new build with the server. |
| Old broker, new client | The client calls `GET /workspaces` and gets a 404. It then falls back to the flat list. Add this fallback. |

Rules:

- Do not remove `GET /sessions`, `POST /sessions`, `DELETE /sessions/:id`, or
  `PATCH /sessions/reorder`.
- Do not remove `session_added`, `session_removed`, `session_renamed`, `sessions_reordered`,
  `session_state`, or `agent_state`.
- Do not change the meaning of `sessions.workdir`.

---

## 15. Testing and verification

| Area | Test |
|---|---|
| Migration `027` | A `bun test` that opens a database with sessions from `026`, runs the migration, and checks that each session has a workspace, a chat view, and a valid layout. |
| Workspace store | `bun test` for make, change, archive, reorder, and the cascade delete of the views. |
| Layout tree | `commonTest` in `apps/shared` for each operation and each invariant of section 5.3. A TypeScript parity test with the same cases. |
| Routes | `bun test` for each route of section 7.1, and a test that each mutation sends its frame. |
| Path limit | A test that a path outside the workspace work directory gets HTTP 400. |
| Name propagation | `bun test` for section 9.5: a rename of the primary session renames the workspace and sends both frames; a rename of a second session does not; a manual workspace rename sets `name_locked` and stops the propagation; an equal name writes nothing. |
| Sidebar | A test of the workspace grouping in `apps/shared` `commonTest`, with the same cases as `SessionGroupingTest`. |
| Close a view | `bun test` for section 9.3: a close of a chat view archives the session; a close of a terminal view kills the tmux terminal; a close of a display view stops the stream; a close of an editor view stops nothing. Each one sends its frame. |
| No Finish on close | A test that a close of a chat view for a worktree session **does not** start a finish job, and that the work tree directory and the branch stay. |
| The invariant | `bun test` that a live session always has exactly one chat view, and that the self-heal step makes a workspace for a session it finds with no view. |
| Desktop UI | `runComposeUiTest` for the tab bar, the split drag, and the tab move. |
| Live check | The user opens two chats in one workspace on the Mac and checks the shared work tree. |

The repository verify gate is `.mux/verify.sh`. It runs the whole `bun test`.

⚠ The desktop Compose UI tests run in one fork. A headless run needs `Xvfb` and
`SKIKO_RENDER_API=SOFTWARE`.

---

## 16. Sequence of work

| Phase | Work | Risk |
|---|---|---|
| **0** | The rename of section 12. No behaviour change. | Low |
| **1** | Migration `027`, the workspace store, the routes of section 7.1, the new frames. The clients do not change. | Medium |
| **2** | The `LayoutTree` model in `apps/shared` and the TypeScript port, with the parity tests. | Low |
| **3** | The desktop client: the workspace sidebar (section 13.6), then tabs, splits, and drag. | High |
| **4** | The workspace scope for the terminal and for the editor (sections 7.3 and 7.4). | Medium |
| **5** | Web, macOS and iPad, Android tablet: the sidebar first, then the layout tree. | Medium |
| **6** | The phone small-screen rule. Move a view between workspaces. | Medium |
| **7** | The close rules of section 9.3: the confirmations, the Finish flow route, and the self-heal step. | Low |

The sidebar of section 13.6 comes before the layout tree on each client. A workspace row with one
chat view looks almost the same as a session row today. This gives an early, safe check of the new
model on real hardware.

Phase 1 and Phase 2 do not change what the user sees. They are safe to merge early.

### Out of scope

- A project table.
- A view kind that is not one of the four kinds. Add more kinds after Phase 5.
- A layout that is different for each device.
- More than one window for one workspace.

---

## 17. Risks

1. **Two agents in one work tree.** Section 10. The user accepted the default. The clients must show
   the share.
2. **The heavy AWT children on the desktop.** More panes means more KCEF and more JediTerm
   instances. Each one costs memory. Build them only when they are visible.
3. **The layout tree is easy to make invalid.** A drag operation can leave an empty group or a
   duplicate view id. The `normalize` function must run after every operation, on both sides.
4. **The small-screen write-back.** If a phone writes the layout back by mistake, it destroys the
   desktop arrangement. Test this rule.
5. **The migration touches every session row.** Take a copy of `~/.mux/state/db.sqlite3` before the
   first run.
6. **The `Viewing` frame change.** A wrong change stops a push, or sends a push for an open chat.
   Test both directions.
7. **The name propagation.** An agent renames its session often. Each rename now writes two rows and
   sends two frames. A loop between the two renames must not start. The broker must write the
   workspace name only when the new name is different from the stored name.
8. **The sidebar rebuild.** The session list is the surface the user looks at most. The digest
   records one full session-list redesign that the user rejected on sight. Make a mock of the
   workspace row and get the user's agreement before you build it.

---

## 18. Answers from the user (2026-08-06)

| # | Question | Answer | Effect on this document |
|---|---|---|---|
| 1 | The branch rule: do the agents in one workspace share one branch? | **Yes.** | Section 10 keeps the shared work tree and the shared branch. The three risk controls stay. |
| 2 | Does the server hold one layout for each workspace? | **Yes.** | Decision 3 and section 8.3 stand. Small screens read but do not write. |
| 3 | Does the workspace stay open after the user closes the last chat view? | **Yes.** | Section 9.3 stands. A workspace can hold only terminals, editors, and displays. |
| 4 | Must the sidebar show workspaces? | **Yes. The list shows workspaces from now on.** | New decision 10 and new section 13.6. |
| 5 | Where does the workspace name come from? | **From the session name. An agent rename of the session also renames the workspace.** | New decision 11 and new section 9.5. The workspace gets `primary_session_id` and `name_locked`. |

## 19. Answers about the close of a view (2026-08-06)

| # | Question | Answer | Effect on this document |
|---|---|---|---|
| 1 | Does a close of a chat view archive its session? | **Yes. It settles only that view. No Finish flow.** Settle and archive are one operation (section 1). | Section 9.3, "No Finish flow on a close". |
| 2 | Does a close of a terminal view stop the tmux terminal? | **Yes. Kill it.** | Section 9.3. |
| 3 | Does a close of a display view stop the display stream? | **Yes. Stop it.** | Section 9.3. |
| 4 | Does the client ask the user first? | **Yes.** | Section 9.3, "The confirmation". The editor is the one kind that does not ask. |
| 5 | What does the user see for a session that has no view? | The question does not apply. Answer 1 removes the state. | Section 9.3, "The invariant this creates". A live session always has exactly one chat view. |

Question 5 came from the earlier proposal, in which a close of a chat view kept the session alive.
Answer 1 removes that state. A session and its chat view now start together and end together. The
broker keeps a self-heal step for a session it finds with no view, but that state is a defect.

There are no open questions in this document.
