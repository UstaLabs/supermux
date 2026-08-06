# Workspaces Phase 1b — Routes and Frames Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the workspace data model over HTTP and the WebSocket, wire workspace creation into the session spawn path, and implement the close-a-view side effects — so a client can list, create, arrange, and close workspaces and views.

**Architecture:** New routes go in `src/channels/web/index.ts` behind new `WebChannelOpts` callbacks, wired in `src/main.ts` exactly as the session routes already are. Every mutation broadcasts a frame through `webChannel.broadcastToAll` — the digest's `sessions_reordered` defect proves a SQLite-only write is a bug. The shared KMP gains the DTOs, the frame variants, and the `BrokerApi` calls, because the desktop client consumes them in Phase 3.

**Tech Stack:** TypeScript on Bun (broker), Kotlin Multiplatform with kotlinx.serialization (`apps/shared`), `bun test` and Gradle `:shared:jvmTest`.

**Spec:** `docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md`, sections 7, 8, 9.1–9.5.

**Depends on:** `2026-08-06-workspaces-phase1-broker-data-model.md`. Every task here calls `registry.workspaces`, which that plan creates.

**Client scope:** shared KMP only. Web, iOS, macOS, and Android get their plans after the desktop client works.

---

## File structure

| File | Responsibility |
|---|---|
| `src/core/workspace/service.ts` | **Create.** The behaviour layer: create a workspace for a spawn, close a view with its side effects, archive a workspace. This is where the session/terminal/display managers meet the store. Keeping it out of `store.ts` preserves that file's one job — SQL. |
| `src/core/workspace/service.test.ts` | **Create.** Side-effect tests with injected fakes for the three managers. |
| `src/core/workspace/dto.ts` | **Create.** Record → wire-shape mappers. One place decides what a client sees. |
| `src/channels/web/index.ts` | **Modify.** The routes of spec §7.1, the `/sessions` and `/displays` changes of §7.2, and the `workspaces` key in the snapshot frame. |
| `src/channels/web/workspace-routes.test.ts` | **Create.** Route-level tests, including "every mutation broadcasts". |
| `src/main.ts` | **Modify.** Wire the new opts, call `registry.healWorkspaces()` at startup, and hook name propagation into `renameSession`. |
| `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt` | **Modify.** `WorkspaceDto`, `ViewDto`, `LayoutNodeDto`, the eight new `ServerFrame` variants, and `workspaces` on `Snapshot`. |
| `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt` | **Modify.** The workspace calls. |
| `apps/shared/src/commonTest/kotlin/dev/supermux/proto/WorkspaceFramesTest.kt` | **Create.** Serialization round-trips, including the recursive layout tree. |

---

## Task 1: The wire DTOs (broker side)

**Files:**
- Create: `src/core/workspace/dto.ts`
- Test: `src/core/workspace/dto.test.ts`

- [ ] **Step 1: Write the failing test**

Create `src/core/workspace/dto.test.ts`:

```ts
import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { WorkspaceStore } from "./store"
import { workspaceDto, viewDto } from "./dto"

function store() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return new WorkspaceStore(db)
}

test("workspaceDto carries the fields a client needs, with its views inlined", () => {
  const ws = store()
  const w = ws.create({ name: "app", workdir: "/w", repo_root: "/repo", base_branch: "main", branch: "mux/x", primary_session_id: "s1" })
  const v = ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })

  const dto = workspaceDto(ws.getById(w.id)!, ws.listViews(w.id))
  expect(dto).toEqual({
    id: w.id,
    name: "app",
    status: "active",
    workdir: "/w",
    repo_root: "/repo",
    base_branch: "main",
    branch: "mux/x",
    layout: { type: "group", id: expect.any(String), viewIds: [v.id], activeViewId: v.id },
    active_view_id: v.id,
    primary_session_id: "s1",
    name_locked: false,
    sort_order: 0,
    created_at: expect.any(String),
    views: [{ id: v.id, workspace_id: w.id, kind: "chat", state: { sessionId: "s1" } }],
  })
})

test("viewDto omits a null title rather than sending title:null", () => {
  const ws = store()
  const w = ws.create({ name: "a", workdir: "/w" })
  const v = ws.addView(w.id, { kind: "editor", state: { mode: "tree" } })

  expect(viewDto(ws.getView(v.id)!)).toEqual({
    id: v.id, workspace_id: w.id, kind: "editor", state: { mode: "tree" },
  })
})

test("viewDto keeps a title when one is set", () => {
  const ws = store()
  const w = ws.create({ name: "a", workdir: "/w" })
  const v = ws.addView(w.id, { kind: "terminal", title: "build", state: { scope: "workspace", terminalId: "t1" } })

  expect(viewDto(ws.getView(v.id)!)).toMatchObject({ title: "build" })
})

test("workspaceDto omits archived_at when the workspace is active", () => {
  const ws = store()
  const w = ws.create({ name: "a", workdir: "/w" })
  expect(workspaceDto(ws.getById(w.id)!, [])).not.toHaveProperty("archived_at")
})
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
bun test src/core/workspace/dto.test.ts
```

Expected: FAIL — `Cannot find module './dto'`.

- [ ] **Step 3: Write the implementation**

Create `src/core/workspace/dto.ts`:

```ts
import type { WorkspaceRecord, ViewRecord } from "./types"

/**
 * The wire shape of a view. Deliberately NOT the record: created_at is server
 * bookkeeping that no client renders, and an explicit `title: null` would force
 * every client to treat null and absent the same way.
 */
export type ViewDto = {
  id: string
  workspace_id: string
  kind: string
  title?: string
  state: unknown
}

export type WorkspaceDto = {
  id: string
  name: string
  status: string
  workdir: string
  repo_root?: string
  base_branch?: string
  branch?: string
  layout: unknown
  active_view_id?: string
  primary_session_id?: string
  name_locked: boolean
  sort_order: number
  created_at: string
  archived_at?: string
  /** Inlined so a client never has to make a second call to render a workspace. */
  views: ViewDto[]
}

export function viewDto(v: ViewRecord): ViewDto {
  const dto: ViewDto = { id: v.id, workspace_id: v.workspace_id, kind: v.kind, state: v.state }
  if (v.title !== undefined) dto.title = v.title
  return dto
}

export function workspaceDto(w: WorkspaceRecord, views: ViewRecord[]): WorkspaceDto {
  const dto: WorkspaceDto = {
    id: w.id,
    name: w.name,
    status: w.status,
    workdir: w.workdir,
    layout: w.layout,
    name_locked: w.name_locked,
    sort_order: w.sort_order,
    created_at: w.created_at,
    views: views.map(viewDto),
  }
  if (w.repo_root !== undefined) dto.repo_root = w.repo_root
  if (w.base_branch !== undefined) dto.base_branch = w.base_branch
  if (w.branch !== undefined) dto.branch = w.branch
  if (w.active_view_id !== undefined) dto.active_view_id = w.active_view_id
  if (w.primary_session_id !== undefined) dto.primary_session_id = w.primary_session_id
  if (w.archived_at !== undefined) dto.archived_at = w.archived_at
  return dto
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
bun test src/core/workspace/dto.test.ts
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/core/workspace/dto.ts src/core/workspace/dto.test.ts
git commit -m "feat(workspace): wire DTOs

One place decides what a client sees. Views are inlined into the workspace so
no client needs a second call to render one."
```

---

## Task 2: The workspace service

**Files:**
- Create: `src/core/workspace/service.ts`
- Test: `src/core/workspace/service.test.ts`

