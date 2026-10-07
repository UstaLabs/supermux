# Persistent Projects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give projects a persistent, broker-local identity (name, image, order, multiple locations) while workspace membership stays computed from paths.

**Architecture:** Two new tables (`projects`, `project_locations`) behind a `ProjectStore` (SQL) and `ProjectService` (normalize → exact match, idempotent registration, startup reconciliation, images on disk). The broker resolves each workspace's `repo_root ?? workdir` to a `project_id` and ships it on `WorkspaceDto`; a `projects_changed` frame carries the full catalog plus a `workspaceId → projectId` membership map so reassignment refreshes active and archived rows. Clients group by `(hostId, projectId)` and fall back to path grouping for unresolved rows or old brokers.

**Tech Stack:** Bun + TypeScript + bun:sqlite (broker, `bun test`); Kotlin Multiplatform shared + Compose Multiplatform UI (`./gradlew :shared:allTests`, `:ui:compileKotlinDesktop`).

**Spec:** `docs/superpowers/specs/2026-09-21-project-organization-design.md`

---

## Wire contract (fixed — every phase codes against this)

```jsonc
// ProjectDto
{ "id": "uuid", "name": "Supermux", "image_id": "uuid.png" /* optional */, "sort_order": 0,
  "created_at": "iso", "locations": [ { "id": "uuid", "path": "/home/u/projects/supermux" } ] }

// WorkspaceDto gains (optional, omitted when unresolved):
{ "project_id": "uuid" }

// snapshot frame gains:
{ "projects": [ProjectDto], "projectMembership": { "<workspaceId>": "<projectId>" } }

// new broadcast frame (full replacement, active AND archived membership):
{ "type": "projects_changed", "projects": [ProjectDto], "projectMembership": { ... } }
```

HTTP (all authenticated, JSON unless noted). `GET /projects` stays path-only and unchanged.

| Method | Path | Body | Result |
|---|---|---|---|
| GET | `/project-catalog` | – | `{ projects: ProjectDto[] }` |
| POST | `/project-catalog` | `{ name }` | `ProjectDto` (201) |
| PATCH | `/project-catalog/reorder` | `{ orderedIds: string[] }` | `{ ok: true }` |
| PATCH | `/project-catalog/:id` | `{ name? }` | `ProjectDto` / 404 / 400 |
| POST | `/project-catalog/:id/locations` | `{ path }` | `ProjectDto` / 404 / 409 `{ error, projectId }` / 400 |
| PATCH | `/project-catalog/locations/:locationId` | `{ projectId }` | `ProjectDto` (target) / 404 |
| PUT | `/project-catalog/:id/image` | raw bytes, `Content-Type: image/png|jpeg|webp|gif`, ≤ 5 MB | `ProjectDto` / 415 / 413 / 404 |
| DELETE | `/project-catalog/:id/image` | – | `ProjectDto` |
| GET | `/project-catalog/:id/image` | – | image bytes (`Cache-Control: private, max-age=300`) / 404 |

Every mutation broadcasts `projects_changed`.

## File structure

Broker (new `src/core/project/`):
- `src/core/storage/migrations/030_projects.sql` — tables + indexes only (backfill is reconciliation, in TS, because normalization and labels are not expressible in SQL).
- `src/core/project/types.ts` — records, rows, DTO, `projectDto()`.
- `src/core/project/paths.ts` — `normalizeLocationPath`, `effectiveLocation`, `pathLabel` (TS port of Kotlin `formatWorkdir`).
- `src/core/project/store.ts` — all SQL for the two tables.
- `src/core/project/service.ts` — resolve, ensureLocation (transactional/idempotent), reconcile, move, images, membership.
- `src/core/project/images.ts` — durable image files under `<STATE_DIR>/project-images/`.
- Modify `src/core/workspace/dto.ts`, `src/core/workspace/service.ts`, `src/core/workspace/self-heal.ts`, `src/core/session-manager/registry.ts`, `src/main.ts`, `src/channels/web/index.ts`, `src/core/storage/migrations/index.ts`.

Shared KMP:
- `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt` — `ProjectDto`, `ProjectLocationDto`, `WorkspaceDto.projectId`, `Snapshot.projects/projectMembership`, `ProjectsChanged`.
- `apps/shared/src/commonMain/kotlin/dev/supermux/state/HostState.kt`, `HostReducer.kt`, `HostStore.kt`, `FleetStore.kt`.
- `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt` — catalog calls.
- `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/WorkspaceGrouping.kt` — project-aware grouping.

UI:
- `apps/ui/src/commonMain/kotlin/dev/supermux/ui/session/SessionListScreen.kt`, `ArchivedScreen.kt`, `SessionLauncherScreen.kt`, `SessionActions.kt`, plus a new `ProjectSettingsSheet.kt`.

---

## Phase 1 — Broker

### Task 1: Migration 030

**Files:**
- Create: `src/core/storage/migrations/030_projects.sql`
- Create: `src/core/storage/migrations/030_projects.test.ts`
- Modify: `src/core/storage/migrations/index.ts`

- [ ] **Step 1: Failing test** `030_projects.test.ts`:

```ts
import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../db"
import { MIGRATIONS } from "./index"

test("030 creates projects and project_locations with a unique path", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  db.run("INSERT INTO projects (id, name, sort_order, created_at) VALUES ('p1','A',0,'t')")
  db.run("INSERT INTO project_locations (id, project_id, path) VALUES ('l1','p1','/a')")
  expect(() => db.run("INSERT INTO project_locations (id, project_id, path) VALUES ('l2','p1','/a')")).toThrow()
})

test("030 rejects a location for a missing project", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  expect(() => db.run("INSERT INTO project_locations (id, project_id, path) VALUES ('l1','nope','/a')")).toThrow()
})

test("030 rejects an empty name", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  expect(() => db.run("INSERT INTO projects (id, name, sort_order, created_at) VALUES ('p1','',0,'t')")).toThrow()
})
```

- [ ] **Step 2:** `bun test src/core/storage/migrations/030_projects.test.ts` → FAIL (no such table).
- [ ] **Step 3:** `030_projects.sql`:

```sql
-- Persistent projects. Membership is NOT stored on workspaces or sessions: the
-- broker resolves repo_root ?? workdir to a location at read time.
-- Rows are backfilled by ProjectService.reconcile() at startup, not here —
-- path normalization and default labels are TypeScript rules.
-- Spec: docs/superpowers/specs/2026-09-21-project-organization-design.md
CREATE TABLE projects (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL CHECK(length(trim(name)) > 0),
  image_id    TEXT,
  sort_order  INTEGER NOT NULL DEFAULT 0,
  created_at  TEXT NOT NULL
);

CREATE TABLE project_locations (
  id          TEXT PRIMARY KEY,
  project_id  TEXT NOT NULL REFERENCES projects(id),
  path        TEXT NOT NULL UNIQUE
);
CREATE INDEX project_locations_project ON project_locations(project_id);
```

Add to `index.ts`: `import m030 from "./030_projects.sql" with { type: "text" }` and `{ version: 30, name: "030_projects", sql: m030 },`.

- [ ] **Step 4:** Run the test plus `bun test src/core/storage` → PASS (the embedded-manifest test must stay green).
- [ ] **Step 5:** `git commit -m "feat(projects): add projects and project_locations tables"`

### Task 2: Path rules

**Files:** Create `src/core/project/paths.ts`, `src/core/project/paths.test.ts`

- [ ] **Step 1: Failing tests:**

```ts
import { test, expect } from "bun:test"
import { normalizeLocationPath, effectiveLocation, pathLabel } from "./paths"

test("normalize removes redundant separators, dot segments and trailing slash, keeps case", () => {
  expect(normalizeLocationPath("/home/u//Proj/./a/../b/")).toBe("/home/u/Proj/b")
  expect(normalizeLocationPath("/")).toBe("/")
})
test("normalize rejects a relative path", () => {
  expect(normalizeLocationPath("rel/x")).toBeUndefined()
  expect(normalizeLocationPath("")).toBeUndefined()
})
test("effectiveLocation prefers repo_root", () => {
  expect(effectiveLocation({ workdir: "/w/tree", repo_root: "/r" })).toBe("/r")
  expect(effectiveLocation({ workdir: "/w/", repo_root: null })).toBe("/w")
})
test("effectiveLocation leaves a bare managed worktree unresolved", () => {
  expect(effectiveLocation({ workdir: "/h/.mux/worktrees/x/y" }, "/h/.mux/worktrees")).toBeUndefined()
  expect(effectiveLocation({ workdir: "/h/.mux/worktrees/x/y", repo_root: "/r" }, "/h/.mux/worktrees")).toBe("/r")
})
test("pathLabel matches the Kotlin formatWorkdir convention", () => {
  expect(pathLabel("/home/u", "/home/u")).toBe("~")
  expect(pathLabel("/home/u/app", "/home/u")).toBe("~/app")
  expect(pathLabel("/home/u/projects/app", "/home/u")).toBe("…/projects/app")
  expect(pathLabel("/srv/app", "/home/u")).toBe("srv/app")
  expect(pathLabel("/app", "/home/u")).toBe("/app")
})
```

- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement:

```ts
import { posix } from "path"

/**
 * One normalization for registration AND lookup (spec "Resolution"): lexical only —
 * separators, dot segments, trailing slash. No realpath, no case folding: reads must
 * not touch the filesystem, and archived paths may no longer exist.
 */
export function normalizeLocationPath(path: string): string | undefined {
  if (!path || !path.startsWith("/")) return undefined
  const n = posix.normalize(path)
  return n.length > 1 ? n.replace(/\/+$/, "") : n
}

/** repo_root ?? workdir, normalized. A managed worktree with no recorded repo_root stays unresolved. */
export function effectiveLocation(
  w: { workdir: string; repo_root?: string | null },
  managedWorktreesRoot?: string,
): string | undefined {
  if (w.repo_root) return normalizeLocationPath(w.repo_root)
  const p = normalizeLocationPath(w.workdir)
  if (!p) return undefined
  if (managedWorktreesRoot && (p === managedWorktreesRoot || p.startsWith(managedWorktreesRoot + "/"))) return undefined
  return p
}

/** TS port of Kotlin `formatWorkdir` (apps/shared/.../session/SessionGrouping.kt) — the default project name. */
export function pathLabel(path: string, home: string): string {
  if (home && path === home) return "~"
  const segments = path.split("/").filter(Boolean)
  if (segments.length <= 1) return path
  const leaf = segments[segments.length - 1]!
  const parent = segments[segments.length - 2]!
  const parentPath = "/" + segments.slice(0, -1).join("/")
  if (home && parentPath === home) return `~/${leaf}`
  const base = `${parent}/${leaf}`
  return segments.length > 2 ? `…/${base}` : base
}
```

- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** `git commit -m "feat(projects): path normalization, effective location, default label"`

### Task 3: ProjectStore + DTO

**Files:** Create `src/core/project/types.ts`, `src/core/project/store.ts`, `src/core/project/store.test.ts`

- [ ] **Step 1:** `types.ts`:

```ts
export type ProjectLocationRecord = { id: string; project_id: string; path: string }
export type ProjectRecord = {
  id: string; name: string; image_id?: string; sort_order: number; created_at: string
}
export type ProjectDto = {
  id: string; name: string; image_id?: string; sort_order: number; created_at: string
  locations: Array<{ id: string; path: string }>
}
export type ProjectRow = { id: string; name: string; image_id: string | null; sort_order: number; created_at: string }

export function rowToProject(r: ProjectRow): ProjectRecord {
  const p: ProjectRecord = { id: r.id, name: r.name, sort_order: r.sort_order, created_at: r.created_at }
  if (r.image_id) p.image_id = r.image_id
  return p
}

export function projectDto(p: ProjectRecord, locations: ProjectLocationRecord[]): ProjectDto {
  const dto: ProjectDto = {
    id: p.id, name: p.name, sort_order: p.sort_order, created_at: p.created_at,
    locations: locations.map((l) => ({ id: l.id, path: l.path })),
  }
  if (p.image_id) dto.image_id = p.image_id
  return dto
}
```

- [ ] **Step 2: Failing tests** `store.test.ts` (in-memory db + `runMigrations(db, MIGRATIONS)`):
  - `create` returns a record with a UUID id; `list()` orders by `sort_order, name, id`.
  - `addLocation(projectId, path)` stores it; `findLocationByPath` returns it; a second `addLocation` with the same path throws (UNIQUE).
  - `listLocations(projectId)` returns locations ordered by path; `allLocations()` returns every row.
  - `rename`, `setImage(id, imageId | null)`, `reorder(ids)` (index → sort_order), `moveLocation(locationId, projectId)`, `maxSortOrder()` (−1 on empty).

- [ ] **Step 3:** Implement `ProjectStore` in the style of `WorkspaceStore` (constructor `(db: Db)`, plain SQL, no cache):

```ts
import { randomUUID } from "crypto"
import type { Database as Db } from "bun:sqlite"
import { type ProjectRecord, type ProjectRow, type ProjectLocationRecord, rowToProject } from "./types"

/** All SQL for projects and project_locations. No cache — read on route calls and DTO builds only. */
export class ProjectStore {
  constructor(readonly db: Db) {}

  create(input: { id?: string; name: string; sort_order: number }): ProjectRecord {
    const id = input.id ?? randomUUID()
    this.db.run("INSERT INTO projects (id, name, image_id, sort_order, created_at) VALUES (?, ?, NULL, ?, ?)",
      [id, input.name, input.sort_order, new Date().toISOString()])
    return this.getById(id)!
  }
  getById(id: string): ProjectRecord | undefined {
    const r = this.db.query("SELECT * FROM projects WHERE id = ?").get(id) as ProjectRow | null
    return r ? rowToProject(r) : undefined
  }
  list(): ProjectRecord[] {
    return (this.db.query("SELECT * FROM projects ORDER BY sort_order ASC, name ASC, id ASC").all() as ProjectRow[]).map(rowToProject)
  }
  maxSortOrder(): number {
    const r = this.db.query("SELECT max(sort_order) m FROM projects").get() as { m: number | null }
    return r.m ?? -1
  }
  rename(id: string, name: string): void { this.db.run("UPDATE projects SET name = ? WHERE id = ?", [name, id]) }
  setImage(id: string, imageId: string | null): void { this.db.run("UPDATE projects SET image_id = ? WHERE id = ?", [imageId, id]) }
  reorder(ids: string[]): void {
    this.db.transaction((xs: string[]) => {
      xs.forEach((id, i) => this.db.run("UPDATE projects SET sort_order = ? WHERE id = ?", [i, id]))
    })(ids)
  }
  addLocation(projectId: string, path: string, id: string = randomUUID()): ProjectLocationRecord {
    this.db.run("INSERT INTO project_locations (id, project_id, path) VALUES (?, ?, ?)", [id, projectId, path])
    return { id, project_id: projectId, path }
  }
  findLocationByPath(path: string): ProjectLocationRecord | undefined {
    return (this.db.query("SELECT * FROM project_locations WHERE path = ?").get(path) as ProjectLocationRecord | null) ?? undefined
  }
  getLocation(id: string): ProjectLocationRecord | undefined {
    return (this.db.query("SELECT * FROM project_locations WHERE id = ?").get(id) as ProjectLocationRecord | null) ?? undefined
  }
  listLocations(projectId: string): ProjectLocationRecord[] {
    return this.db.query("SELECT * FROM project_locations WHERE project_id = ? ORDER BY path ASC").all(projectId) as ProjectLocationRecord[]
  }
  allLocations(): ProjectLocationRecord[] {
    return this.db.query("SELECT * FROM project_locations").all() as ProjectLocationRecord[]
  }
  moveLocation(locationId: string, projectId: string): void {
    this.db.run("UPDATE project_locations SET project_id = ? WHERE id = ?", [projectId, locationId])
  }
}
```

