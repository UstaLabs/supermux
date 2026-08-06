# Workspaces Phase 1 — Broker Data Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `workspaces` and `views` tables, a `WorkspaceStore`, a layout-tree module, and the name-propagation rule to the broker — so that every session has a workspace and a chat view, with no visible change for any client.

**Architecture:** A new `src/core/workspace/` module holds four files: pure layout-tree logic, the record types, the SQLite store, and the name-propagation rule. Migration `027_workspaces.sql` creates the tables and backfills one workspace + one chat view per existing session. `Registry` gains a `workspaces` store and a startup self-heal. No HTTP route and no WebSocket frame is touched in this plan — clients keep seeing exactly today's flat session list.

**Tech Stack:** TypeScript on Bun, `bun:sqlite` (SQLite with the JSON1 extension), `bun test`. Migrations are `.sql` text files imported into a manifest.

**Spec:** `docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md`, sections 5, 6, 9.5, and 9.3.

**Depends on:** nothing. This plan can run before, after, or in parallel with the Phase 0 shell rename — they touch disjoint directories.

---

## Why this is safe to land early

Nothing in this plan is reachable from a client. There is no route, no frame, and no behaviour change. The migration is a pure backfill: after it runs, every session behaves exactly as it did before. The value is that the data model exists and is tested, so Phase 1b (routes + frames) is a thin layer on top of proven storage.

⚠ **Take a database copy before the first run against real data.**

```bash
cp ~/.mux/state/db.sqlite3 ~/.mux/state/db.sqlite3.pre-027
```

The live broker database is `~/.mux/state/db.sqlite3`. The `supermux.db` files in that directory are 0-byte decoys — do not copy those.

---

## File structure

| File | Responsibility |
|---|---|
| `src/core/workspace/layout-tree.ts` | **Create.** Pure functions over the split/group tree. No database, no I/O. Phase 2 ports this file to Kotlin with parity tests, so it must stay free of Node and Bun APIs. |
| `src/core/workspace/layout-tree.test.ts` | **Create.** Tests for every function and every invariant. |
| `src/core/workspace/types.ts` | **Create.** `WorkspaceRecord`, `WorkspaceRow`, `ViewRecord`, `ViewRow`, `ViewState`, and the row→record mappers. Mirrors the shape of `src/core/session-manager/types.ts`. |
| `src/core/workspace/store.ts` | **Create.** `WorkspaceStore` — all SQL for both tables. The only file in the module that touches `bun:sqlite`. |
| `src/core/workspace/store.test.ts` | **Create.** Store tests against an in-memory database. |
| `src/core/workspace/name.ts` | **Create.** The name-propagation rule from spec 9.5. Pure decision function plus a thin applier. |
| `src/core/workspace/name.test.ts` | **Create.** Tests for every branch of the rule, including the no-write-when-equal case. |
| `src/core/workspace/self-heal.ts` | **Create.** Startup repair: a live session with no workspace gets one. |
| `src/core/workspace/self-heal.test.ts` | **Create.** |
| `src/core/storage/migrations/027_workspaces.sql` | **Create.** Tables plus backfill. |
| `src/core/storage/migrations/index.ts` | **Modify.** Add the import and the manifest row. |
| `src/core/storage/migrations/027_workspaces.test.ts` | **Create.** Backfill correctness against a database seeded at version 026. |
| `src/core/session-manager/registry.ts` | **Modify.** Construct and expose `workspaces`; call the self-heal. |

The module is deliberately split by responsibility, not by layer: the pure logic, the types, the SQL, and the two rules each live in their own file. `store.ts` is the single place that knows SQL exists.

---

## Task 1: The layout tree module

**Files:**
- Create: `src/core/workspace/layout-tree.ts`
- Test: `src/core/workspace/layout-tree.test.ts`

This is pure data-structure work. Build it first, because the store stores its output and the migration writes its shape.

- [ ] **Step 1: Write the failing tests**

Create `src/core/workspace/layout-tree.test.ts`:

```ts
import { test, expect } from "bun:test"
import {
  type LayoutNode,
  collectViewIds,
  validateLayout,
  normalizeLayout,
  singleViewLayout,
  addViewToGroup,
  removeViewFromLayout,
} from "./layout-tree"

const group = (id: string, viewIds: string[], activeViewId?: string): LayoutNode =>
  ({ type: "group", id, viewIds, activeViewId: activeViewId ?? viewIds[0] })

test("singleViewLayout makes a one-group layout with the view active", () => {
  const l = singleViewLayout("g1", "v1")
  expect(l).toEqual({ type: "group", id: "g1", viewIds: ["v1"], activeViewId: "v1" })
  expect(validateLayout(l)).toBeNull()
})

test("collectViewIds walks the whole tree in document order", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.5, 0.5],
    children: [group("g1", ["v1"]), group("g2", ["v2", "v3"])],
  }
  expect(collectViewIds(l)).toEqual(["v1", "v2", "v3"])
})

test("validateLayout rejects a duplicate view id", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.5, 0.5],
    children: [group("g1", ["v1"]), group("g2", ["v1"])],
  }
  expect(validateLayout(l)).toBe("duplicate view id: v1")
})

test("validateLayout rejects an empty group", () => {
  expect(validateLayout({ type: "group", id: "g1", viewIds: [] })).toBe("empty group: g1")
})

test("validateLayout rejects an activeViewId that is not in the group", () => {
  expect(validateLayout(group("g1", ["v1"], "v9"))).toBe("activeViewId not in group g1: v9")
})

test("validateLayout rejects a split whose sizes length differs from its children length", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [1],
    children: [group("g1", ["v1"]), group("g2", ["v2"])],
  }
  expect(validateLayout(l)).toBe("split sizes length 1 does not match children length 2")
})

test("validateLayout rejects a split with fewer than two children", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [1], children: [group("g1", ["v1"])],
  }
  expect(validateLayout(l)).toBe("split needs at least 2 children, got 1")
})

test("validateLayout rejects sizes that do not add up to 1", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.5, 0.2],
    children: [group("g1", ["v1"]), group("g2", ["v2"])],
  }
  expect(validateLayout(l)).toBe("split sizes must add up to 1, got 0.7")
})

test("validateLayout rejects a non-positive size", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0, 1],
    children: [group("g1", ["v1"]), group("g2", ["v2"])],
  }
  expect(validateLayout(l)).toBe("split sizes must all be greater than 0")
})

test("validateLayout accepts a valid nested tree", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.5, 0.5],
    children: [
      group("g1", ["v1"]),
      { type: "split", direction: "column", sizes: [0.6, 0.4], children: [group("g2", ["v2", "v3"], "v2"), group("g3", ["v4"])] },
    ],
  }
  expect(validateLayout(l)).toBeNull()
})

test("normalizeLayout drops an empty group and collapses the single-child split", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.5, 0.5],
    children: [group("g1", ["v1"]), { type: "group", id: "g2", viewIds: [] }],
  }
  expect(normalizeLayout(l)).toEqual(group("g1", ["v1"]))
})

test("normalizeLayout returns null when every group is empty", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.5, 0.5],
    children: [{ type: "group", id: "g1", viewIds: [] }, { type: "group", id: "g2", viewIds: [] }],
  }
  expect(normalizeLayout(l)).toBeNull()
})

test("normalizeLayout repairs sizes after a child is dropped", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.2, 0.3, 0.5],
    children: [group("g1", ["v1"]), { type: "group", id: "gx", viewIds: [] }, group("g3", ["v3"])],
  }
  expect(normalizeLayout(l)).toEqual({
    type: "split", direction: "row", sizes: [0.5, 0.5],
    children: [group("g1", ["v1"]), group("g3", ["v3"])],
  })
})

test("normalizeLayout repairs an activeViewId that left the group", () => {
  const l = { type: "group", id: "g1", viewIds: ["v1", "v2"], activeViewId: "v9" } as LayoutNode
  expect(normalizeLayout(l)).toEqual(group("g1", ["v1", "v2"], "v1"))
})

test("addViewToGroup appends the view and makes it active", () => {
  const l = singleViewLayout("g1", "v1")
  expect(addViewToGroup(l, "g1", "v2")).toEqual(group("g1", ["v1", "v2"], "v2"))
})

test("addViewToGroup leaves the tree alone when the group id is unknown", () => {
  const l = singleViewLayout("g1", "v1")
  expect(addViewToGroup(l, "nope", "v2")).toEqual(l)
})

test("removeViewFromLayout removes the view and normalizes", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.5, 0.5],
    children: [group("g1", ["v1"]), group("g2", ["v2"])],
  }
  expect(removeViewFromLayout(l, "v2")).toEqual(group("g1", ["v1"]))
})

test("removeViewFromLayout returns null when the last view goes", () => {
  expect(removeViewFromLayout(singleViewLayout("g1", "v1"), "v1")).toBeNull()
})

test("removeViewFromLayout picks a new active view when the active one goes", () => {
  const l = group("g1", ["v1", "v2"], "v1")
  expect(removeViewFromLayout(l, "v1")).toEqual(group("g1", ["v2"], "v2"))
})
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
bun test src/core/workspace/layout-tree.test.ts
```