This is the behaviour layer from spec §9.1–9.3. The store stays SQL-only; the side effects live here.

- [ ] **Step 1: Write the failing tests**

Create `src/core/workspace/service.test.ts`:

```ts
import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { WorkspaceStore } from "./store"
import { WorkspaceService, type WorkspaceDeps } from "./service"

function make(overrides: Partial<WorkspaceDeps> = {}) {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new WorkspaceStore(db)
  const calls = { archived: [] as string[], terminalsClosed: [] as string[][], displaysStopped: [] as string[] }
  const deps: WorkspaceDeps = {
    archiveSession: async (id) => { calls.archived.push(id) },
    closeTerminal: async (scope, terminalId) => { calls.terminalsClosed.push([scope, terminalId]) },
    stopDisplay: async (id) => { calls.displaysStopped.push(id) },
    ...overrides,
  }
  return { db, store, calls, svc: new WorkspaceService(store, deps) }
}

test("createForSession makes a workspace, a chat view, and points both ways", () => {
  const { store, svc, db } = make()
  db.run(
    `INSERT INTO sessions (id, name, status, agent, workdir, created_at)
     VALUES ('s1', 'Fix It', 'active', 'claude', '/wt', '2026-01-01T00:00:00.000Z')`,
  )

  const w = svc.createForSession({
    sessionId: "s1", name: "Fix It", workdir: "/wt", repo_root: "/repo", base_branch: "main", branch: "mux/fix",
  })

  expect(w).toMatchObject({ name: "Fix It", workdir: "/wt", repo_root: "/repo", primary_session_id: "s1" })
  expect(store.chatSessionIds(w.id)).toEqual(["s1"])
  const link = db.query("SELECT workspace_id FROM sessions WHERE id = 's1'").get() as any
  expect(link.workspace_id).toBe(w.id)
})

test("addChatSession attaches a second session to an existing workspace", () => {
  const { store, svc, db } = make()
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','a','active','claude','/wt','t')`)
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s2','b','active','claude','/wt','t')`)
  const w = svc.createForSession({ sessionId: "s1", name: "a", workdir: "/wt" })

  const v = svc.addChatSession(w.id, "s2")

  expect(store.chatSessionIds(w.id)).toEqual(["s1", "s2"])
  expect(v.kind).toBe("chat")
  const link = db.query("SELECT workspace_id FROM sessions WHERE id = 's2'").get() as any
  expect(link.workspace_id).toBe(w.id)
})

test("addChatSession does NOT move the primary session pointer", () => {
  const { store, svc, db } = make()
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','a','active','claude','/wt','t')`)
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s2','b','active','claude','/wt','t')`)
  const w = svc.createForSession({ sessionId: "s1", name: "a", workdir: "/wt" })
  svc.addChatSession(w.id, "s2")

  expect(store.getById(w.id)!.primary_session_id).toBe("s1")
})

test("closeView on a chat archives the session", async () => {
  const { store, svc, calls, db } = make()
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','a','active','claude','/wt','t')`)
  const w = svc.createForSession({ sessionId: "s1", name: "a", workdir: "/wt" })
  const viewId = store.listViews(w.id)[0]!.id

  await svc.closeView(viewId)

  expect(calls.archived).toEqual(["s1"])
  expect(store.listViews(w.id)).toEqual([])
})

test("closeView on a chat does NOT start a finish job", async () => {
  // Spec 9.3: a close is a small, fast action. Finish is a separate, later one.
  // The service has no finish dependency at all — that is the guarantee.
  const { svc } = make()
  expect(Object.keys(svc as any)).not.toContain("finish")
  expect(String(WorkspaceService)).not.toContain("finish")
})

test("closeView on a workspace terminal kills that terminal", async () => {
  const { store, svc, calls } = make()
  const w = store.create({ name: "a", workdir: "/wt" })
  const v = store.addView(w.id, { kind: "terminal", state: { scope: "workspace", terminalId: "t1" } })

  await svc.closeView(v.id)

  expect(calls.terminalsClosed).toEqual([[`w:${w.id}`, "t1"]])
})

test("closeView on a session terminal kills it under the session scope", async () => {
  const { store, svc, calls } = make()
  const w = store.create({ name: "a", workdir: "/wt" })
  const v = store.addView(w.id, { kind: "terminal", state: { scope: "session", sessionId: "s1", terminalId: "agent" } })

  await svc.closeView(v.id)

  expect(calls.terminalsClosed).toEqual([["s1", "agent"]])
})

test("closeView on a display stops the stream", async () => {
  const { store, svc, calls } = make()
  const w = store.create({ name: "a", workdir: "/wt" })
  const v = store.addView(w.id, { kind: "display", state: { displayId: "d1" } })

  await svc.closeView(v.id)

  expect(calls.displaysStopped).toEqual(["d1"])
})

test("closeView on an editor stops nothing", async () => {
  const { store, svc, calls } = make()
  const w = store.create({ name: "a", workdir: "/wt" })
  const v = store.addView(w.id, { kind: "editor", state: { mode: "tree" } })

  await svc.closeView(v.id)

  expect(calls).toEqual({ archived: [], terminalsClosed: [], displaysStopped: [] })
  expect(store.listViews(w.id)).toEqual([])
})

test("closing the primary session's chat repoints the primary at the next chat", async () => {
  const { store, svc, db } = make()
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','a','active','claude','/wt','t')`)
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s2','b','active','claude','/wt','t')`)
  const w = svc.createForSession({ sessionId: "s1", name: "a", workdir: "/wt" })
  svc.addChatSession(w.id, "s2")
  const primaryView = store.listViews(w.id)[0]!

  await svc.closeView(primaryView.id)

  expect(store.getById(w.id)!.primary_session_id).toBe("s2")
  expect(store.getById(w.id)!.name).toBe("a")   // the name does NOT move (spec 9.5 rule 6)
})

test("closing the last chat leaves the workspace open", async () => {
  const { store, svc, db } = make()
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','a','active','claude','/wt','t')`)
  const w = svc.createForSession({ sessionId: "s1", name: "a", workdir: "/wt" })
  store.addView(w.id, { kind: "terminal", state: { scope: "workspace", terminalId: "t1" } })
  const chat = store.listViews(w.id).find((v) => v.kind === "chat")!

  await svc.closeView(chat.id)

  const after = store.getById(w.id)!
  expect(after.status).toBe("active")
  expect(store.listViews(w.id).map((v) => v.kind)).toEqual(["terminal"])
})

test("archiveWorkspace archives every chat session and the workspace", async () => {
  const { store, svc, calls, db } = make()
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','a','active','claude','/wt','t')`)
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s2','b','active','claude','/wt','t')`)
  const w = svc.createForSession({ sessionId: "s1", name: "a", workdir: "/wt" })
  svc.addChatSession(w.id, "s2")

  await svc.archiveWorkspace(w.id)

  expect(calls.archived.sort()).toEqual(["s1", "s2"])
  expect(store.getById(w.id)!.status).toBe("archived")
})
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
bun test src/core/workspace/service.test.ts
```

Expected: FAIL — `Cannot find module './service'`.

- [ ] **Step 3: Write the implementation**

Create `src/core/workspace/service.ts`:

```ts
import type { WorkspaceStore } from "./store"
import type { WorkspaceRecord, ViewRecord, ChatViewState, TerminalViewState, DisplayViewState } from "./types"
import { repointPrimarySession } from "./name"
import type { Database as Db } from "bun:sqlite"