- [ ] **Step 4:** `bun test src/core/project` → PASS.
- [ ] **Step 5:** `git commit -m "feat(projects): ProjectStore and ProjectDto"`

### Task 4: ProjectService (resolve, register, reconcile, move, membership, images)

**Files:** Create `src/core/project/service.ts`, `src/core/project/images.ts`, `src/core/project/service.test.ts`, `src/core/project/images.test.ts`

Interface (later tasks depend on these exact names):

```ts
export class ProjectConflictError extends Error { constructor(readonly projectId: string) { super("location already belongs to a project") } }
export class ProjectNotFoundError extends Error {}

export class ProjectService {
  constructor(store: ProjectStore, opts: { home: string; managedWorktreesRoot: string; images: ProjectImages })
  /** Read-only. Never writes, never stats. */
  resolve(w: { workdir: string; repo_root?: string | null }): string | undefined   // → projectId
  /** Idempotent + transactional: an unknown location gets a new project named pathLabel(path, home), appended to the order. Undefined for an unresolvable path. */
  ensureLocation(w: { workdir: string; repo_root?: string | null }): { projectId: string; created: boolean } | undefined
  /** Startup backfill/reconciliation over workspaces + legacy sessions without a workspace. New projects get sort_order in pathLabel order after the current max. Returns created project ids. */
  reconcile(db: Db): string[]
  create(name: string): ProjectDto                       // trims; throws Error("name required") on empty
  rename(id: string, name: string): ProjectDto           // ProjectNotFoundError / Error("name required")
  reorder(ids: string[]): void
  addLocation(projectId: string, rawPath: string): ProjectDto   // normalizes; ProjectConflictError if owned (even by the same project → returns dto instead, idempotent); Error("absolute path required")
  moveLocation(locationId: string, projectId: string): ProjectDto  // ProjectNotFoundError for either id; the source project stays
  setImage(id: string, bytes: Uint8Array, mime: string): ProjectDto   // writes new file, then deletes the old one
  clearImage(id: string): ProjectDto
  imageFile(id: string): { path: string; mime: string } | undefined
  get(id: string): ProjectDto | undefined
  list(): ProjectDto[]
  /** workspaceId → projectId for every row given (active and archived). */
  membership(workspaces: Array<{ id: string; workdir: string; repo_root?: string | null }>): Record<string, string>
}
```

`ensureLocation` implementation (this is what makes concurrent creation safe — bun:sqlite is synchronous, so the transaction cannot interleave, and `INSERT … ON CONFLICT(path) DO NOTHING` + re-read makes a lost race harmless):

```ts
ensureLocation(w: { workdir: string; repo_root?: string | null }): { projectId: string; created: boolean } | undefined {
  const path = effectiveLocation(w, this.opts.managedWorktreesRoot)
  if (!path) return undefined
  return this.store.db.transaction(() => {
    const found = this.store.findLocationByPath(path)
    if (found) return { projectId: found.project_id, created: false }
    const p = this.store.create({ name: pathLabel(path, this.opts.home), sort_order: this.store.maxSortOrder() + 1 })
    this.store.addLocation(p.id, path)
    return { projectId: p.id, created: true }
  })()
}
```

`reconcile(db)` gathers `SELECT workdir, repo_root FROM workspaces` plus `SELECT workdir, repo_root FROM sessions WHERE workspace_id IS NULL`, computes effective locations, dedupes, drops already-registered paths, sorts the rest by `pathLabel` (then path), and inserts them in ONE transaction with consecutive sort_orders after `maxSortOrder()`. It must never read the filesystem.

`ProjectImages` (`images.ts`):

```ts
const EXT: Record<string, string> = { "image/png": "png", "image/jpeg": "jpg", "image/webp": "webp", "image/gif": "gif" }
export const PROJECT_IMAGE_MAX_BYTES = 5 * 1024 * 1024
export class ProjectImages {
  constructor(private readonly dir: string) {}   // <STATE_DIR>/project-images, created 0700 on first write
  isSupported(mime: string): boolean { return mime in EXT }
  write(bytes: Uint8Array, mime: string): string  // returns image_id `${randomUUID()}.${ext}`; atomic tmp+rename
  path(imageId: string): string | undefined        // rejects ids not matching /^[0-9a-f-]{36}\.(png|jpg|webp|gif)$/
  mimeOf(imageId: string): string
  remove(imageId: string): void                    // best effort
}
```