Expected: FAIL — `Cannot find module './layout-tree'`.

- [ ] **Step 3: Write the implementation**

Create `src/core/workspace/layout-tree.ts`:

```ts
/**
 * The workspace layout tree: splits and groups, VS-Code style.
 *
 * Pure logic only — no bun:sqlite, no node:*, no I/O. Phase 2 ports this file to
 * Kotlin (apps/shared) with parity tests that lock the two implementations
 * together, exactly as PredictiveEcho.kt and TerminalKeys.kt are locked to their
 * TypeScript twins. Keep it portable.
 *
 * Spec: docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md §5.3
 */

export type LayoutGroup = {
  type: "group"
  id: string
  viewIds: string[]
  activeViewId?: string
}

export type LayoutSplit = {
  type: "split"
  direction: "row" | "column"
  /** Fractions, one per child, all > 0, adding up to 1. */
  sizes: number[]
  children: LayoutNode[]
}

export type LayoutNode = LayoutGroup | LayoutSplit

/** Float comparison tolerance for the sizes-add-up-to-1 rule. */
const SIZE_EPSILON = 1e-6

export function singleViewLayout(groupId: string, viewId: string): LayoutNode {
  return { type: "group", id: groupId, viewIds: [viewId], activeViewId: viewId }
}

/** Every view id in the tree, in document order. Duplicates are kept — validateLayout reports them. */
export function collectViewIds(node: LayoutNode): string[] {
  if (node.type === "group") return [...node.viewIds]
  return node.children.flatMap(collectViewIds)
}

/**
 * Returns null when the tree is valid, or a human-readable reason when it is not.
 * The HTTP layer turns a non-null return into a 400.
 */
export function validateLayout(node: LayoutNode): string | null {
  const seen = new Set<string>()
  const walk = (n: LayoutNode): string | null => {
    if (n.type === "group") {
      if (n.viewIds.length === 0) return `empty group: ${n.id}`
      for (const v of n.viewIds) {
        if (seen.has(v)) return `duplicate view id: ${v}`
        seen.add(v)
      }
      if (n.activeViewId !== undefined && !n.viewIds.includes(n.activeViewId)) {
        return `activeViewId not in group ${n.id}: ${n.activeViewId}`
      }
      return null
    }
    if (n.sizes.length !== n.children.length) {
      return `split sizes length ${n.sizes.length} does not match children length ${n.children.length}`
    }
    if (n.children.length < 2) {
      return `split needs at least 2 children, got ${n.children.length}`
    }
    if (n.sizes.some((s) => s <= 0)) return "split sizes must all be greater than 0"
    const total = n.sizes.reduce((a, b) => a + b, 0)
    if (Math.abs(total - 1) > SIZE_EPSILON) {
      // Trim the float noise so the message is readable (0.7, not 0.7000000000000001).
      return `split sizes must add up to 1, got ${Number(total.toFixed(6))}`
    }
    for (const c of n.children) {
      const err = walk(c)
      if (err) return err
    }
    return null
  }
  return walk(node)
}

/**
 * Repair a tree into a valid one, or return null when nothing is left.
 *
 * - an empty group is dropped
 * - a split with one surviving child becomes that child
 * - a split with no surviving child is dropped
 * - sizes are re-spread evenly whenever the child count changed
 * - an activeViewId that is not in its group falls back to the first view
 *
 * Run this after EVERY structural edit. A drag that leaves an empty group is the
 * normal case, not an error.
 */
export function normalizeLayout(node: LayoutNode): LayoutNode | null {
  if (node.type === "group") {
    if (node.viewIds.length === 0) return null
    const active = node.activeViewId !== undefined && node.viewIds.includes(node.activeViewId)
      ? node.activeViewId
      : node.viewIds[0]
    return { type: "group", id: node.id, viewIds: [...node.viewIds], activeViewId: active }
  }

  const kept: Array<{ child: LayoutNode; size: number }> = []
  node.children.forEach((child, i) => {
    const n = normalizeLayout(child)
    if (n !== null) kept.push({ child: n, size: node.sizes[i] ?? 0 })
  })

  if (kept.length === 0) return null
  if (kept.length === 1) return kept[0]!.child

  // Re-spread only when a child was dropped; an untouched split keeps the user's
  // drag positions. An even spread on every normalize would reset the splitter
  // every time an unrelated tab closed somewhere else in the tree.
  const sizes = kept.length === node.children.length
    ? kept.map((k) => k.size)
    : kept.map(() => 1 / kept.length)

  return { type: "split", direction: node.direction, sizes, children: kept.map((k) => k.child) }
}

/** Append a view to one group and make it the active tab. Unknown group id = no change. */
export function addViewToGroup(node: LayoutNode, groupId: string, viewId: string): LayoutNode {
  if (node.type === "group") {
    if (node.id !== groupId) return node
    if (node.viewIds.includes(viewId)) return { ...node, activeViewId: viewId }
    return { type: "group", id: node.id, viewIds: [...node.viewIds, viewId], activeViewId: viewId }
  }
  return {
    ...node,
    children: node.children.map((c) => addViewToGroup(c, groupId, viewId)),
  }
}

/** Remove a view wherever it is, then normalize. Returns null when the tree empties. */
export function removeViewFromLayout(node: LayoutNode, viewId: string): LayoutNode | null {
  const strip = (n: LayoutNode): LayoutNode => {
    if (n.type === "group") {
      return { ...n, viewIds: n.viewIds.filter((v) => v !== viewId) }
    }
    return { ...n, children: n.children.map(strip) }
  }
  return normalizeLayout(strip(node))
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
bun test src/core/workspace/layout-tree.test.ts
```

Expected: PASS, 18 tests.

- [ ] **Step 5: Commit**

```bash
git add src/core/workspace/layout-tree.ts src/core/workspace/layout-tree.test.ts
git commit -m "feat(workspace): add the layout tree module

Pure split/group tree logic for the workspaces-and-views spec: validate,
normalize, add a view, remove a view. No I/O, so Phase 2 can port it to
Kotlin with parity tests.

Spec: docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md"
```

---

## Task 2: The record types

**Files:**
- Create: `src/core/workspace/types.ts`
- Test: covered by Task 3's store tests — a types file with only mappers is tested through the store that uses them.

- [ ] **Step 1: Write the types**

Create `src/core/workspace/types.ts`:

```ts
import type { LayoutNode } from "./layout-tree"

export type WorkspaceStatus = "active" | "archived"

export type ViewKind = "chat" | "terminal" | "editor" | "display"

/** A chat view names exactly one agent session. */
export type ChatViewState = { sessionId: string }

/**
 * A terminal view is either workspace-scoped (a plain shell in the workspace
 * work directory, no agent needed) or session-scoped (the agent's own pane,
 * terminalId always "agent").
 */
export type TerminalViewState =
  | { scope: "workspace"; terminalId: string }
  | { scope: "session"; sessionId: string; terminalId: "agent" }

/** Paths are relative to the workspace work directory. */
export type EditorViewState = {
  path?: string
  mode: "tree" | "file" | "diff"
  line?: number
  diffBase?: string
}

/** The display stream stays host-global; the view only points at it. */
export type DisplayViewState = { displayId: string }

export type ViewState = ChatViewState | TerminalViewState | EditorViewState | DisplayViewState

export type WorkspaceRecord = {
  id: string
  name: string
  status: WorkspaceStatus
  workdir: string
  repo_root?: string
  base_branch?: string
  branch?: string
  layout: LayoutNode
  active_view_id?: string
  /** The session whose name drives this workspace's name (spec §9.5). */
  primary_session_id?: string
  /** True once the user renames the workspace by hand; stops name propagation. */
  name_locked: boolean
  sort_order: number
  created_at: string
  archived_at?: string
}

export type WorkspaceRow = {
  id: string
  name: string
  status: string
  workdir: string
  repo_root: string | null
  base_branch: string | null
  branch: string | null
  layout: string
  active_view_id: string | null
  primary_session_id: string | null
  name_locked: number
  sort_order: number
  created_at: string
  archived_at: string | null
}

export type ViewRecord = {
  id: string
  workspace_id: string
  kind: ViewKind
  title?: string
  state: ViewState
  created_at: string
}

export type ViewRow = {
  id: string
  workspace_id: string
  kind: string
  title: string | null
  state: string
  created_at: string
}

export function rowToWorkspace(row: WorkspaceRow): WorkspaceRecord {
  return {
    id: row.id,
    name: row.name,
    status: row.status as WorkspaceStatus,
    workdir: row.workdir,
    repo_root: row.repo_root ?? undefined,
    base_branch: row.base_branch ?? undefined,
    branch: row.branch ?? undefined,
    layout: JSON.parse(row.layout) as LayoutNode,
    active_view_id: row.active_view_id ?? undefined,
    primary_session_id: row.primary_session_id ?? undefined,
    name_locked: row.name_locked === 1,
    sort_order: row.sort_order,
    created_at: row.created_at,
    archived_at: row.archived_at ?? undefined,
  }
}

export function rowToView(row: ViewRow): ViewRecord {
  return {
    id: row.id,
    workspace_id: row.workspace_id,
    kind: row.kind as ViewKind,
    title: row.title ?? undefined,
    state: JSON.parse(row.state) as ViewState,
    created_at: row.created_at,
  }
}
```

- [ ] **Step 2: Typecheck**

```bash
bun run typecheck
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add src/core/workspace/types.ts
git commit -m "feat(workspace): add the workspace and view record types

Mirrors the shape of session-manager/types.ts: a Record for callers, a Row for
SQLite, and a mapper between them."
```

---

## Task 3: Migration 027

**Files:**
- Create: `src/core/storage/migrations/027_workspaces.sql`
- Modify: `src/core/storage/migrations/index.ts`
- Test: `src/core/storage/migrations/027_workspaces.test.ts`

- [ ] **Step 1: Write the failing test**

Create `src/core/storage/migrations/027_workspaces.test.ts`:

```ts
import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"

/** Apply every migration strictly before 27, so we can seed pre-027 rows. */
function dbAt026() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS.filter((m) => m.version < 27))
  return db
}

function seedSession(db: ReturnType<typeof openDb>, o: {
  id: string; name: string; workdir: string
  status?: string; repo_root?: string; base_branch?: string; session_branch?: string; sort_order?: number
}) {
  db.run(
    `INSERT INTO sessions (id, name, status, agent, workdir, created_at, repo_root, base_branch, session_branch, sort_order)
     VALUES (?, ?, ?, 'claude', ?, '2026-01-01T00:00:00.000Z', ?, ?, ?, ?)`,
    [o.id, o.name, o.status ?? "active", o.workdir, o.repo_root ?? null, o.base_branch ?? null, o.session_branch ?? null, o.sort_order ?? 0],
  )
}

test("027 gives every session exactly one workspace, carrying its paths", () => {
  const db = dbAt026()
  seedSession(db, {
    id: "s1", name: "Fix Session Renaming", workdir: "/home/u/.mux/worktrees/abc",
    repo_root: "/home/u/projects/app", base_branch: "main", session_branch: "mux/fix", sort_order: 3,
  })
  runMigrations(db, MIGRATIONS)

  const rows = db.query("SELECT * FROM workspaces").all() as any[]
  expect(rows).toHaveLength(1)
  expect(rows[0]).toMatchObject({
    name: "Fix Session Renaming",
    status: "active",
    workdir: "/home/u/.mux/worktrees/abc",
    repo_root: "/home/u/projects/app",
    base_branch: "main",
    branch: "mux/fix",
    primary_session_id: "s1",
    name_locked: 0,
    sort_order: 3,
  })
})

test("027 links the session back to its workspace", () => {
  const db = dbAt026()
  seedSession(db, { id: "s1", name: "a", workdir: "/w" })
  runMigrations(db, MIGRATIONS)

  const ws = db.query("SELECT id FROM workspaces").get() as { id: string }
  const s = db.query("SELECT workspace_id FROM sessions WHERE id = 's1'").get() as { workspace_id: string }
  expect(s.workspace_id).toBe(ws.id)
})

test("027 makes one chat view per workspace pointing at the session", () => {
  const db = dbAt026()
  seedSession(db, { id: "s1", name: "a", workdir: "/w" })
  runMigrations(db, MIGRATIONS)

  const views = db.query("SELECT * FROM views").all() as any[]
  expect(views).toHaveLength(1)
  expect(views[0].kind).toBe("chat")
  expect(JSON.parse(views[0].state)).toEqual({ sessionId: "s1" })
})

test("027 writes a valid one-group layout naming that view", () => {
  const db = dbAt026()
  seedSession(db, { id: "s1", name: "a", workdir: "/w" })
  runMigrations(db, MIGRATIONS)

  const ws = db.query("SELECT layout, active_view_id FROM workspaces").get() as any
  const view = db.query("SELECT id FROM views").get() as { id: string }
  expect(JSON.parse(ws.layout)).toEqual({
    type: "group",
    id: expect.any(String),
    viewIds: [view.id],
    activeViewId: view.id,
  })
  expect(ws.active_view_id).toBe(view.id)
})

test("027 marks an archived session's workspace archived", () => {
  const db = dbAt026()
  seedSession(db, { id: "s1", name: "a", workdir: "/w", status: "archived" })
  runMigrations(db, MIGRATIONS)

  const ws = db.query("SELECT status FROM workspaces").get() as { status: string }
  expect(ws.status).toBe("archived")
})

test("027 gives each session its own workspace, never a shared one", () => {
  const db = dbAt026()
  seedSession(db, { id: "s1", name: "a", workdir: "/same" })
  seedSession(db, { id: "s2", name: "b", workdir: "/same" })
  runMigrations(db, MIGRATIONS)

  const ids = (db.query("SELECT id FROM workspaces").all() as any[]).map((r) => r.id)
  expect(new Set(ids).size).toBe(2)
})

test("027 gives every workspace a distinct id and every view a distinct id", () => {
  const db = dbAt026()
  for (let i = 0; i < 25; i++) seedSession(db, { id: `s${i}`, name: `n${i}`, workdir: "/w" })
  runMigrations(db, MIGRATIONS)

  const ws = (db.query("SELECT id FROM workspaces").all() as any[]).map((r) => r.id)
  const vs = (db.query("SELECT id FROM views").all() as any[]).map((r) => r.id)
  expect(new Set(ws).size).toBe(25)
  expect(new Set(vs).size).toBe(25)
})

test("027 on an empty database creates the tables and no rows", () => {
  const db = dbAt026()
  runMigrations(db, MIGRATIONS)
  expect(db.query("SELECT count(*) c FROM workspaces").get()).toEqual({ c: 0 })
  expect(db.query("SELECT count(*) c FROM views").get()).toEqual({ c: 0 })
})

test("removing a workspace cascades to its views", () => {
  const db = dbAt026()
  seedSession(db, { id: "s1", name: "a", workdir: "/w" })
  runMigrations(db, MIGRATIONS)
  db.exec("PRAGMA foreign_keys = ON")

  const ws = db.query("SELECT id FROM workspaces").get() as { id: string }
  db.run("DELETE FROM workspaces WHERE id = ?", [ws.id])
  expect(db.query("SELECT count(*) c FROM views").get()).toEqual({ c: 0 })
})
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
bun test src/core/storage/migrations/027_workspaces.test.ts
```

Expected: FAIL — `no such table: workspaces`.

- [ ] **Step 3: Write the migration**

Create `src/core/storage/migrations/027_workspaces.sql`:

```sql
-- src/core/storage/migrations/027_workspaces.sql
--
-- Workspaces and views. A workspace is the container the user arranges: it owns
-- a work directory, a source directory, a layout tree, and a set of views. A
-- view is a chat, a terminal, an editor, or a display.
--
-- The backfill is deliberately ONE workspace per existing session (never grouped
-- by path). A grouped move would put two agents in one container without the
-- user asking for it. One-to-one is lossless: after this migration every session
-- behaves exactly as it did before.
--
-- Spec: docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md §6

CREATE TABLE workspaces (
  id                 TEXT PRIMARY KEY,
  name               TEXT NOT NULL,
  status             TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','archived')),
  workdir            TEXT NOT NULL,
  repo_root          TEXT,
  base_branch        TEXT,
  branch             TEXT,
  layout             TEXT NOT NULL,          -- JSON LayoutNode
  active_view_id     TEXT,
  primary_session_id TEXT REFERENCES sessions(id),
  name_locked        INTEGER NOT NULL DEFAULT 0,
  sort_order         INTEGER NOT NULL DEFAULT 0,
  created_at         TEXT NOT NULL,
  archived_at        TEXT
);

CREATE TABLE views (
  id           TEXT PRIMARY KEY,
  workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  kind         TEXT NOT NULL CHECK(kind IN ('chat','terminal','editor','display')),
  title        TEXT,
  state        TEXT NOT NULL,                -- JSON ViewState
  created_at   TEXT NOT NULL
);
CREATE INDEX views_workspace ON views(workspace_id);

ALTER TABLE sessions ADD COLUMN workspace_id TEXT REFERENCES workspaces(id);
CREATE INDEX sessions_workspace ON sessions(workspace_id);

-- Backfill step 1: one workspace per session.
--
-- SQLite has no uuid() function. hex(randomblob(...)) is the standard way to
-- build a v4-shaped id, and it matches the dashed format randomUUID() writes
-- everywhere else in this schema.
INSERT INTO workspaces (
  id, name, status, workdir, repo_root, base_branch, branch,
  layout, primary_session_id, name_locked, sort_order, created_at, archived_at
)
SELECT
  lower(
    substr(hex(randomblob(4)), 1, 8) || '-' ||
    substr(hex(randomblob(2)), 1, 4) || '-4' ||
    substr(hex(randomblob(2)), 2, 3) || '-' ||
    substr('89ab', abs(random()) % 4 + 1, 1) ||
    substr(hex(randomblob(2)), 2, 3) || '-' ||
    substr(hex(randomblob(6)), 1, 12)
  ),
  s.name,
  CASE WHEN s.status = 'archived' THEN 'archived' ELSE 'active' END,
  s.workdir,
  s.repo_root,
  s.base_branch,
  s.session_branch,
  '{}',                 -- placeholder; step 4 writes the real tree
  s.id,
  0,
  s.sort_order,
  s.created_at,
  s.killed_at
FROM sessions s;

-- Backfill step 2: point each session at its workspace.
UPDATE sessions
   SET workspace_id = (SELECT w.id FROM workspaces w WHERE w.primary_session_id = sessions.id);

-- Backfill step 3: one chat view per workspace.
INSERT INTO views (id, workspace_id, kind, title, state, created_at)
SELECT
  lower(
    substr(hex(randomblob(4)), 1, 8) || '-' ||
    substr(hex(randomblob(2)), 1, 4) || '-4' ||
    substr(hex(randomblob(2)), 2, 3) || '-' ||
    substr('89ab', abs(random()) % 4 + 1, 1) ||
    substr(hex(randomblob(2)), 2, 3) || '-' ||
    substr(hex(randomblob(6)), 1, 12)
  ),
  w.id,
  'chat',
  NULL,
  json_object('sessionId', w.primary_session_id),
  w.created_at
FROM workspaces w;

-- Backfill step 4: a one-group layout naming that view, and the active view id.
UPDATE workspaces
   SET layout = (
         SELECT json_object(
                  'type', 'group',
                  'id', lower(
                    substr(hex(randomblob(4)), 1, 8) || '-' ||
                    substr(hex(randomblob(2)), 1, 4) || '-4' ||
                    substr(hex(randomblob(2)), 2, 3) || '-' ||
                    substr('89ab', abs(random()) % 4 + 1, 1) ||
                    substr(hex(randomblob(2)), 2, 3) || '-' ||
                    substr(hex(randomblob(6)), 1, 12)
                  ),
                  'viewIds', json_array(v.id),
                  'activeViewId', v.id
                )
           FROM views v WHERE v.workspace_id = workspaces.id
       ),
       active_view_id = (SELECT v.id FROM views v WHERE v.workspace_id = workspaces.id);
```

- [ ] **Step 4: Add the migration to the manifest**

Open `src/core/storage/migrations/index.ts`. Add the import after the `m026` line:

```ts
import m027 from "./027_workspaces.sql" with { type: "text" }
```

Add the manifest row after the `026_user_status` entry:

```ts
  { version: 27, name: "027_workspaces", sql: m027 },
```

⚠ Both edits are required. The file's own header says so, and a `migrations-embedded` test fails when the manifest drifts from disk. The manifest is what ships inside the bundled broker — the directory is not readable from the bundle.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
bun test src/core/storage/migrations/
```

Expected: PASS — the 9 new tests plus the existing migration tests, including `migrations-embedded`.

- [ ] **Step 6: Run the whole broker suite**

A schema change can break a test anywhere.

```bash
bun test
```

Expected: PASS. If `schema-stamp.test.ts` fails, it is asserting a schema fingerprint that this migration legitimately changed — read the failure and update the expected stamp, and say so in the commit.

- [ ] **Step 7: Commit**

```bash
git add src/core/storage/migrations/027_workspaces.sql src/core/storage/migrations/index.ts src/core/storage/migrations/027_workspaces.test.ts
git commit -m "feat(storage): migration 027 — workspaces and views

Adds the workspaces and views tables and sessions.workspace_id, then backfills
one workspace + one chat view per existing session. One-to-one, never grouped
by path: a grouped backfill would put two agents in one container without the
user asking.

No behaviour change — nothing reads these tables yet.

Spec: docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md"
```

---

## Task 4: The workspace store

**Files:**
- Create: `src/core/workspace/store.ts`
- Test: `src/core/workspace/store.test.ts`

- [ ] **Step 1: Write the failing tests**

Create `src/core/workspace/store.test.ts`:

```ts
import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { WorkspaceStore } from "./store"

function store() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return { db, ws: new WorkspaceStore(db) }
}

test("create makes a workspace with an empty-safe layout and no views", () => {
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w", repo_root: "/repo", base_branch: "main" })
  expect(w).toMatchObject({ name: "app", workdir: "/w", repo_root: "/repo", base_branch: "main", status: "active", name_locked: false })
  expect(ws.listViews(w.id)).toEqual([])
  expect(ws.getById(w.id)).toMatchObject({ id: w.id, name: "app" })
})

test("addView appends the view to the layout and makes it active", () => {
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w" })
  const v = ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })

  const after = ws.getById(w.id)!
  expect(after.active_view_id).toBe(v.id)
  expect(after.layout).toEqual({ type: "group", id: expect.any(String), viewIds: [v.id], activeViewId: v.id })
  expect(ws.listViews(w.id)).toHaveLength(1)
})

test("addView twice puts both views in the same group", () => {
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w" })
  const v1 = ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })
  const v2 = ws.addView(w.id, { kind: "terminal", state: { scope: "workspace", terminalId: "main" } })

  const after = ws.getById(w.id)!
  expect(after.layout).toEqual({ type: "group", id: expect.any(String), viewIds: [v1.id, v2.id], activeViewId: v2.id })
})

test("removeView drops the view from the table and from the layout", () => {
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w" })
  const v1 = ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })
  const v2 = ws.addView(w.id, { kind: "editor", state: { mode: "tree" } })

  ws.removeView(v2.id)
  const after = ws.getById(w.id)!
  expect(ws.listViews(w.id).map((v) => v.id)).toEqual([v1.id])
  expect(after.layout).toEqual({ type: "group", id: expect.any(String), viewIds: [v1.id], activeViewId: v1.id })
  expect(after.active_view_id).toBe(v1.id)
})