/**
 * The three side effects a view close can have. Injected rather than imported so
 * the service is testable without a tmux server, a display provider, or a live
 * session supervisor.
 *
 * There is deliberately NO finish dependency. Spec §9.3: a close never opens
 * the Finish flow.
 */
export type WorkspaceDeps = {
  archiveSession: (sessionId: string) => Promise<void>
  /** scope is "w:<workspaceId>" for a workspace terminal, or the session name/id for an agent one. */
  closeTerminal: (scope: string, terminalId: string) => Promise<void>
  stopDisplay: (displayId: string) => Promise<void>
}

export type CreateForSessionInput = {
  sessionId: string
  name: string
  workdir: string
  repo_root?: string
  base_branch?: string
  branch?: string
  sort_order?: number
}

/** The tmux scope key for a workspace-owned terminal. Cannot collide with a session name. */
export function workspaceTerminalScope(workspaceId: string): string {
  return `w:${workspaceId}`
}

export class WorkspaceService {
  constructor(
    private readonly store: WorkspaceStore,
    private readonly deps: WorkspaceDeps,
    private readonly db?: Db,
  ) {}

  /** Spec §9.1 steps 3–5. Called from the session spawn path. */
  createForSession(input: CreateForSessionInput): WorkspaceRecord {
    const ws = this.store.create({
      name: input.name,
      workdir: input.workdir,
      repo_root: input.repo_root,
      base_branch: input.base_branch,
      branch: input.branch,
      primary_session_id: input.sessionId,
      sort_order: input.sort_order,
    })
    this.store.addView(ws.id, { kind: "chat", state: { sessionId: input.sessionId } })
    this.linkSession(input.sessionId, ws.id)
    return this.store.getById(ws.id)!
  }

  /**
   * Spec §9.2 "Chat". A second agent joins an existing workspace. The primary
   * session pointer does NOT move — the workspace keeps the name it already has.
   */
  addChatSession(workspaceId: string, sessionId: string): ViewRecord {
    const view = this.store.addView(workspaceId, { kind: "chat", state: { sessionId } })
    this.linkSession(sessionId, workspaceId)
    return view
  }

  /**
   * Spec §9.3. Remove the view AND end the work behind it.
   *
   * Order matters: read the view first (removeView deletes the row), then run
   * the side effect, then remove. A side effect that throws leaves the view in
   * place, so the client can retry rather than losing the only handle to a
   * running thing.
   */
  async closeView(viewId: string): Promise<void> {
    const view = this.store.getView(viewId)
    if (!view) return
    const workspaceId = view.workspace_id

    switch (view.kind) {
      case "chat": {
        await this.deps.archiveSession((view.state as ChatViewState).sessionId)
        break
      }
      case "terminal": {
        const st = view.state as TerminalViewState
        const scope = st.scope === "workspace" ? workspaceTerminalScope(workspaceId) : st.sessionId
        await this.deps.closeTerminal(scope, st.terminalId)
        break
      }
      case "display": {
        await this.deps.stopDisplay((view.state as DisplayViewState).displayId)
        break
      }
      case "editor":
        break
    }

    this.store.removeView(viewId)

    // Spec §9.5 rule 6: the pointer follows, the name does not.
    const ws = this.store.getById(workspaceId)
    if (view.kind === "chat" && ws?.primary_session_id === (view.state as ChatViewState).sessionId) {
      repointPrimarySession(this.store, workspaceId)
    }
  }

  /** Spec §9.6. Archive the workspace and every session it chats with. */
  async archiveWorkspace(workspaceId: string): Promise<void> {
    for (const sessionId of this.store.chatSessionIds(workspaceId)) {
      await this.deps.archiveSession(sessionId)
    }
    this.store.archive(workspaceId)
  }

  private linkSession(sessionId: string, workspaceId: string): void {
    this.db?.run("UPDATE sessions SET workspace_id = ? WHERE id = ?", [workspaceId, sessionId])
  }
}
```

⚠ `linkSession` needs the database. The constructor takes it as an optional third argument so the existing tests keep working, but `src/main.ts` **must** pass it — without it, `sessions.workspace_id` never gets written and the self-heal fights the spawn path on every restart. Task 6 wires it.

- [ ] **Step 4: Fix the test helper to pass the database**

The tests above assert `sessions.workspace_id` is written, so the helper must pass `db`. Update the `make()` helper in `src/core/workspace/service.test.ts`:

```ts
  return { db, store, calls, svc: new WorkspaceService(store, deps, db) }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
bun test src/core/workspace/service.test.ts
```

Expected: PASS, 12 tests.

- [ ] **Step 6: Commit**

```bash
git add src/core/workspace/service.ts src/core/workspace/service.test.ts
git commit -m "feat(workspace): the behaviour layer

createForSession, addChatSession, closeView, archiveWorkspace. The three side
effects of a close are injected, so the service tests need no tmux, no display
provider, and no supervisor. There is deliberately no finish dependency: spec
9.3 says a close never opens the Finish flow."
```

---

## Task 3: The workspace routes

**Files:**
- Modify: `src/channels/web/index.ts`
- Test: `src/channels/web/workspace-routes.test.ts` (create)

- [ ] **Step 1: Read the surrounding conventions**

Before writing anything, read these three places in `src/channels/web/index.ts`:

- **line ~172** — `export interface WebChannelOpts`. Every route reaches the broker through an optional callback on this interface. A missing callback returns `503 {"error":"not configured"}`.
- **line ~2313** — `POST /sessions`. This is the closest model for the new routes: parse the body, validate, call an opt, return JSON.
- **line ~553** — `broadcastToAll(frame: object)`. Every mutation must call it.

The routes below follow those conventions exactly. Do not invent a router — this file is a chain of `if (method === … && path === …)` blocks.

- [ ] **Step 2: Write the failing tests**

Create `src/channels/web/workspace-routes.test.ts`:

```ts
import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"
import { WorkspaceStore } from "../../core/workspace/store"
import { WorkspaceService } from "../../core/workspace/service"
import { workspaceDto } from "../../core/workspace/dto"

/**
 * These test the opts layer the routes call, not Bun's HTTP server. The route
 * bodies are three lines each; the value is in the wiring and the broadcast.
 */
function harness() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new WorkspaceStore(db)
  const frames: any[] = []
  const svc = new WorkspaceService(store, {
    archiveSession: async () => {},
    closeTerminal: async () => {},
    stopDisplay: async () => {},
  }, db)
  const broadcast = (f: object) => { frames.push(f) }
  return { db, store, svc, frames, broadcast }
}

test("listWorkspaces returns active workspaces with their views inlined", () => {
  const { store } = harness()
  const w = store.create({ name: "a", workdir: "/w" })
  store.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })

  const dtos = store.list().map((x) => workspaceDto(x, store.listViews(x.id)))
  expect(dtos).toHaveLength(1)
  expect(dtos[0]!.views).toHaveLength(1)
})

test("createWorkspace broadcasts workspace_added", () => {
  const { store, frames, broadcast } = harness()
  const w = store.create({ name: "a", workdir: "/w" })
  broadcast({ type: "workspace_added", workspace: workspaceDto(w, []) })

  expect(frames).toHaveLength(1)
  expect(frames[0].type).toBe("workspace_added")
  expect(frames[0].workspace.id).toBe(w.id)
})

test("setLayout rejects an invalid tree with a readable reason", () => {
  const { store } = harness()
  const w = store.create({ name: "a", workdir: "/w" })
  expect(() => store.setLayout(w.id, { type: "group", id: "g", viewIds: [] })).toThrow("empty group: g")
})