The directory is NOT under the chat-upload store, so upload cleanup never touches it (spec requirement).

- [ ] **Step 1: Failing tests** in `service.test.ts` — one `test` per behavior:
  1. `resolve` returns undefined for an unknown path and performs no writes (count rows before/after).
  2. `ensureLocation` twice for the same workdir → one project, one location, same id; `created` is true then false.
  3. A worktree workspace `{ workdir: "/h/.mux/worktrees/a/b", repo_root: "/h/projects/app" }` resolves to the `/h/projects/app` project.
  4. Nested path `/h/projects/app/sub` does NOT resolve to `/h/projects/app` (no prefix matching).
  5. `addLocation` to project B of a path owned by A → `ProjectConflictError` with `projectId === A`; the same call on A is idempotent.
  6. `moveLocation` → `resolve` now returns B; A still exists (`get(A)` defined, zero locations).
  7. `reconcile` over seeded workspaces `/h/b`, `/h/a`, worktree-with-repo_root `/h/a`, bare managed worktree, plus a legacy session without workspace `/h/c` → 3 projects named `~/a`,`~/b`,`~/c` with sort_order 0,1,2; running it again creates nothing.
  8. `reconcile` on a DB with nonexistent directories still works (paths are fake) — covered by the fake paths above.
  9. `create("  ")` throws; `rename` of unknown id throws `ProjectNotFoundError`.
  10. `setImage` writes a file under a temp dir, `imageFile` returns it; `setImage` again removes the previous file; `clearImage` removes it and unsets `image_id`.
  11. `membership` maps workspace ids to project ids and omits unresolved ones.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement `service.ts` and `images.ts`.
- [ ] **Step 4:** `bun test src/core/project` → PASS.
- [ ] **Step 5:** `git commit -m "feat(projects): ProjectService with idempotent registration and reconciliation"`

### Task 5: Wire into workspaces, startup, and DTOs

**Files:**
- Modify: `src/core/workspace/dto.ts` (add optional `project_id` to `WorkspaceDto`; `workspaceDto(w, views, projectId?)`).
- Modify: `src/core/workspace/service.ts` (optional `ensureProject?: (w: { workdir: string; repo_root?: string }) => void` dep called inside `createForSession` BEFORE `store.create`, in the same `db.transaction` when `db` exists — a throw aborts the workspace insert).
- Modify: `src/core/workspace/self-heal.ts` — accept an optional `ensureProject` callback and call it per healed row.
- Modify: `src/core/session-manager/registry.ts` — construct `this.projects = new ProjectStore(resolvedDb)`.
- Modify: `src/main.ts` — build `projectService` (`home()`, `worktreesRoot()`, `new ProjectImages(join(STATE_DIR, "project-images"))`); call `projectService.reconcile(registry.db)` right after `registry.healWorkspaces()` (line ~258); pass `ensureProject` to `WorkspaceService`; the bare `createWorkspace` opt calls `projectService.ensureLocation({ workdir })` before `registry.workspaces.create`; every `workspaceDto(...)` call in `main.ts` (lines ~1157, 1559, 1815, 1820, 1848) passes `projectService.resolve(w)`. Add a local helper `const toWsDto = (w) => workspaceDto(w, registry.workspaces.listViews(w.id), projectService.resolve(w))` and use it everywhere.
- Test: `src/core/workspace/dto.test.ts`, `src/core/workspace/service.test.ts`.

- [ ] **Step 1: Failing tests:**
  - `dto.test.ts`: `workspaceDto(w, [], "p1").project_id === "p1"`; omitted when undefined.
  - `service.test.ts`: `createForSession` with an `ensureProject` dep that throws → throws, and `store.list()` is empty afterwards (rollback, no orphan workspace). With a recording dep → called once with the workspace paths.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. In `WorkspaceService.createForSession`:

```ts
createForSession(input: CreateForSessionInput): WorkspaceRecord {
  const run = () => {
    this.deps.ensureProject?.({ workdir: input.workdir, repo_root: input.repo_root })
    const ws = this.store.create({ /* unchanged fields */ })
    this.store.addView(ws.id, { kind: "chat", state: { sessionId: input.sessionId } })
    this.linkSession(input.sessionId, ws.id)
    return this.store.getById(ws.id)!
  }
  return this.db ? this.db.transaction(run)() : run()
}
```

(`ensureProject` goes in `WorkspaceDeps` as an optional member so existing test harnesses compile unchanged.)

Deviation from the spec text, recorded here: the agent process is spawned before `createForSession` in the current spawn path, so "rolls back before an agent is launched" becomes "rolls back the workspace insert atomically". A failure is a SQLite error, and the existing self-heal repairs the orphaned session on the next start.
- [ ] **Step 4:** `bun test src/core/workspace src/core/project` → PASS; `bunx tsc --noEmit -p .` → no new errors.
- [ ] **Step 5:** `git commit -m "feat(projects): register locations on workspace creation and resolve project_id in DTOs"`

### Task 6: HTTP catalog routes, snapshot, and broadcasts