test("removing the last view leaves an empty layout and no active view", () => {
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w" })
  const v = ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })
  ws.removeView(v.id)

  const after = ws.getById(w.id)!
  expect(ws.listViews(w.id)).toEqual([])
  expect(after.active_view_id).toBeUndefined()
  expect(after.layout).toEqual({ type: "group", id: expect.any(String), viewIds: [], activeViewId: undefined })
})

test("setLayout rejects an invalid tree and keeps the old one", () => {
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w" })
  const v = ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })
  const before = ws.getById(w.id)!.layout

  expect(() => ws.setLayout(w.id, { type: "group", id: "g", viewIds: [] })).toThrow("empty group: g")
  expect(ws.getById(w.id)!.layout).toEqual(before)
  expect(v.id).toBeTruthy()
})

test("setLayout rejects a tree naming a view that is not in this workspace", () => {
  const { ws } = store()
  const w = ws.create({ name: "app", workdir: "/w" })
  ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })

  expect(() => ws.setLayout(w.id, { type: "group", id: "g", viewIds: ["ghost"], activeViewId: "ghost" }))
    .toThrow("layout names a view that is not in this workspace: ghost")
})

test("moveView changes the owner and both layouts", () => {
  const { ws } = store()
  const a = ws.create({ name: "a", workdir: "/a" })
  const b = ws.create({ name: "b", workdir: "/b" })
  const v = ws.addView(a.id, { kind: "chat", state: { sessionId: "s1" } })

  ws.moveView(v.id, b.id)

  expect(ws.listViews(a.id)).toEqual([])
  expect(ws.listViews(b.id).map((x) => x.id)).toEqual([v.id])
  expect(ws.getById(a.id)!.layout).toEqual({ type: "group", id: expect.any(String), viewIds: [], activeViewId: undefined })
  expect(ws.getById(b.id)!.layout).toMatchObject({ viewIds: [v.id], activeViewId: v.id })
})

test("rename by the agent leaves name_locked false; rename by the user sets it", () => {
  const { ws } = store()
  const w = ws.create({ name: "old", workdir: "/w" })

  ws.rename(w.id, "agent name", { byUser: false })
  expect(ws.getById(w.id)).toMatchObject({ name: "agent name", name_locked: false })

  ws.rename(w.id, "user name", { byUser: true })
  expect(ws.getById(w.id)).toMatchObject({ name: "user name", name_locked: true })
})

test("archive sets the status and the timestamp and hides it from list()", () => {
  const { ws } = store()
  const w = ws.create({ name: "a", workdir: "/w" })
  ws.archive(w.id)

  expect(ws.list()).toEqual([])
  expect(ws.list({ includeArchived: true }).map((x) => x.id)).toEqual([w.id])
  const got = ws.getById(w.id)!
  expect(got.status).toBe("archived")
  expect(got.archived_at).toBeTruthy()
})

test("list returns active workspaces in sort_order then id", () => {
  const { ws } = store()
  const a = ws.create({ name: "a", workdir: "/w", sort_order: 2 })
  const b = ws.create({ name: "b", workdir: "/w", sort_order: 1 })
  expect(ws.list().map((x) => x.id)).toEqual([b.id, a.id])
})

test("reorder assigns sort_order by position", () => {
  const { ws } = store()
  const a = ws.create({ name: "a", workdir: "/w" })
  const b = ws.create({ name: "b", workdir: "/w" })
  const c = ws.create({ name: "c", workdir: "/w" })

  ws.reorder([c.id, a.id, b.id])
  expect(ws.list().map((x) => x.id)).toEqual([c.id, a.id, b.id])
})

test("chatSessionIds returns the session of every chat view, ignoring other kinds", () => {
  const { ws } = store()
  const w = ws.create({ name: "a", workdir: "/w" })
  ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })
  ws.addView(w.id, { kind: "terminal", state: { scope: "workspace", terminalId: "t" } })
  ws.addView(w.id, { kind: "chat", state: { sessionId: "s2" } })

  expect(ws.chatSessionIds(w.id)).toEqual(["s1", "s2"])
})

test("findByPrimarySession finds the workspace a session names", () => {
  const { ws } = store()
  const w = ws.create({ name: "a", workdir: "/w", primary_session_id: "s1" })
  expect(ws.findByPrimarySession("s1")?.id).toBe(w.id)
  expect(ws.findByPrimarySession("nope")).toBeUndefined()
})
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
bun test src/core/workspace/store.test.ts
```

Expected: FAIL — `Cannot find module './store'`.

- [ ] **Step 3: Write the store**

Create `src/core/workspace/store.ts`:

```ts
import { randomUUID } from "crypto"
import type { Database as Db } from "bun:sqlite"
import {
  type WorkspaceRecord, type WorkspaceRow, type ViewRecord, type ViewRow,
  type ViewKind, type ViewState, type ChatViewState,
  rowToWorkspace, rowToView,
} from "./types"
import {
  type LayoutNode,
  validateLayout, normalizeLayout, addViewToGroup, removeViewFromLayout, collectViewIds,
} from "./layout-tree"

export type CreateWorkspaceInput = {
  id?: string
  name: string
  workdir: string
  repo_root?: string
  base_branch?: string
  branch?: string
  primary_session_id?: string
  sort_order?: number
}

export type AddViewInput = {
  id?: string
  kind: ViewKind
  state: ViewState
  title?: string
  /** Which group to append to. Defaults to the first group in the layout. */
  groupId?: string
}

/**
 * All SQL for the workspaces and views tables.
 *
 * There is no in-memory cache here, unlike SessionStore. A workspace is read on
 * a route call, not on every agent frame, so the cache would buy nothing and
 * would be one more thing to keep coherent.
 */
export class WorkspaceStore {
  constructor(private readonly db: Db) {}

  create(input: CreateWorkspaceInput): WorkspaceRecord {
    const id = input.id ?? randomUUID()
    const created_at = new Date().toISOString()
    // An empty group, not an empty object: a workspace with no views yet is
    // still a valid tree shape, and addView can append straight into it.
    const layout: LayoutNode = { type: "group", id: randomUUID(), viewIds: [] }
    this.db.run(
      `INSERT INTO workspaces (id, name, status, workdir, repo_root, base_branch, branch,
                               layout, active_view_id, primary_session_id, name_locked, sort_order, created_at)
       VALUES (?, ?, 'active', ?, ?, ?, ?, ?, NULL, ?, 0, ?, ?)`,
      [id, input.name, input.workdir, input.repo_root ?? null, input.base_branch ?? null,
       input.branch ?? null, JSON.stringify(layout), input.primary_session_id ?? null,
       input.sort_order ?? 0, created_at],
    )
    return this.getById(id)!
  }

  getById(id: string): WorkspaceRecord | undefined {
    const row = this.db.query("SELECT * FROM workspaces WHERE id = ?").get(id) as WorkspaceRow | null
    return row ? rowToWorkspace(row) : undefined
  }

  findByPrimarySession(sessionId: string): WorkspaceRecord | undefined {
    const row = this.db
      .query("SELECT * FROM workspaces WHERE primary_session_id = ?")
      .get(sessionId) as WorkspaceRow | null
    return row ? rowToWorkspace(row) : undefined
  }

  list(opts?: { includeArchived?: boolean }): WorkspaceRecord[] {
    const sql = opts?.includeArchived
      ? "SELECT * FROM workspaces ORDER BY sort_order ASC, id ASC"
      : "SELECT * FROM workspaces WHERE status = 'active' ORDER BY sort_order ASC, id ASC"
    return (this.db.query(sql).all() as WorkspaceRow[]).map(rowToWorkspace)
  }

  rename(id: string, name: string, opts?: { byUser?: boolean }): void {
    if (opts?.byUser) {
      this.db.run("UPDATE workspaces SET name = ?, name_locked = 1 WHERE id = ?", [name, id])
    } else {
      this.db.run("UPDATE workspaces SET name = ? WHERE id = ?", [name, id])
    }
  }

  setPrimarySession(id: string, sessionId: string | null): void {
    this.db.run("UPDATE workspaces SET primary_session_id = ? WHERE id = ?", [sessionId, id])
  }