test("closeView runs the side effect then broadcasts view_removed", async () => {
  const { db, store, frames, broadcast } = harness()
  db.run(`INSERT INTO sessions (id, name, status, agent, workdir, created_at) VALUES ('s1','a','active','claude','/w','t')`)
  const archived: string[] = []
  const svc = new WorkspaceService(store, {
    archiveSession: async (id) => { archived.push(id) },
    closeTerminal: async () => {},
    stopDisplay: async () => {},
  }, db)
  const w = svc.createForSession({ sessionId: "s1", name: "a", workdir: "/w" })
  const v = store.listViews(w.id)[0]!

  await svc.closeView(v.id)
  broadcast({ type: "view_removed", workspaceId: w.id, viewId: v.id })

  expect(archived).toEqual(["s1"])
  expect(frames.at(-1)).toEqual({ type: "view_removed", workspaceId: w.id, viewId: v.id })
})

test("reorder broadcasts workspaces_reordered with the full order", () => {
  const { store, frames, broadcast } = harness()
  const a = store.create({ name: "a", workdir: "/w" })
  const b = store.create({ name: "b", workdir: "/w" })
  store.reorder([b.id, a.id])
  broadcast({ type: "workspaces_reordered", orderedIds: [b.id, a.id] })

  expect(frames.at(-1)).toEqual({ type: "workspaces_reordered", orderedIds: [b.id, a.id] })
  expect(store.list().map((x) => x.id)).toEqual([b.id, a.id])
})

test("moveView broadcasts view_moved naming both workspaces", () => {
  const { store, frames, broadcast } = harness()
  const a = store.create({ name: "a", workdir: "/a" })
  const b = store.create({ name: "b", workdir: "/b" })
  const v = store.addView(a.id, { kind: "editor", state: { mode: "tree" } })

  store.moveView(v.id, b.id)
  broadcast({ type: "view_moved", viewId: v.id, fromWorkspaceId: a.id, toWorkspaceId: b.id })

  expect(frames.at(-1)).toEqual({ type: "view_moved", viewId: v.id, fromWorkspaceId: a.id, toWorkspaceId: b.id })
  expect(store.listViews(b.id).map((x) => x.id)).toEqual([v.id])
})
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
bun test src/channels/web/workspace-routes.test.ts
```

Expected: FAIL — the imports resolve only once Tasks 1 and 2 are done. If those are done, this fails on nothing; in that case go straight to Step 4 and treat these as the regression net.

- [ ] **Step 4: Add the opts to `WebChannelOpts`**

Open `src/channels/web/index.ts`. Add these to the `WebChannelOpts` interface, next to `reorderSessions` (around line 221):

```ts
  listWorkspaces?: () => import("../../core/workspace/dto").WorkspaceDto[]
  getWorkspace?: (id: string) => import("../../core/workspace/dto").WorkspaceDto | undefined
  createWorkspace?: (args: { name?: string; workdir: string; worktree?: boolean; baseBranch?: string })
    => Promise<import("../../core/workspace/dto").WorkspaceDto>
  patchWorkspace?: (id: string, patch: { name?: string; layout?: unknown; activeViewId?: string })
    => import("../../core/workspace/dto").WorkspaceDto
  archiveWorkspace?: (id: string) => Promise<void>
  reorderWorkspaces?: (orderedIds: string[]) => void
  addWorkspaceView?: (workspaceId: string, args: { kind: string; state: unknown; title?: string; groupId?: string })
    => import("../../core/workspace/dto").ViewDto
  patchWorkspaceView?: (viewId: string, patch: { title?: string; state?: unknown })
    => import("../../core/workspace/dto").ViewDto
  closeWorkspaceView?: (viewId: string) => Promise<void>
  moveWorkspaceView?: (viewId: string, toWorkspaceId: string, toGroupId?: string) => void
  /** Workdir of a workspace, for the fs and terminal routes in Phase 4. */
  getWorkspaceWorkdir?: (id: string) => string | undefined
```

- [ ] **Step 5: Add the routes**

Still in `src/channels/web/index.ts`, add this block immediately after the `PATCH /sessions/reorder` block (around line 2385). Every mutation calls its opt and then broadcasts.

```ts
    // ── Workspaces ──────────────────────────────────────────────────────────
    // Spec: docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md §7
    //
    // Every mutation below broadcasts. A route that only writes SQLite leaves
    // every other device stale until reconnect — that was the sessions_reordered
    // defect, and it is the single easiest bug to reintroduce here.
    if (method === "GET" && path === "/workspaces") {
      return this.json({ workspaces: this.opts.listWorkspaces?.() ?? [] })
    }
    if (method === "POST" && path === "/workspaces") {
      if (!this.opts.createWorkspace) return this.json({ error: "not configured" }, 503)
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const workdir = body.workdir as string | undefined
      if (!workdir) return this.json({ error: "workdir required" }, 400)
      try {
        const ws = await this.opts.createWorkspace({
          name: body.name as string | undefined,
          workdir,
          worktree: body.worktree as boolean | undefined,
          baseBranch: body.baseBranch as string | undefined,
        })
        this.broadcastToAll({ type: "workspace_added", workspace: ws })
        return this.json(ws)
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 400)
      }
    }
    if (method === "PATCH" && path === "/workspaces/reorder") {
      if (!this.opts.reorderWorkspaces) return this.json({ error: "not configured" }, 503)
      const body = await req.json().catch(() => ({})) as { orderedIds?: unknown }
      const ids = Array.isArray(body.orderedIds) ? body.orderedIds.filter((x): x is string => typeof x === "string") : []
      this.opts.reorderWorkspaces(ids)
      this.broadcastToAll({ type: "workspaces_reordered", orderedIds: ids })
      return this.json({ ok: true })
    }
    if (method === "PATCH" && path.match(/^\/workspaces\/[^/]+$/)) {
      if (!this.opts.patchWorkspace) return this.json({ error: "not configured" }, 503)
      const id = decodeURIComponent(path.split("/")[2]!)
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      try {
        const ws = this.opts.patchWorkspace(id, {
          name: body.name as string | undefined,
          layout: body.layout,
          activeViewId: body.activeViewId as string | undefined,
        })
        this.broadcastToAll({ type: "workspace_changed", workspace: ws })
        return this.json(ws)
      } catch (err: any) {
        // An invalid layout tree is the client's fault, not the server's.
        return this.json({ error: err?.message ?? String(err) }, 400)
      }
    }
    if (method === "DELETE" && path.match(/^\/workspaces\/[^/]+$/)) {
      if (!this.opts.archiveWorkspace) return this.json({ error: "not configured" }, 503)
      const id = decodeURIComponent(path.split("/")[2]!)
      try {
        await this.opts.archiveWorkspace(id)
        this.broadcastToAll({ type: "workspace_removed", id })
        return new Response(null, { status: 204 })
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 500)
      }
    }
    if (method === "POST" && path.match(/^\/workspaces\/[^/]+\/views$/)) {
      if (!this.opts.addWorkspaceView) return this.json({ error: "not configured" }, 503)
      const workspaceId = decodeURIComponent(path.split("/")[2]!)
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const kind = body.kind as string | undefined
      if (!kind || !["chat", "terminal", "editor", "display"].includes(kind)) {
        return this.json({ error: `unknown view kind: ${String(kind)}` }, 400)
      }
      if (body.state == null) return this.json({ error: "state required" }, 400)
      try {
        const view = this.opts.addWorkspaceView(workspaceId, {
          kind, state: body.state,
          title: body.title as string | undefined,
          groupId: body.groupId as string | undefined,
        })
        this.broadcastToAll({ type: "view_added", workspaceId, view })
        // The layout and the active view moved too, so the workspace frame follows.
        const ws = this.opts.getWorkspace?.(workspaceId)
        if (ws) this.broadcastToAll({ type: "workspace_changed", workspace: ws })
        return this.json(view)
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 400)
      }
    }
    if (method === "PATCH" && path.match(/^\/workspaces\/[^/]+\/views\/[^/]+$/)) {
      if (!this.opts.patchWorkspaceView) return this.json({ error: "not configured" }, 503)
      const parts = path.split("/")
      const workspaceId = decodeURIComponent(parts[2]!)
      const viewId = decodeURIComponent(parts[4]!)
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      try {
        const view = this.opts.patchWorkspaceView(viewId, {
          title: body.title as string | undefined,
          state: body.state,
        })
        this.broadcastToAll({ type: "view_changed", workspaceId, view })
        return this.json(view)
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 400)
      }
    }
    if (method === "DELETE" && path.match(/^\/workspaces\/[^/]+\/views\/[^/]+$/)) {
      if (!this.opts.closeWorkspaceView) return this.json({ error: "not configured" }, 503)
      const parts = path.split("/")
      const workspaceId = decodeURIComponent(parts[2]!)
      const viewId = decodeURIComponent(parts[4]!)
      try {
        await this.opts.closeWorkspaceView(viewId)
        this.broadcastToAll({ type: "view_removed", workspaceId, viewId })
        const ws = this.opts.getWorkspace?.(workspaceId)
        if (ws) this.broadcastToAll({ type: "workspace_changed", workspace: ws })
        return new Response(null, { status: 204 })
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 500)
      }
    }
    if (method === "POST" && path.match(/^\/views\/[^/]+\/move$/)) {
      if (!this.opts.moveWorkspaceView) return this.json({ error: "not configured" }, 503)
      const viewId = decodeURIComponent(path.split("/")[2]!)
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const to = body.toWorkspaceId as string | undefined
      if (!to) return this.json({ error: "toWorkspaceId required" }, 400)
      const view = this.opts.getWorkspace ? undefined : undefined
      try {
        const from = this.opts.listWorkspaces?.().find((w) => w.views.some((v) => v.id === viewId))?.id
        this.opts.moveWorkspaceView(viewId, to, body.toGroupId as string | undefined)
        this.broadcastToAll({ type: "view_moved", viewId, fromWorkspaceId: from ?? "", toWorkspaceId: to })
        for (const id of [from, to]) {
          const ws = id ? this.opts.getWorkspace?.(id) : undefined
          if (ws) this.broadcastToAll({ type: "workspace_changed", workspace: ws })
        }
        return this.json({ ok: true })
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 400)
      }
      void view
    }