**Files:**
- Modify: `src/channels/web/index.ts` — add `"/project-catalog"` to `API_PREFIXES`; new opts (below); routes from the wire-contract table, placed right after the `GET /projects` block; `PATCH /project-catalog/reorder` and `/project-catalog/locations/:id` are matched BEFORE `/project-catalog/:id`. Snapshot adds `projects` and `projectMembership`.
- Modify: `src/main.ts` — implement the opts with `projectService`; after every catalog mutation call `broadcastProjects()`:

```ts
function broadcastProjects(): void {
  const all = registry.workspaces.list({ includeArchived: true })
  webChannel?.broadcastToAll({
    type: "projects_changed",
    projects: projectService.list(),
    projectMembership: projectService.membership(all),
  })
}
```

Also call `broadcastProjects()` after `createForSession`/`createWorkspace` when `ensureLocation` reported `created: true`.

New `WebChannelOpts` members:

```ts
listProjectCatalog?: () => unknown[]
getProjectMembership?: () => Record<string, string>
createProject?: (name: string) => unknown
renameProject?: (id: string, name: string) => unknown
reorderProjects?: (ids: string[]) => void
addProjectLocation?: (id: string, path: string) => unknown
moveProjectLocation?: (locationId: string, projectId: string) => unknown
setProjectImage?: (id: string, bytes: Uint8Array, mime: string) => unknown
clearProjectImage?: (id: string) => unknown
projectImageFile?: (id: string) => { path: string; mime: string } | undefined
```

Error mapping in routes: `ProjectNotFoundError` → 404, `ProjectConflictError` → 409 `{ error, projectId }`, other `Error` → 400. `POST .../locations` passes the raw path through `normalizeExistingWorkdir` (tilde expansion + existence check → 400 when missing): registering a location is a user action on a live directory. Reads never stat.

Image PUT: reject `content-length` > `PROJECT_IMAGE_MAX_BYTES` with 413 before reading, then `new Uint8Array(await req.arrayBuffer())` and re-check size; unsupported mime → 415.

- [ ] **Step 1: Failing tests** `src/channels/web/project-routes.test.ts`, following `upload-routes.test.ts`/`reasoning-levels-route.test.ts` for how to construct a `WebChannel` and call its handler with an authenticated request (read those files first and copy their harness). Cover: GET list; POST create → 201 + one `projects_changed` broadcast; PATCH rename 404 on unknown; POST location 409 with `projectId`; PATCH reorder broadcasts; PUT image 415 on `text/plain`, 413 on oversize content-length; GET `/projects` still returns `{ projects: [{ path }] }` shape.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement routes + main.ts opts + snapshot fields.
- [ ] **Step 4:** `bun test src/channels/web src/core/project src/core/workspace` → PASS; `bunx tsc --noEmit -p .` clean.
- [ ] **Step 5:** `git commit -m "feat(projects): project catalog API, snapshot fields, projects_changed broadcast"`

---

## Phase 2 — Shared KMP (can start in parallel with Phase 1 once the wire contract above is fixed)

### Task 7: Protocol, state, API

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt`
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/state/HostState.kt`, `HostReducer.kt`, `HostStore.kt`, `FleetStore.kt`
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/state/HostReducerProjectsTest.kt`, `apps/shared/src/commonTest/kotlin/dev/supermux/proto/ProjectFramesTest.kt`

- [ ] **Step 1: Failing tests:**
  - Decoding `{"type":"projects_changed","projects":[{"id":"p","name":"A","sort_order":0,"created_at":"t","locations":[{"id":"l","path":"/a"}]}],"projectMembership":{"w1":"p"}}` yields `ServerFrame.ProjectsChanged`.
  - A snapshot without `projects` decodes (old broker) with empty lists.
  - Reducer: snapshot sets `projects` and patches `projectId` onto active + archived workspaces from `projectMembership`; `ProjectsChanged` replaces `projects` and re-patches both lists (a workspace absent from the map gets `projectId = null`); `WorkspaceAdded` keeps the dto's own `projectId`.
- [ ] **Step 2:** `cd apps && ./gradlew :shared:desktopTest --tests '*Project*'` (check `apps/shared/build.gradle.kts` for the JVM target's test task name) → FAIL.
- [ ] **Step 3:** Implement:

```kotlin
@Serializable
data class ProjectLocationDto(val id: String, val path: String)

@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    @SerialName("image_id") val imageId: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    val locations: List<ProjectLocationDto> = emptyList(),
)
```

`WorkspaceDto` gains `@SerialName("project_id") val projectId: String? = null`. `Snapshot` gains `val projects: List<ProjectDto> = emptyList()` and `val projectMembership: Map<String, String> = emptyMap()`. New `@Serializable @SerialName("projects_changed") data class ProjectsChanged(val projects: List<ProjectDto> = emptyList(), val projectMembership: Map<String, String> = emptyMap()) : ServerFrame`. `HostState.projects: List<ProjectDto> = emptyList()` and `HostState.projectCatalogKnown: Boolean = false` (true once a snapshot/frame carried a catalog — lets the UI tell "old broker" from "no projects"; a snapshot sets it to `frame.projects.isNotEmpty() || frame.projectMembership.isNotEmpty()`, `ProjectsChanged` sets it true).

Reducer helper:

```kotlin
private fun applyMembership(ws: List<WorkspaceDto>, m: Map<String, String>): List<WorkspaceDto> =
    ws.map { w -> val p = m[w.id]; if (w.projectId == p) w else w.copy(projectId = p) }