  archive(id: string): void {
    this.db.run(
      "UPDATE workspaces SET status = 'archived', archived_at = ? WHERE id = ?",
      [new Date().toISOString(), id],
    )
  }

  reorder(orderedIds: string[]): void {
    const tx = this.db.transaction((ids: string[]) => {
      ids.forEach((id, i) => this.db.run("UPDATE workspaces SET sort_order = ? WHERE id = ?", [i, id]))
    })
    tx(orderedIds)
  }

  /**
   * Replace the layout tree. Throws when the tree is invalid or names a view
   * that does not belong to this workspace — the HTTP layer turns that into a 400.
   * The stored layout is never written from an unvalidated input.
   */
  setLayout(id: string, layout: LayoutNode): void {
    const err = validateLayout(layout)
    if (err) throw new Error(err)
    const owned = new Set(this.listViews(id).map((v) => v.id))
    for (const viewId of collectViewIds(layout)) {
      if (!owned.has(viewId)) {
        throw new Error(`layout names a view that is not in this workspace: ${viewId}`)
      }
    }
    this.db.run("UPDATE workspaces SET layout = ? WHERE id = ?", [JSON.stringify(layout), id])
  }

  setActiveView(id: string, viewId: string | null): void {
    this.db.run("UPDATE workspaces SET active_view_id = ? WHERE id = ?", [viewId, id])
  }

  // ── views ────────────────────────────────────────────────────────────────

  listViews(workspaceId: string): ViewRecord[] {
    return (this.db
      .query("SELECT * FROM views WHERE workspace_id = ? ORDER BY created_at ASC, id ASC")
      .all(workspaceId) as ViewRow[]).map(rowToView)
  }

  getView(viewId: string): ViewRecord | undefined {
    const row = this.db.query("SELECT * FROM views WHERE id = ?").get(viewId) as ViewRow | null
    return row ? rowToView(row) : undefined
  }

  /** The session id of every chat view, in view order. Used by archive and by the sidebar. */
  chatSessionIds(workspaceId: string): string[] {
    return this.listViews(workspaceId)
      .filter((v) => v.kind === "chat")
      .map((v) => (v.state as ChatViewState).sessionId)
  }

  addView(workspaceId: string, input: AddViewInput): ViewRecord {
    const id = input.id ?? randomUUID()
    const created_at = new Date().toISOString()
    this.db.run(
      "INSERT INTO views (id, workspace_id, kind, title, state, created_at) VALUES (?, ?, ?, ?, ?, ?)",
      [id, workspaceId, input.kind, input.title ?? null, JSON.stringify(input.state), created_at],
    )

    const ws = this.getById(workspaceId)
    if (ws) {
      const groupId = input.groupId ?? firstGroupId(ws.layout)
      const next = groupId ? addViewToGroup(ws.layout, groupId, id) : ws.layout
      this.db.run("UPDATE workspaces SET layout = ?, active_view_id = ? WHERE id = ?",
        [JSON.stringify(next), id, workspaceId])
    }
    return this.getView(id)!
  }

  setViewState(viewId: string, state: ViewState): void {
    this.db.run("UPDATE views SET state = ? WHERE id = ?", [JSON.stringify(state), viewId])
  }

  setViewTitle(viewId: string, title: string | null): void {
    this.db.run("UPDATE views SET title = ? WHERE id = ?", [title, viewId])
  }

  /**
   * Delete the view row and take it out of its workspace's layout.
   *
   * This is storage only. It does NOT archive a session, kill a terminal, or
   * stop a display — spec §9.3 puts those side effects in the route layer,
   * where the session/terminal/display managers live.
   */
  removeView(viewId: string): void {
    const view = this.getView(viewId)
    if (!view) return
    this.db.run("DELETE FROM views WHERE id = ?", [viewId])
    this.pruneLayout(view.workspace_id, viewId)
  }

  moveView(viewId: string, toWorkspaceId: string, toGroupId?: string): void {
    const view = this.getView(viewId)
    if (!view) return
    const from = view.workspace_id
    if (from === toWorkspaceId) return

    this.db.run("UPDATE views SET workspace_id = ? WHERE id = ?", [toWorkspaceId, viewId])
    this.pruneLayout(from, viewId)

    const target = this.getById(toWorkspaceId)
    if (target) {
      const groupId = toGroupId ?? firstGroupId(target.layout)
      const next = groupId ? addViewToGroup(target.layout, groupId, viewId) : target.layout
      this.db.run("UPDATE workspaces SET layout = ?, active_view_id = ? WHERE id = ?",
        [JSON.stringify(next), viewId, toWorkspaceId])
    }
  }

  /**
   * Take one view id out of a workspace's layout and repair the tree.
   * An emptied tree becomes a single empty group rather than null — a workspace
   * always has a layout column, and the next addView needs a group to target.
   */
  private pruneLayout(workspaceId: string, viewId: string): void {
    const ws = this.getById(workspaceId)
    if (!ws) return
    const pruned = removeViewFromLayout(ws.layout, viewId)
    const next: LayoutNode = pruned ?? { type: "group", id: randomUUID(), viewIds: [] }
    const remaining = collectViewIds(next)
    const active = ws.active_view_id && remaining.includes(ws.active_view_id)
      ? ws.active_view_id
      : (remaining[0] ?? null)
    this.db.run("UPDATE workspaces SET layout = ?, active_view_id = ? WHERE id = ?",
      [JSON.stringify(next), active, workspaceId])
  }
}

/** The id of the first group found in document order, or undefined for an empty tree. */
function firstGroupId(node: LayoutNode): string | undefined {
  if (node.type === "group") return node.id
  for (const child of node.children) {
    const id = firstGroupId(child)
    if (id) return id
  }
  return undefined
}

// Re-exported so callers do not have to import from two modules to repair a tree.
export { normalizeLayout, validateLayout }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
bun test src/core/workspace/store.test.ts
```

Expected: PASS, 14 tests.

- [ ] **Step 5: Commit**

```bash
git add src/core/workspace/store.ts src/core/workspace/store.test.ts
git commit -m "feat(workspace): add WorkspaceStore

All SQL for the workspaces and views tables, with the layout tree kept
consistent on every view add, remove, and move. Storage only: removeView does
not archive a session or kill a terminal — those side effects belong to the
route layer (spec 9.3)."
```

---

## Task 5: Name propagation

**Files:**
- Create: `src/core/workspace/name.ts`
- Test: `src/core/workspace/name.test.ts`

Spec §9.5. The workspace name follows its primary session. This task is the rule; Phase 1b wires it into the rename route.

- [ ] **Step 1: Write the failing tests**

Create `src/core/workspace/name.test.ts`:

```ts
import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { WorkspaceStore } from "./store"
import { propagateSessionRename, repointPrimarySession } from "./name"

function store() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return new WorkspaceStore(db)
}

test("renaming the primary session renames the workspace and reports the id", () => {
  const ws = store()
  const w = ws.create({ name: "old", workdir: "/w", primary_session_id: "s1" })

  expect(propagateSessionRename(ws, "s1", "New Title")).toBe(w.id)
  expect(ws.getById(w.id)!.name).toBe("New Title")
})

test("renaming a session that is not primary changes nothing", () => {
  const ws = store()
  const w = ws.create({ name: "old", workdir: "/w", primary_session_id: "s1" })

  expect(propagateSessionRename(ws, "s2", "New Title")).toBeUndefined()
  expect(ws.getById(w.id)!.name).toBe("old")
})

test("a locked name is never propagated", () => {
  const ws = store()
  const w = ws.create({ name: "old", workdir: "/w", primary_session_id: "s1" })
  ws.rename(w.id, "user chose this", { byUser: true })

  expect(propagateSessionRename(ws, "s1", "agent tried")).toBeUndefined()
  expect(ws.getById(w.id)!.name).toBe("user chose this")
})

test("an equal name writes nothing — this is the loop guard", () => {
  const ws = store()
  const w = ws.create({ name: "same", workdir: "/w", primary_session_id: "s1" })

  expect(propagateSessionRename(ws, "s1", "same")).toBeUndefined()
})

test("propagation does not set name_locked", () => {
  const ws = store()
  const w = ws.create({ name: "old", workdir: "/w", primary_session_id: "s1" })
  propagateSessionRename(ws, "s1", "agent name")

  expect(ws.getById(w.id)!.name_locked).toBe(false)
})