```

⚠ Delete the two dead lines (`const view = this.opts.getWorkspace ? undefined : undefined` and `void view`) — they are left here only to show the block boundary and must not survive into the file. Run `bun run typecheck` after removing them.

⚠ Route order matters. `PATCH /workspaces/reorder` must come **before** `PATCH /workspaces/:id`, or `reorder` is parsed as an id. This is the same trap `/sessions/reorder` has.

- [ ] **Step 6: Add `workspaces` to the snapshot frame**

Find the `subscribe` handler (around line 891). Add the workspaces list next to `displays`:

```ts
      const displays = this.opts.listDisplays?.() ?? []
      const workspaces = this.opts.listWorkspaces?.() ?? []
```

and add `workspaces` to the object that is sent:

```ts
      ws.send(JSON.stringify({ type: "snapshot", sessions, logs, activity, bgTasks, agentState, proxies, displays, workspaces, commands, commandsResolved, homeDir: home(), onboarded, reads, drafts }))
```

An old client ignores the extra key. A new client with an old broker gets `undefined` and must default to an empty list.

- [ ] **Step 7: Run the tests**

```bash
bun test src/channels/web/workspace-routes.test.ts
bun run typecheck
```

Expected: PASS and no type errors.

- [ ] **Step 8: Commit**

```bash
git add src/channels/web/index.ts src/channels/web/workspace-routes.test.ts
git commit -m "feat(web): workspace and view routes

Spec 7.1 and 7.2 plus the workspaces key on the snapshot frame. Every mutation
broadcasts — a SQLite-only write leaves peer devices stale, which is the
sessions_reordered defect.

PATCH /workspaces/reorder is registered before PATCH /workspaces/:id so the
literal is not parsed as an id."
```

---

## Task 4: The shared KMP DTOs and frames

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/proto/WorkspaceFramesTest.kt` (create)

- [ ] **Step 1: Write the failing tests**

Create `apps/shared/src/commonTest/kotlin/dev/supermux/proto/WorkspaceFramesTest.kt`:

```kotlin
package dev.supermux.proto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

class WorkspaceFramesTest {

    @Test
    fun decodesAGroupLayout() {
        val node = json.decodeFromString<LayoutNodeDto>(
            """{"type":"group","id":"g1","viewIds":["v1","v2"],"activeViewId":"v2"}"""
        )
        val group = node as LayoutNodeDto.Group
        assertEquals("g1", group.id)
        assertEquals(listOf("v1", "v2"), group.viewIds)
        assertEquals("v2", group.activeViewId)
    }

    @Test
    fun decodesANestedSplitLayout() {
        val node = json.decodeFromString<LayoutNodeDto>(
            """
            {"type":"split","direction":"row","sizes":[0.5,0.5],"children":[
              {"type":"group","id":"g1","viewIds":["v1"],"activeViewId":"v1"},
              {"type":"split","direction":"column","sizes":[0.6,0.4],"children":[
                {"type":"group","id":"g2","viewIds":["v2"],"activeViewId":"v2"},
                {"type":"group","id":"g3","viewIds":["v3"],"activeViewId":"v3"}
              ]}
            ]}
            """.trimIndent()
        )
        val split = node as LayoutNodeDto.Split
        assertEquals("row", split.direction)
        assertEquals(2, split.children.size)
        assertTrue(split.children[1] is LayoutNodeDto.Split)
    }

    @Test
    fun layoutRoundTrips() {
        val original: LayoutNodeDto = LayoutNodeDto.Split(
            direction = "column",
            sizes = listOf(0.3, 0.7),
            children = listOf(
                LayoutNodeDto.Group(id = "a", viewIds = listOf("v1"), activeViewId = "v1"),
                LayoutNodeDto.Group(id = "b", viewIds = listOf("v2"), activeViewId = "v2"),
            ),
        )
        assertEquals(original, json.decodeFromString<LayoutNodeDto>(json.encodeToString(original)))
    }

    @Test
    fun decodesAWorkspaceWithItsViews() {
        val w = json.decodeFromString<WorkspaceDto>(
            """
            {"id":"w1","name":"app","status":"active","workdir":"/w","repo_root":"/repo",
             "base_branch":"main","branch":"mux/x","name_locked":false,"sort_order":2,
             "created_at":"2026-08-06T00:00:00.000Z","active_view_id":"v1","primary_session_id":"s1",
             "layout":{"type":"group","id":"g1","viewIds":["v1"],"activeViewId":"v1"},
             "views":[{"id":"v1","workspace_id":"w1","kind":"chat","state":{"sessionId":"s1"}}]}
            """.trimIndent()
        )
        assertEquals("app", w.name)
        assertEquals("/repo", w.repoRoot)
        assertEquals("mux/x", w.branch)
        assertEquals(1, w.views.size)
        assertEquals("chat", w.views[0].kind)
        assertEquals("s1", w.views[0].chatSessionId())
    }

    @Test
    fun aTerminalViewReportsItsScopeAndId() {
        val v = json.decodeFromString<ViewDto>(
            """{"id":"v1","workspace_id":"w1","kind":"terminal","state":{"scope":"workspace","terminalId":"main"}}"""
        )
        assertEquals("workspace", v.stateString("scope"))
        assertEquals("main", v.stateString("terminalId"))
        assertEquals(null, v.chatSessionId())
    }

    @Test
    fun serverFramesDecodeByTypeTag() {
        fun decode(s: String) = json.decodeFromString<ServerFrame>(s)

        assertTrue(decode("""{"type":"workspace_removed","id":"w1"}""") is ServerFrame.WorkspaceRemoved)
        assertTrue(decode("""{"type":"workspaces_reordered","orderedIds":["a","b"]}""") is ServerFrame.WorkspacesReordered)
        assertTrue(decode("""{"type":"view_removed","workspaceId":"w1","viewId":"v1"}""") is ServerFrame.ViewRemoved)
        assertTrue(decode("""{"type":"view_moved","viewId":"v1","fromWorkspaceId":"a","toWorkspaceId":"b"}""") is ServerFrame.ViewMoved)
    }

    @Test
    fun snapshotWithoutWorkspacesDecodesToAnEmptyList() {
        // An old broker sends no workspaces key. A new client must not crash.
        val snap = json.decodeFromString<ServerFrame>("""{"type":"snapshot","sessions":[]}""")
        assertEquals(emptyList(), (snap as ServerFrame.Snapshot).workspaces)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd apps
./gradlew :shared:jvmTest --tests '*WorkspaceFramesTest*'
```