```

On a snapshot from an old broker (empty membership AND empty projects) leave the dtos' own `projectId` (null) alone.

`HostStore`: `val projects: StateFlow<List<ProjectDto>>`, plus suspend wrappers over the new `BrokerApi` calls using the existing `runApi` pattern. `BrokerApi` (model on `createWorkspace`/`reorderWorkspaces`): `listProjectCatalog()`, `createProject(name)`, `renameProject(id, name)`, `reorderProjects(ids)`, `addProjectLocation(id, path)` (409 → throw a `ProjectLocationConflict(projectId)` exception), `moveProjectLocation(locationId, projectId)`, `setProjectImage(id, bytes: ByteArray, mime)`, `clearProjectImage(id)`, `projectImageUrl(id, imageId): String` (`"$httpBase/project-catalog/$id/image?v=$imageId"` — the version query busts caches after a change).

`FleetStore`: `data class HostProject(val hostId: String, val project: ProjectDto)` and `val projects: StateFlow<List<HostProject>>` built from each host's `HostStore.projects` tagged with its host record id (see how `_sessionHost` learns record ids; reuse that source). Mutations route to the owning host's `HostStore`.
- [ ] **Step 4:** Tests → PASS; `./gradlew :shared:compileKotlinDesktop :shared:compileKotlinWasmJs` (use the targets that exist in `apps/shared/build.gradle.kts`) → success.
- [ ] **Step 5:** `git commit -m "feat(projects): shared protocol, reducer, API and fleet projection for projects"`

### Task 8: Project-aware grouping

**Files:** Modify `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/WorkspaceGrouping.kt`, `apps/shared/src/commonTest/kotlin/dev/supermux/workspace/WorkspaceGroupingTest.kt`

New shape (keeps `key`/`label`/`workspaces` so existing call sites keep compiling):

```kotlin
data class WorkspaceGroup(
    /** "p:<hostId>:<projectId>" for a persistent project, the raw path for fallback groups, PA_GROUP_KEY for PAs. */
    val key: String,
    val label: String,
    val workspaces: List<WorkspaceDto>,
    /** Set for persistent projects only. */
    val project: ProjectDto? = null,
    val hostId: String? = null,
)

fun projectGroupKey(hostId: String, projectId: String): String = "p:$hostId:$projectId"
```

`groupWorkspaces(workspaces, home, isPersonalAssistant = { false }, projects: List<ProjectRef> = emptyList(), hostOf: (WorkspaceDto) -> String = { "" })` where `data class ProjectRef(val hostId: String, val project: ProjectDto)`:
1. PAs first, exactly as today.
2. Every `ProjectRef` becomes a group (empty projects included — spec: persistent projects show without workspaces), ordered by `sortOrder`, then `name`, then `id`; label = `project.name`.
3. Workspaces with a `projectId` that matches `(hostOf(w), projectId)` go into that group.
4. The rest fall back to today's path grouping, sorted by label, appended after project groups.
With `projects` empty the output is byte-identical to today (existing tests prove it).

`groupArchivedWorkspaces(workspaces, home, projects = emptyList(), hostOf = { "" })` — same resolution, but only non-empty groups are returned (an archive fold with empty projects is noise), ordered like the live list.

- [ ] **Step 1: Failing tests:** two workspaces in different repos with the same `projectId` land in one group labeled with the project name; an empty project yields an empty group; project order follows `sortOrder` not name; a workspace with an unknown `projectId` falls back to path grouping; same projectId on two hosts stays two groups; all existing tests still pass unchanged.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** `git commit -m "feat(projects): group workspaces by persistent project with path fallback"`

---

## Phase 3 — UI (after Task 7 and 8)

### Task 9: Sidebar and archive use projects

**Files:** Modify `apps/ui/src/commonMain/kotlin/dev/supermux/ui/session/SessionListScreen.kt`, `ArchivedScreen.kt`, and the callers that pass them state (find with `grep -rn "SessionListScreen(" apps/ui apps/*/src`).

- Add params `projects: List<ProjectRef> = emptyList()` and `workspaceHost: (WorkspaceDto) -> String = { "" }` to both screens; single-host callers pass `app.projects` mapped to `ProjectRef("", it)`; fleet callers pass `fleet.projects` mapped to `ProjectRef(hostId, project)` and a `workspaceHost` derived from `sessionHost[primarySessionId]`.
- Pass them into `groupWorkspaces` / `groupArchivedWorkspaces`.
- Group header: when `group.project?.imageId != null`, draw a 20dp rounded image loaded from `projectImageUrl` (reuse whatever image loader the chat attachment thumbnails use — `grep -rn "AsyncImage\|rememberImage" apps/ui`) before the label; otherwise the existing header. The collapsed-state set keeps using `group.key` (project keys are stable across renames — that is the point).
- Empty project groups render their header with a muted "No workspaces" row whose "+" starts a new workspace in that project (Task 10's launcher entry point).
- Header overflow menu (project groups only): "Project settings…" (Task 11), and "Move up"/"Move down" calling `reorderProjects` — drag reordering of project headers is out of scope for this task.
- Verify: `cd apps && ./gradlew :ui:compileKotlinDesktop` succeeds; existing UI tests (`./gradlew :ui:desktopTest`) pass.
- Commit: `feat(projects): sidebar and archive group by persistent project`

### Task 10: Launcher picks a project, then a location

**Files:** Modify `apps/ui/src/commonMain/kotlin/dev/supermux/ui/session/SessionLauncherScreen.kt`, `SessionActions.kt`; add a pure helper + test `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/ProjectLaunch.kt` / `apps/shared/src/commonTest/.../ProjectLaunchTest.kt`.

Pure helper (TDD it first):

```kotlin
/** Which directory a new workspace in [project] starts in. */
sealed interface LaunchLocation {
    data class Chosen(val path: String) : LaunchLocation
    data class Choose(val paths: List<String>) : LaunchLocation
    data object NeedsLocation : LaunchLocation
}