test("repointPrimarySession moves the pointer to the oldest remaining chat session", () => {
  const ws = store()
  const w = ws.create({ name: "n", workdir: "/w", primary_session_id: "s1" })
  ws.addView(w.id, { kind: "chat", state: { sessionId: "s2" } })
  ws.addView(w.id, { kind: "chat", state: { sessionId: "s3" } })

  expect(repointPrimarySession(ws, w.id)).toBe("s2")
  expect(ws.getById(w.id)!.primary_session_id).toBe("s2")
})

test("repointPrimarySession does not change the name", () => {
  const ws = store()
  const w = ws.create({ name: "keep me", workdir: "/w", primary_session_id: "s1" })
  ws.addView(w.id, { kind: "chat", state: { sessionId: "s2" } })

  repointPrimarySession(ws, w.id)
  expect(ws.getById(w.id)!.name).toBe("keep me")
})

test("repointPrimarySession clears the pointer when no chat view is left", () => {
  const ws = store()
  const w = ws.create({ name: "n", workdir: "/w", primary_session_id: "s1" })
  ws.addView(w.id, { kind: "terminal", state: { scope: "workspace", terminalId: "t" } })

  expect(repointPrimarySession(ws, w.id)).toBeUndefined()
  expect(ws.getById(w.id)!.primary_session_id).toBeUndefined()
})
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
bun test src/core/workspace/name.test.ts
```

Expected: FAIL — `Cannot find module './name'`.

- [ ] **Step 3: Write the implementation**

Create `src/core/workspace/name.ts`:

```ts
import type { WorkspaceStore } from "./store"

/**
 * Spec §9.5 — the workspace name follows its primary session.
 *
 * Call this after a session rename, whoever caused it: the agent's own
 * rename_session shim tool or the user. Returns the workspace id when a rename
 * happened, so the caller knows to broadcast workspace_changed. Returns
 * undefined when nothing was written.
 *
 * A rename that changes nothing MUST NOT write. The rename route broadcasts on
 * every write, and a write-then-broadcast on an unchanged name is how a rename
 * loop starts.
 */
export function propagateSessionRename(
  store: WorkspaceStore,
  sessionId: string,
  newName: string,
): string | undefined {
  const ws = store.findByPrimarySession(sessionId)
  if (!ws) return undefined
  if (ws.name_locked) return undefined
  if (ws.name === newName) return undefined
  store.rename(ws.id, newName, { byUser: false })
  return ws.id
}

/**
 * Spec §9.5 rule 6 — when the primary session goes, point at the oldest chat
 * session that is left. The NAME does not change here; only the next rename of
 * the new primary session moves it.
 *
 * Returns the new primary session id, or undefined when no chat view remains.
 */
export function repointPrimarySession(
  store: WorkspaceStore,
  workspaceId: string,
): string | undefined {
  const ws = store.getById(workspaceId)
  if (!ws) return undefined
  // listViews orders by created_at, so the first chat view is the oldest one.
  const next = store.chatSessionIds(workspaceId).find((id) => id !== ws.primary_session_id)
  store.setPrimarySession(workspaceId, next ?? null)
  return next
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
bun test src/core/workspace/name.test.ts
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src/core/workspace/name.ts src/core/workspace/name.test.ts
git commit -m "feat(workspace): name propagation from the primary session

Spec 9.5: an agent or user rename of the primary session renames its
workspace, unless the user locked the name by renaming the workspace by hand.
An unchanged name writes nothing — that is the loop guard."
```

---

## Task 6: Startup self-heal

**Files:**
- Create: `src/core/workspace/self-heal.ts`
- Test: `src/core/workspace/self-heal.test.ts`

Spec §9.3 states the invariant: every live session has exactly one chat view. This task repairs a database that breaks it.

- [ ] **Step 1: Write the failing tests**

Create `src/core/workspace/self-heal.test.ts`:

```ts
import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { WorkspaceStore } from "./store"
import { healSessionsWithoutWorkspace } from "./self-heal"

function seed() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return { db, ws: new WorkspaceStore(db) }
}

function insertSession(db: any, id: string, name: string, workdir: string, status = "active") {
  db.run(
    `INSERT INTO sessions (id, name, status, agent, workdir, created_at)
     VALUES (?, ?, ?, 'claude', ?, '2026-01-01T00:00:00.000Z')`,
    [id, name, status, workdir],
  )
}

test("a live session with no workspace gets one, with a chat view", () => {
  const { db, ws } = seed()
  insertSession(db, "s1", "orphan", "/w")

  const healed = healSessionsWithoutWorkspace(db, ws)
  expect(healed).toEqual(["s1"])

  const link = db.query("SELECT workspace_id FROM sessions WHERE id = 's1'").get() as any
  const w = ws.getById(link.workspace_id)!
  expect(w).toMatchObject({ name: "orphan", workdir: "/w", primary_session_id: "s1" })
  expect(ws.chatSessionIds(w.id)).toEqual(["s1"])
})

test("a session that already has a workspace is left alone", () => {
  const { db, ws } = seed()
  insertSession(db, "s1", "ok", "/w")
  const w = ws.create({ name: "ok", workdir: "/w", primary_session_id: "s1" })
  ws.addView(w.id, { kind: "chat", state: { sessionId: "s1" } })
  db.run("UPDATE sessions SET workspace_id = ? WHERE id = 's1'", [w.id])

  expect(healSessionsWithoutWorkspace(db, ws)).toEqual([])
})

test("a session pointing at a workspace that no longer exists is healed", () => {
  const { db, ws } = seed()
  insertSession(db, "s1", "dangling", "/w")
  db.run("UPDATE sessions SET workspace_id = 'gone' WHERE id = 's1'")

  expect(healSessionsWithoutWorkspace(db, ws)).toEqual(["s1"])
  const link = db.query("SELECT workspace_id FROM sessions WHERE id = 's1'").get() as any
  expect(ws.getById(link.workspace_id)).toBeDefined()
})

test("archived sessions are not healed", () => {
  const { db, ws } = seed()
  insertSession(db, "s1", "old", "/w", "archived")
  expect(healSessionsWithoutWorkspace(db, ws)).toEqual([])
})
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
bun test src/core/workspace/self-heal.test.ts
```

Expected: FAIL — `Cannot find module './self-heal'`.

- [ ] **Step 3: Write the implementation**

Create `src/core/workspace/self-heal.ts`:

```ts
import type { Database as Db } from "bun:sqlite"
import type { WorkspaceStore } from "./store"
import { log } from "../../shared/log"

type OrphanRow = {
  id: string
  name: string
  workdir: string
  repo_root: string | null
  base_branch: string | null
  session_branch: string | null
  sort_order: number
}

/**
 * Spec §9.3 — every live session must have exactly one chat view in exactly one
 * workspace. Migration 027 guarantees it for every row that existed then, and
 * the session-create path guarantees it for new rows.
 *
 * This repairs a database where it is false anyway: a crash between the session
 * insert and the workspace insert, or a workspace row deleted by hand. Run it
 * once at broker startup.
 *
 * A heal is a DEFECT SIGNAL, not a normal path. It logs at warn on purpose.
 * Returns the session ids that were healed.
 */