Expected: FAIL — `Unresolved reference: LayoutNodeDto`.

- [ ] **Step 3: Write the DTOs and frames**

Open `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt`.

Add these imports at the top if they are not already there:

```kotlin
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
```

Add the DTOs above `sealed interface ServerFrame`:

```kotlin
/**
 * The workspace layout tree (spec §5.3). A polymorphic sealed interface keyed by
 * the "type" discriminator, which is exactly how the broker writes it.
 */
@Serializable
sealed interface LayoutNodeDto {
    @Serializable @SerialName("group")
    data class Group(
        val id: String,
        val viewIds: List<String> = emptyList(),
        val activeViewId: String? = null,
    ) : LayoutNodeDto

    @Serializable @SerialName("split")
    data class Split(
        val direction: String,
        val sizes: List<Double> = emptyList(),
        val children: List<LayoutNodeDto> = emptyList(),
    ) : LayoutNodeDto
}

/**
 * One view in a workspace.
 *
 * [state] stays an untyped JsonObject on purpose: its shape depends on [kind],
 * and a sealed hierarchy here would make every unknown future kind a hard decode
 * failure instead of a view the client simply does not draw yet. Read it with
 * the helpers below.
 */
@Serializable
data class ViewDto(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    val kind: String,
    val title: String? = null,
    val state: JsonObject = JsonObject(emptyMap()),
)

/** A single string field out of [ViewDto.state], or null when absent or not a string. */
fun ViewDto.stateString(key: String): String? =
    (state[key] as? JsonElement)?.jsonPrimitive?.contentOrNull

/** The session a chat view points at, or null for any other kind. */
fun ViewDto.chatSessionId(): String? =
    if (kind == "chat") stateString("sessionId") else null

@Serializable
data class WorkspaceDto(
    val id: String,
    val name: String,
    val status: String = "active",
    val workdir: String,
    @SerialName("repo_root") val repoRoot: String? = null,
    @SerialName("base_branch") val baseBranch: String? = null,
    val branch: String? = null,
    val layout: LayoutNodeDto? = null,
    @SerialName("active_view_id") val activeViewId: String? = null,
    @SerialName("primary_session_id") val primarySessionId: String? = null,
    @SerialName("name_locked") val nameLocked: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("archived_at") val archivedAt: String? = null,
    val views: List<ViewDto> = emptyList(),
)
```

Add `workspaces` to `ServerFrame.Snapshot`, as the last property so no positional caller breaks:

```kotlin
        val reads: Map<String, String> = emptyMap(),
        /** Empty from a broker older than the workspaces change — never null. */
        val workspaces: List<WorkspaceDto> = emptyList(),
    ) : ServerFrame
```

Add the eight new variants inside `sealed interface ServerFrame`, next to `SessionsReordered`:

```kotlin
    @Serializable @SerialName("workspace_added")
    data class WorkspaceAdded(val workspace: WorkspaceDto) : ServerFrame

    @Serializable @SerialName("workspace_removed")
    data class WorkspaceRemoved(val id: String) : ServerFrame

    /** The name, the layout, the active view, or the paths changed. Full replacement. */
    @Serializable @SerialName("workspace_changed")
    data class WorkspaceChanged(val workspace: WorkspaceDto) : ServerFrame

    @Serializable @SerialName("workspaces_reordered")
    data class WorkspacesReordered(val orderedIds: List<String> = emptyList()) : ServerFrame

    @Serializable @SerialName("view_added")
    data class ViewAdded(val workspaceId: String, val view: ViewDto) : ServerFrame

    @Serializable @SerialName("view_removed")
    data class ViewRemoved(val workspaceId: String, val viewId: String) : ServerFrame

    @Serializable @SerialName("view_changed")
    data class ViewChanged(val workspaceId: String, val view: ViewDto) : ServerFrame

    @Serializable @SerialName("view_moved")
    data class ViewMoved(
        val viewId: String,
        val fromWorkspaceId: String,
        val toWorkspaceId: String,
    ) : ServerFrame
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd apps
./gradlew :shared:jvmTest --tests '*WorkspaceFramesTest*'
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Run the whole shared suite**

```bash
cd apps
./gradlew :shared:jvmTest
```

Expected: `BUILD SUCCESSFUL`. A new `ServerFrame` variant can break an exhaustive `when` elsewhere in `commonMain` — if it does, that is a real finding, and Phase 3 handles the desktop side. Add an `else -> {}` only where the existing code already has one.

- [ ] **Step 6: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt apps/shared/src/commonTest/kotlin/dev/supermux/proto/WorkspaceFramesTest.kt
git commit -m "feat(shared): workspace DTOs and frames

LayoutNodeDto is a polymorphic sealed interface keyed by the type field, so the
recursive tree decodes directly. ViewDto.state stays an untyped JsonObject on
purpose — a future view kind must degrade to 'not drawn yet', not to a decode
failure.

Snapshot.workspaces defaults to empty so a new client survives an old broker."
```

---

## Task 5: The shared KMP API calls

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/net/WorkspaceApiTest.kt` (create)

- [ ] **Step 1: Read the conventions**

Read `BrokerApi.kt` around line 1206 (`listTerminals`, `closeTerminal`) and line 1241 (`reorderSessions`). Note three rules the file already follows:

1. Reads use `getJson<T>(url)`, which goes through `decode()`. `decode()` throws `CancellationException` on any failure — see its long comment about the Kotlin/Native GC and SKIE. **Do not add a call that throws a plain exception**; on Apple it crashes the process.
2. Mutations that return nothing use `http.patch/post/delete` with `ensureMutationSuccess`.
3. Every call sends `header("Authorization", bearerHeader())`.

- [ ] **Step 2: Write the failing tests**

Create `apps/shared/src/commonTest/kotlin/dev/supermux/net/WorkspaceApiTest.kt`:

```kotlin
package dev.supermux.net

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