fun launchLocation(project: ProjectDto, remembered: String?): LaunchLocation {
    val paths = project.locations.map { it.path }
    return when {
        paths.isEmpty() -> LaunchLocation.NeedsLocation
        paths.size == 1 -> LaunchLocation.Chosen(paths.single())
        remembered != null && remembered in paths -> LaunchLocation.Chosen(remembered)
        else -> LaunchLocation.Choose(paths)
    }
}
```

- `SessionActions` gains `listProjectCatalog: suspend () -> List<ProjectDto>` (default empty) wired to `HostStore`/`FleetStore`.
- The launcher's picker shows projects (name + image) when the catalog is non-empty; selecting one applies `launchLocation`. `Choose` shows a second list of the project's locations (labels via `formatWorkdir`); `NeedsLocation` shows the existing path-entry/validate UI and then calls `addProjectLocation`. The remembered location is persisted per `(hostId, projectId)` with the same settings mechanism the launcher already uses for its last workdir (find it with `grep -n "lastWorkdir\|rememberedWorkdir\|launcherPrefs" apps/ui apps/shared -r`); a remembered path no longer in the project is ignored by `launchLocation` and overwritten on the next pick.
- When the catalog is empty (old broker), keep today's path picker exactly.
- A workspace-locked launcher (`workspaceWorkdir != null`, i.e. "+ → Chat") never consults projects — unchanged.
- Verify: helper tests pass; `./gradlew :ui:compileKotlinDesktop` succeeds.
- Commit: `feat(projects): launcher picks a project, then one of its locations`

### Task 11: Project settings sheet

**Files:** Create `apps/ui/src/commonMain/kotlin/dev/supermux/ui/session/ProjectSettingsSheet.kt`; wire from Task 9's header menu and from a "New project" action in the sidebar overflow.

Sheet contents: name field (save → `renameProject`), image picker (reuse the file picker the chat composer uses for attachments; send bytes with their mime → `setProjectImage`; "Remove" → `clearProjectImage`), locations list (each with "Move to…" → a project chooser → `moveProjectLocation`), "Add location" (path entry validated by `POST /paths/validate` via the existing launcher helper → `addProjectLocation`; a `ProjectLocationConflict` shows "Already in <project name>" with a "Move here" button that calls `moveProjectLocation`). There is no delete (out of scope per spec).

- Verify: `./gradlew :ui:compileKotlinDesktop` and the web target compile; manual run via the `run` skill if available.
- Commit: `feat(projects): project settings sheet (name, image, locations)`

### Task 12: End-to-end verification

- [ ] `bun test` (whole broker suite) and `bunx tsc --noEmit -p .` — clean.
- [ ] `cd apps && ./gradlew :shared:allTests :ui:compileKotlinDesktop` — clean.
- [ ] Against a copy of the real DB (`cp ~/.mux/state/db.sqlite3` into the scratchpad; open it with `openDb` + `runMigrations` + `ProjectService.reconcile`): project count equals distinct effective paths, no worktree-dir projects, workspace/session/view row counts unchanged.
- [ ] Preview the broker (`mux:preview-broker` skill) and confirm in the PWA: grouping unchanged at first glance, rename/image/reorder propagate to a second client, moving a location regroups active and archived rows without restarting agents.
- [ ] Append a dated entry to `~/.mux/domains/claudemux.md` describing the project model and the `projects_changed` frame.

---

## Self-review notes

- Spec coverage: data model (T1,T3), normalization/exact match (T2,T4), read-only resolution (T4 test 1), transactional idempotent registration (T4,T5), reconciliation/backfill incl. legacy sessions and bare worktrees (T4), operations incl. conflict + move + empty projects + ordering (T4,T6,T9,T11), images durable and outside upload cleanup (T4,T6,T11), compat `GET /projects` (T6), sync + archived refresh via membership (T6,T7), host-qualified keys (T7,T8), launcher location choice + memory (T10), no delete (T11), verification (T12).
- Known deviation: registration rollback is atomic with the workspace insert, not before agent launch (Task 5 note).