export function healSessionsWithoutWorkspace(db: Db, store: WorkspaceStore): string[] {
  const orphans = db.query(`
    SELECT s.id, s.name, s.workdir, s.repo_root, s.base_branch, s.session_branch, s.sort_order
      FROM sessions s
     WHERE s.status IN ('active', 'suspended')
       AND (s.workspace_id IS NULL
            OR NOT EXISTS (SELECT 1 FROM workspaces w WHERE w.id = s.workspace_id))
  `).all() as OrphanRow[]

  const healed: string[] = []
  for (const s of orphans) {
    const ws = store.create({
      name: s.name,
      workdir: s.workdir,
      repo_root: s.repo_root ?? undefined,
      base_branch: s.base_branch ?? undefined,
      branch: s.session_branch ?? undefined,
      primary_session_id: s.id,
      sort_order: s.sort_order,
    })
    store.addView(ws.id, { kind: "chat", state: { sessionId: s.id } })
    db.run("UPDATE sessions SET workspace_id = ? WHERE id = ?", [ws.id, s.id])
    healed.push(s.id)
    log.warn("workspace_self_heal", { session: s.id, workspace: ws.id, name: s.name })
  }
  return healed
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
bun test src/core/workspace/self-heal.test.ts
```

Expected: PASS, 4 tests.

If the `log` import fails, open `src/shared/log.ts` and match its actual export shape — the broker uses a shared structured logger with `log.warn(event, fields)`.

- [ ] **Step 5: Commit**

```bash
git add src/core/workspace/self-heal.ts src/core/workspace/self-heal.test.ts
git commit -m "feat(workspace): startup self-heal for a session with no workspace

Spec 9.3's invariant is every live session has exactly one chat view. This
repairs a database where that is false — a crash mid-create, or a hand-deleted
row. Logs at warn: a heal means a defect, not a normal path."
```

---

## Task 7: Wire the store into the registry

**Files:**
- Modify: `src/core/session-manager/registry.ts`
- Test: `src/core/session-manager/registry-workspaces.test.ts` (create)

- [ ] **Step 1: Write the failing test**

Create `src/core/session-manager/registry-workspaces.test.ts`:

```ts
import { test, expect } from "bun:test"
import { Registry } from "./registry"

test("registry exposes a workspace store", () => {
  const r = new Registry()
  expect(r.workspaces).toBeDefined()
  const w = r.workspaces.create({ name: "a", workdir: "/w" })
  expect(r.workspaces.getById(w.id)?.name).toBe("a")
})

test("registry heals a session that has no workspace", () => {
  const r = new Registry()
  // register() does not create a workspace yet — Phase 1b adds that to the
  // spawn path. Until then every registered session is an orphan, and the heal
  // is what keeps the invariant true.
  const s = r.register({ name: "n", workdir: "/w", pid: 1 })
  const healed = r.healWorkspaces()
  expect(healed).toContain(s.id)
  expect(r.workspaces.findByPrimarySession(s.id)).toBeDefined()
})
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
bun test src/core/session-manager/registry-workspaces.test.ts
```

Expected: FAIL — `r.workspaces` is undefined.

- [ ] **Step 3: Modify the registry**

Open `src/core/session-manager/registry.ts`.

Add these imports below the existing `ProxyStore` import:

```ts
import { WorkspaceStore } from "../workspace/store"
import { healSessionsWithoutWorkspace } from "../workspace/self-heal"
```

Add the public field beside `chats`:

```ts
  readonly sessions: SessionStore
  readonly chats: ChatStore
  readonly workspaces: WorkspaceStore
```

In the constructor, keep a reference to the resolved database (the self-heal needs it) and build the store:

```ts
  private readonly db: Database

  constructor(db?: Database) {
    const resolvedDb = db ?? createTestDb()
    this.db = resolvedDb
    this.sessions = new SessionStore(resolvedDb)
    this.chats = new ChatStore(resolvedDb)
    this.workspaces = new WorkspaceStore(resolvedDb)
    this.proxies = new ProxyStore(resolvedDb)
    // Runs after SessionStore has loaded active+suspended sessions, so orphan
    // detection sees the real set. Prunes proxies whose session is gone.
    this.reloadProxies()
  }
```

Add the heal method next to `register`:

```ts
  /**
   * Repair any live session that has no workspace. Called once at broker
   * startup (src/main.ts). Not called from the constructor: a test that builds
   * a Registry and then registers sessions would otherwise heal nothing, and a
   * heal writes rows, which a constructor should not do.
   */
  healWorkspaces(): string[] {
    return healSessionsWithoutWorkspace(this.db, this.workspaces)
  }
```

⚠ Do **not** call `healWorkspaces()` from the constructor. Phase 1b calls it from `src/main.ts` after the registry is built.

- [ ] **Step 4: Run the test to verify it passes**

```bash
bun test src/core/session-manager/registry-workspaces.test.ts
```

Expected: PASS, 2 tests.

- [ ] **Step 5: Run the whole suite**

```bash
./.mux/verify.sh
```

Expected: PASS. This is `bun test` over the entire broker.

- [ ] **Step 6: Commit**

```bash
git add src/core/session-manager/registry.ts src/core/session-manager/registry-workspaces.test.ts
git commit -m "feat(workspace): expose the workspace store on Registry

Adds registry.workspaces and registry.healWorkspaces(). The heal is not called
from the constructor — a constructor should not write rows, and main.ts calls
it explicitly at startup in Phase 1b."
```

---

## Task 8: Verify against real data

**Files:** none. This task runs the migration against a copy of the live database and reads the result.

⚠ Work on a **copy**. Do not point this at `~/.mux/state/db.sqlite3` itself. The live broker holds that file open.

- [ ] **Step 1: Take the copy**

```bash
cp ~/.mux/state/db.sqlite3 /tmp/db-027-check.sqlite3
```

- [ ] **Step 2: Write a throwaway migration runner**

Create `/tmp/run-027.ts`:

```ts
import { openDb, runMigrations } from "./src/core/storage/db"
import { MIGRATIONS } from "./src/core/storage/migrations"

const db = openDb("/tmp/db-027-check.sqlite3")
const before = db.query("SELECT count(*) c FROM sessions").get() as { c: number }
runMigrations(db, MIGRATIONS)
const ws = db.query("SELECT count(*) c FROM workspaces").get() as { c: number }
const vs = db.query("SELECT count(*) c FROM views").get() as { c: number }
const orphans = db.query("SELECT count(*) c FROM sessions WHERE workspace_id IS NULL").get() as { c: number }
const badLayout = db.query("SELECT count(*) c FROM workspaces WHERE json_valid(layout) = 0").get() as { c: number }

console.log({ sessions: before.c, workspaces: ws.c, views: vs.c, sessionsWithoutWorkspace: orphans.c, invalidLayouts: badLayout.c })
```

- [ ] **Step 3: Run it**

```bash
bun /tmp/run-027.ts
```

Expected, for a database with N sessions:

```
{ sessions: N, workspaces: N, views: N, sessionsWithoutWorkspace: 0, invalidLayouts: 0 }
```

⚠ `workspaces` must equal `sessions` exactly, and `sessionsWithoutWorkspace` must be `0`. Anything else means the backfill missed rows — most likely archived ones. Stop and report the numbers.

- [ ] **Step 4: Spot-check one row by eye**

```bash
bun -e '
import { openDb } from "./src/core/storage/db"
const db = openDb("/tmp/db-027-check.sqlite3")
console.log(db.query("SELECT id, name, workdir, repo_root, branch, layout, active_view_id, primary_session_id FROM workspaces LIMIT 3").all())
'
```

Read the output. Each `layout` must be a JSON group whose single `viewIds` entry equals that row's `active_view_id`.

- [ ] **Step 5: Clean up**

```bash
rm /tmp/db-027-check.sqlite3 /tmp/run-027.ts
```

- [ ] **Step 6: Report the numbers to the user**

Report the row counts from Step 3 and one sample workspace from Step 4. Do not deploy anything — this plan changes no runtime behaviour, and the live broker does not restart as part of it.

---

## Self-review notes

**Spec coverage.** This plan implements spec §5 (data model), §6 (migration), §9.5 (name propagation), and the storage half of §9.3 (the invariant plus self-heal). It deliberately does **not** implement §7 (HTTP), §8 (frames), §9.1–9.4 (behaviour flows), §10–13 (clients). Those belong to Phase 1b and later plans, listed below.

**Deliberately deferred to Phase 1b — do not build them here:**
- Every route in spec §7.1 and every frame in §8.1
- Calling `healWorkspaces()` from `src/main.ts`
- Creating a workspace inside the session spawn path
- The side effects of a view close (archive the session, kill the terminal, stop the display) — `WorkspaceStore.removeView` is storage only, by design
- Adding `workspaces` to `ServerFrame.Snapshot`

**Type consistency check.** `LayoutNode` is used by `types.ts`, `store.ts`, and `self-heal.ts` and is defined once in `layout-tree.ts`. `WorkspaceStore.create` takes `CreateWorkspaceInput` in Tasks 4, 6, and 7 with the same fields each time. `addView` takes `AddViewInput` in Tasks 4, 6, and 7 with the same fields. `chatSessionIds` is defined in Task 4 and used in Tasks 5 and 6.