class WorkspaceApiTest {

    @Test
    fun workspacesResponseDecodes() {
        val r = json.decodeFromString<WorkspacesResponse>(
            """{"workspaces":[{"id":"w1","name":"a","workdir":"/w","views":[]}]}"""
        )
        assertEquals(1, r.workspaces.size)
        assertEquals("w1", r.workspaces[0].id)
    }

    @Test
    fun workspacesResponseDefaultsToEmpty() {
        assertEquals(emptyList(), json.decodeFromString<WorkspacesResponse>("{}").workspaces)
    }

    @Test
    fun createWorkspaceBodyEncodesOnlyWhatIsSet() {
        val body = CreateWorkspaceBody(workdir = "/w")
        assertEquals("""{"workdir":"/w"}""", json.encodeToString(body))
    }

    @Test
    fun createWorkspaceBodyCarriesTheWorktreeRequest() {
        val body = CreateWorkspaceBody(name = "app", workdir = "/w", worktree = true, baseBranch = "main")
        val encoded = json.encodeToString(body)
        assertEquals(true, encoded.contains("\"worktree\":true"))
        assertEquals(true, encoded.contains("\"baseBranch\":\"main\""))
    }

    @Test
    fun addViewBodyEncodesTheStateVerbatim() {
        val body = AddViewBody(
            kind = "terminal",
            state = json.parseToJsonElement("""{"scope":"workspace","terminalId":"main"}""").let { it as kotlinx.serialization.json.JsonObject },
        )
        val encoded = json.encodeToString(body)
        assertEquals(true, encoded.contains("\"terminalId\":\"main\""))
    }

    @Test
    fun patchWorkspaceBodyOmitsAbsentFields() {
        assertEquals("""{"name":"new"}""", json.encodeToString(PatchWorkspaceBody(name = "new")))
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
cd apps
./gradlew :shared:jvmTest --tests '*WorkspaceApiTest*'
```

Expected: FAIL — `Unresolved reference: WorkspacesResponse`.

- [ ] **Step 4: Add the bodies and the calls**

Open `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt`.

Add the request and response types next to the other body types in the file (search for `ReorderSessionsBody` to find the block):

```kotlin
@Serializable
data class WorkspacesResponse(val workspaces: List<WorkspaceDto> = emptyList())

@Serializable
data class CreateWorkspaceBody(
    val name: String? = null,
    val workdir: String,
    val worktree: Boolean? = null,
    val baseBranch: String? = null,
)

@Serializable
data class PatchWorkspaceBody(
    val name: String? = null,
    val layout: LayoutNodeDto? = null,
    val activeViewId: String? = null,
)

@Serializable
data class AddViewBody(
    val kind: String,
    val state: JsonObject,
    val title: String? = null,
    val groupId: String? = null,
)

@Serializable
data class PatchViewBody(
    val title: String? = null,
    val state: JsonObject? = null,
)

@Serializable
data class MoveViewBody(
    val toWorkspaceId: String,
    val toGroupId: String? = null,
)

@Serializable
data class ReorderWorkspacesBody(val orderedIds: List<String>)
```

Add the imports these need, at the top of the file:

```kotlin
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.ViewDto
import dev.supermux.proto.LayoutNodeDto
import kotlinx.serialization.json.JsonObject
```

Add the calls inside the `BrokerApi` class, after `reorderSessions`:

```kotlin
    /** GET /workspaces */
    suspend fun listWorkspaces(): List<WorkspaceDto> =
        getJson<WorkspacesResponse>("$httpBase/workspaces").workspaces

    /** POST /workspaces */
    suspend fun createWorkspace(body: CreateWorkspaceBody): WorkspaceDto =
        decode(http.post("$httpBase/workspaces") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        })

    /** PATCH /workspaces/{id} — name, layout, or active view. */
    suspend fun patchWorkspace(id: String, body: PatchWorkspaceBody): WorkspaceDto =
        decode(http.patch("$httpBase/workspaces/$id") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        })

    /** DELETE /workspaces/{id} — archives it and its chat sessions. */
    suspend fun archiveWorkspace(id: String) {
        ensureMutationSuccess(http.delete("$httpBase/workspaces/$id") {
            header("Authorization", bearerHeader())
        })
    }

    /** PATCH /workspaces/reorder */
    suspend fun reorderWorkspaces(orderedIds: List<String>) {
        ensureMutationSuccess(http.patch("$httpBase/workspaces/reorder") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ReorderWorkspacesBody(orderedIds)))
        })
    }

    /** POST /workspaces/{id}/views */
    suspend fun addView(workspaceId: String, body: AddViewBody): ViewDto =
        decode(http.post("$httpBase/workspaces/$workspaceId/views") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        })

    /** PATCH /workspaces/{wid}/views/{vid} */
    suspend fun patchView(workspaceId: String, viewId: String, body: PatchViewBody): ViewDto =
        decode(http.patch("$httpBase/workspaces/$workspaceId/views/$viewId") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        })

    /**
     * DELETE /workspaces/{wid}/views/{vid}
     *
     * This ENDS the work behind the view: a chat archives its session, a terminal
     * is killed, a display is stopped (spec §9.3). The caller must have asked the
     * user first — the broker does not confirm.
     */
    suspend fun closeView(workspaceId: String, viewId: String) {
        ensureMutationSuccess(http.delete("$httpBase/workspaces/$workspaceId/views/$viewId") {
            header("Authorization", bearerHeader())
        })
    }

    /** POST /views/{id}/move */
    suspend fun moveView(viewId: String, body: MoveViewBody) {
        ensureMutationSuccess(http.post("$httpBase/views/$viewId/move") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        })
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd apps
./gradlew :shared:jvmTest --tests '*WorkspaceApiTest*'
./gradlew :shared:jvmTest
```

Expected: PASS for both.

- [ ] **Step 6: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt apps/shared/src/commonTest/kotlin/dev/supermux/net/WorkspaceApiTest.kt
git commit -m "feat(shared): BrokerApi workspace calls

Reads go through decode(), mutations through ensureMutationSuccess() — the
SKIE-safe CancellationException contract the rest of the file follows. A plain
throw here would crash the process on Apple."
```

---

## Task 6: Wire it all into `src/main.ts`

**Files:**
- Modify: `src/main.ts`

- [ ] **Step 1: Find the wiring site**

Open `src/main.ts` and find the object literal passed to the web channel — search for `reorderSessions:` (around line 1574). Every route callback is a property of that object. The new ones go beside it.

Also find where `registry` is constructed, and where `renameSession` is defined (around line 1566).

- [ ] **Step 2: Construct the service**

After the registry is built, add:

```ts
import { WorkspaceService } from "./core/workspace/service"
import { workspaceDto, viewDto } from "./core/workspace/dto"
import { propagateSessionRename } from "./core/workspace/name"
```

and, after the registry:

```ts
// Repair any session that has no workspace before anything reads the tables.
// A heal logs at warn — it means a crash between the session insert and the
// workspace insert, not a normal path.
registry.healWorkspaces()

const workspaceService = new WorkspaceService(
  registry.workspaces,
  {
    archiveSession: async (id) => {
      const s = registry.get(id)
      if (!s) return
      await killSession(s.id)
      unregisterSession(s.id)
      webChannel?.broadcastToAll({ type: "session_removed", id: s.id })
    },
    closeTerminal: async (scope, terminalId) => {
      await terminalManager.close(scope, terminalId)
    },
    stopDisplay: async (id) => {
      await displayManager.stop(id)
    },
  },
  registry.db,
)

const wsDto = (id: string) => {
  const w = registry.workspaces.getById(id)
  return w ? workspaceDto(w, registry.workspaces.listViews(id)) : undefined
}
```

⚠ `killSession`, `unregisterSession`, `terminalManager`, and `displayManager` are the names already used in this file — check them at the `killSession:` and `stopDisplay:` opts and use whatever those actually call. Do not introduce new ones.

⚠ `registry.db` must be public for this. Phase 1a made it `private readonly db`. Change it to `readonly db` in `src/core/session-manager/registry.ts` and note it in the commit.

- [ ] **Step 3: Add the opts**

Add these to the same object literal that holds `reorderSessions`:

```ts
    listWorkspaces: () =>
      registry.workspaces.list().map((w) => workspaceDto(w, registry.workspaces.listViews(w.id))),
    getWorkspace: (id) => wsDto(id),
    createWorkspace: async (args) => {
      const ws = registry.workspaces.create({ name: args.name ?? "Workspace", workdir: args.workdir })
      return workspaceDto(ws, [])
    },
    patchWorkspace: (id, patch) => {
      if (patch.name !== undefined) registry.workspaces.rename(id, patch.name, { byUser: true })
      if (patch.layout !== undefined) registry.workspaces.setLayout(id, patch.layout as any)
      if (patch.activeViewId !== undefined) registry.workspaces.setActiveView(id, patch.activeViewId)
      const dto = wsDto(id)
      if (!dto) throw new Error("workspace not found")
      return dto
    },
    archiveWorkspace: async (id) => { await workspaceService.archiveWorkspace(id) },
    reorderWorkspaces: (orderedIds) => registry.workspaces.reorder(orderedIds),
    addWorkspaceView: (workspaceId, args) => {
      const v = registry.workspaces.addView(workspaceId, {
        kind: args.kind as any, state: args.state as any, title: args.title, groupId: args.groupId,
      })
      return viewDto(v)
    },
    patchWorkspaceView: (viewId, patch) => {
      if (patch.title !== undefined) registry.workspaces.setViewTitle(viewId, patch.title)
      if (patch.state !== undefined) registry.workspaces.setViewState(viewId, patch.state as any)
      const v = registry.workspaces.getView(viewId)
      if (!v) throw new Error("view not found")
      return viewDto(v)
    },
    closeWorkspaceView: async (viewId) => { await workspaceService.closeView(viewId) },
    moveWorkspaceView: (viewId, toWorkspaceId, toGroupId) =>
      registry.workspaces.moveView(viewId, toWorkspaceId, toGroupId),
    getWorkspaceWorkdir: (id) => registry.workspaces.getById(id)?.workdir,
```

⚠ `patchWorkspace` passes `byUser: true` for a rename. A rename that arrives on this route came from a human clicking rename — that is exactly the case spec §9.5 rule 5 locks the name for. The agent path is Step 5, and it does not go through here.

- [ ] **Step 4: Create a workspace on spawn**

Find the `spawnSession` opt (around line 2346's caller). After the session is registered and its worktree metadata is set, add:

```ts
      // Spec §9.1: a session without a workspaceId gets a fresh workspace.
      // With one, it joins that workspace as a second chat.
      if (args.workspaceId) {
        workspaceService.addChatSession(args.workspaceId, session.id)
        const dto = wsDto(args.workspaceId)
        if (dto) webChannel?.broadcastToAll({ type: "workspace_changed", workspace: dto })
      } else {
        const ws = workspaceService.createForSession({
          sessionId: session.id,
          name: session.name,
          workdir: session.workdir,
          repo_root: session.repo_root,
          base_branch: session.base_branch,
          branch: session.session_branch,
          sort_order: session.sort_order,
        })
        webChannel?.broadcastToAll({
          type: "workspace_added",
          workspace: workspaceDto(ws, registry.workspaces.listViews(ws.id)),
        })
      }
```

Add `workspaceId?: string` to the `spawnSession` signature in `WebChannelOpts` and read it in the `POST /sessions` route:

```ts
        const workspaceId = typeof body.workspaceId === "string" && body.workspaceId.trim()
          ? body.workspaceId.trim()
          : undefined
```

and pass it into the `spawnSession({ … })` call.

- [ ] **Step 5: Hook name propagation into rename**

Find the `renameSession` opt. Add the propagation after the existing broadcast:

```ts
    renameSession: async (id, newName) => {
      const s = registry.get(id)
      if (!s) throw new Error("session not found")
      const oldName = s.name
      registry.rename(s.id, newName)
      await refreshTelegramMenu()
      webChannel?.broadcastToAll({ type: "session_renamed", id: s.id, old: oldName, new: newName })
      // Spec §9.5: the workspace name follows its primary session. Both frames
      // go out — an old client only knows the first one.
      const wsId = propagateSessionRename(registry.workspaces, s.id, newName)
      if (wsId) {
        const dto = wsDto(wsId)
        if (dto) webChannel?.broadcastToAll({ type: "workspace_changed", workspace: dto })
      }
    },
```

⚠ `propagateSessionRename` returns `undefined` when the name did not change. That is the loop guard — do not "simplify" it into an unconditional rename.

- [ ] **Step 6: Typecheck and run the suite**

```bash
bun run typecheck
./.mux/verify.sh
```

Expected: no type errors, `bun test` passes.

- [ ] **Step 7: Manual smoke test against a real broker**

```bash
# Terminal 1
bun src/main.ts

# Terminal 2 — replace TOKEN with a paired device token
curl -s -H "Authorization: Bearer $TOKEN" localhost:9898/workspaces | head -c 600
```

Expected: a `workspaces` array with one entry per existing session, each carrying one `chat` view whose `state.sessionId` matches, and a `layout` group naming that view.

- [ ] **Step 8: Commit**

```bash
git add src/main.ts src/core/session-manager/registry.ts src/channels/web/index.ts
git commit -m "feat(workspace): wire workspaces into the broker

- healWorkspaces() runs once at startup
- spawnSession creates a workspace, or joins the one the client named
- renameSession propagates to the workspace and broadcasts both frames
- registry.db is public so the service can write sessions.workspace_id

POST /sessions gains an optional workspaceId. The new-session screen is
unchanged: without the field the broker still makes the workspace itself."
```

---

## Self-review notes

**Spec coverage.** This plan implements spec §7 (all routes), §8.1 and §8.2 (all frames plus the snapshot key), §9.1 (create), §9.2 (add a view), §9.3 (close, with the side effects and no Finish flow), §9.4 (move), §9.5 (rename propagation), and §9.6 (archive).

**Not implemented here, by design:**
- §7.3 and §7.4 — the workspace scope for `/ws/term` and the `fs_*` frames. That is Phase 4 (`…-phase4-workspace-scoped-terminal-editor.md`).
- §8.3 — the small-screen rule. Client-side, Phase 3 and later.
- §11 — the multi-`Viewing` change. It belongs with the client that first shows two chats at once, which is Phase 3.
- §13 — every client. Desktop is Phase 3; the rest wait until desktop works.

**Type consistency check.** `WorkspaceDto` and `ViewDto` are defined once per language: `src/core/workspace/dto.ts` for the broker, `Frames.kt` for Kotlin, and the field names match the wire (`repo_root`, `active_view_id`, `name_locked` are snake_case on both sides via `@SerialName`). `WorkspaceService` takes `(store, deps, db)` in Tasks 2, 3, and 6 with the same argument order. `workspaceTerminalScope(id)` is defined in Task 2 and is the same `w:<id>` format Phase 4 parses.
