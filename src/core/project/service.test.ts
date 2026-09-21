import { test, expect, afterEach } from "bun:test"
import { mkdtempSync, rmSync, existsSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { WorkspaceStore } from "../workspace/store"
import { ProjectStore } from "./store"
import { ProjectImages } from "./images"
import { ProjectService, ProjectConflictError, ProjectNotFoundError } from "./service"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

function make() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const imagesDir = join(mkdtempSync(join(tmpdir(), "project-svc-")), "project-images")
  dirs.push(join(imagesDir, ".."))
  const store = new ProjectStore(db)
  const images = new ProjectImages(imagesDir)
  const svc = new ProjectService(store, { home: "/h", managedWorktreesRoot: "/h/.mux/worktrees", images })
  return { db, store, images, svc }
}

function counts(db: ReturnType<typeof openDb>) {
  const p = (db.query("SELECT count(*) n FROM projects").get() as { n: number }).n
  const l = (db.query("SELECT count(*) n FROM project_locations").get() as { n: number }).n
  return { p, l }
}

test("resolve returns undefined for an unknown path and writes nothing", () => {
  const { db, svc } = make()
  const before = counts(db)
  expect(svc.resolve({ workdir: "/h/projects/app" })).toBeUndefined()
  expect(counts(db)).toEqual(before)
})

test("ensureLocation is idempotent: one project, one location, same id", () => {
  const { db, svc } = make()
  const first = svc.ensureLocation({ workdir: "/h/projects/app/" })!
  const second = svc.ensureLocation({ workdir: "/h/projects/app" })!
  expect(first.created).toBe(true)
  expect(second.created).toBe(false)
  expect(second.projectId).toBe(first.projectId)
  expect(counts(db)).toEqual({ p: 1, l: 1 })
  expect(svc.get(first.projectId)!.name).toBe("…/projects/app")
  expect(svc.resolve({ workdir: "/h/projects/app" })).toBe(first.projectId)
})

test("ensureLocation leaves a bare managed worktree unresolved", () => {
  const { db, svc } = make()
  expect(svc.ensureLocation({ workdir: "/h/.mux/worktrees/a/b" })).toBeUndefined()
  expect(counts(db)).toEqual({ p: 0, l: 0 })
})

test("ensureLocation rolls back the created project when the location insert fails (no orphan)", () => {
  const { db, store, svc } = make()
  const original = store.addLocation.bind(store)
  store.addLocation = () => { throw new Error("boom") }
  const before = counts(db)
  try {
    expect(() => svc.ensureLocation({ workdir: "/h/projects/app" })).toThrow("boom")
    expect(counts(db)).toEqual(before)
  } finally {
    store.addLocation = original
  }
})

test("a worktree workspace resolves to its repo_root project", () => {
  const { svc } = make()
  const { projectId } = svc.ensureLocation({ workdir: "/h/projects/app" })!
  expect(svc.resolve({ workdir: "/h/.mux/worktrees/a/b", repo_root: "/h/projects/app" })).toBe(projectId)
})

test("a nested path does not resolve to its parent location", () => {
  const { svc } = make()
  svc.ensureLocation({ workdir: "/h/projects/app" })
  expect(svc.resolve({ workdir: "/h/projects/app/sub" })).toBeUndefined()
})

test("addLocation of a path owned elsewhere conflicts; on the owner it is idempotent", () => {
  const { svc } = make()
  const a = svc.create("A")
  const b = svc.create("B")
  svc.addLocation(a.id, "/h/x")
  let err: unknown
  try { svc.addLocation(b.id, "/h/x/") } catch (e) { err = e }
  expect(err).toBeInstanceOf(ProjectConflictError)
  expect((err as ProjectConflictError).projectId).toBe(a.id)
  const again = svc.addLocation(a.id, "/h//x")
  expect(again.locations.map((l) => l.path)).toEqual(["/h/x"])
})

test("addLocation validates the path and the project", () => {
  const { svc } = make()
  const a = svc.create("A")
  expect(() => svc.addLocation(a.id, "rel/x")).toThrow("absolute path required")
  expect(() => svc.addLocation("nope", "/h/x")).toThrow(ProjectNotFoundError)
})

test("addLocation no longer trims — normalizeLocationPath is the only transform", () => {
  const { svc } = make()
  const a = svc.create("A")
  expect(() => svc.addLocation(a.id, " /h/x")).toThrow("absolute path required")
})

test("moveLocation reassigns resolution and keeps the source project", () => {
  const { svc } = make()
  const a = svc.create("A")
  const b = svc.create("B")
  const loc = svc.addLocation(a.id, "/h/x").locations[0]!
  const dto = svc.moveLocation(loc.id, b.id)
  expect(dto.id).toBe(b.id)
  expect(dto.locations).toEqual([{ id: loc.id, path: "/h/x" }])
  expect(svc.resolve({ workdir: "/h/x" })).toBe(b.id)
  expect(svc.get(a.id)).toBeDefined()
  expect(svc.get(a.id)!.locations).toEqual([])
  expect(() => svc.moveLocation("nope", b.id)).toThrow(ProjectNotFoundError)
  expect(() => svc.moveLocation(loc.id, "nope")).toThrow(ProjectNotFoundError)
})

test("reconcile backfills distinct effective locations in label order, once", () => {
  const { db, svc } = make()
  const ws = new WorkspaceStore(db)
  // Paths are fake: reconcile must never touch the filesystem.
  ws.create({ name: "b", workdir: "/h/b" })
  ws.create({ name: "a", workdir: "/h/a" })
  ws.create({ name: "a-wt", workdir: "/h/.mux/worktrees/x/y", repo_root: "/h/a" })
  ws.create({ name: "bare", workdir: "/h/.mux/worktrees/z/w" })
  db.run(
    `INSERT INTO sessions (id, name, status, agent, workdir, created_at)
     VALUES ('s1', 'legacy', 'archived', 'claude', '/h/c', '2026-01-01T00:00:00.000Z')`,
  )

  const created = svc.reconcile(db)
  expect(created).toHaveLength(3)
  expect(svc.list().map((p) => [p.name, p.sort_order, p.locations.map((l) => l.path)])).toEqual([
    ["~/a", 0, ["/h/a"]],
    ["~/b", 1, ["/h/b"]],
    ["~/c", 2, ["/h/c"]],
  ])
  expect(svc.reconcile(db)).toEqual([])
  expect(counts(db)).toEqual({ p: 3, l: 3 })
})

test("reconcile appends after existing projects and skips registered paths", () => {
  const { db, svc } = make()
  const existing = svc.create("Mine")
  svc.addLocation(existing.id, "/h/a")
  new WorkspaceStore(db).create({ name: "a", workdir: "/h/a" })
  new WorkspaceStore(db).create({ name: "d", workdir: "/h/d" })
  const created = svc.reconcile(db)
  expect(created).toHaveLength(1)
  expect(svc.get(created[0]!)).toMatchObject({ name: "~/d", sort_order: 1 })
})

test("create and rename validate names and ids", () => {
  const { svc } = make()
  expect(() => svc.create("  ")).toThrow("name required")
  const p = svc.create("  Trim me ")
  expect(p.name).toBe("Trim me")
  expect(p.locations).toEqual([])
  expect(() => svc.rename("nope", "X")).toThrow(ProjectNotFoundError)
  expect(() => svc.rename(p.id, " ")).toThrow("name required")
  expect(svc.rename(p.id, " New ").name).toBe("New")
})

test("create appends to the order; reorder rewrites it", () => {
  const { svc } = make()
  const a = svc.create("A")
  const b = svc.create("B")
  expect([a.sort_order, b.sort_order]).toEqual([0, 1])
  svc.reorder([b.id, a.id])
  expect(svc.list().map((p) => p.id)).toEqual([b.id, a.id])
})

test("reorder rejects an unknown id", () => {
  const { svc } = make()
  const a = svc.create("A")
  expect(() => svc.reorder([a.id, "nope"])).toThrow("unknown project id: nope")
})

test("reorder rejects a duplicate id", () => {
  const { svc } = make()
  const a = svc.create("A")
  const b = svc.create("B")
  expect(() => svc.reorder([a.id, b.id, a.id])).toThrow(`duplicate project id: ${a.id}`)
})

test("reorder appends unlisted projects after the listed ones, keeping their relative order, as a dense permutation", () => {
  const { svc } = make()
  const a = svc.create("A")
  const b = svc.create("B")
  const c = svc.create("C")
  svc.reorder([c.id])
  expect(svc.list().map((p) => [p.id, p.sort_order])).toEqual([
    [c.id, 0],
    [a.id, 1],
    [b.id, 2],
  ])
})

test("a failed reorder leaves sort_order untouched", () => {
  const { svc } = make()
  const a = svc.create("A")
  const b = svc.create("B")
  expect(() => svc.reorder([b.id, "nope"])).toThrow()
  expect(svc.list().map((p) => p.id)).toEqual([a.id, b.id])
})

test("setImage stores a file, replaces the old one, and clearImage removes it", () => {
  const { svc } = make()
  const p = svc.create("A")
  const first = svc.setImage(p.id, new Uint8Array([1]), "image/png")
  const f1 = svc.imageFile(p.id)!
  expect(first.image_id).toMatch(/\.png$/)
  expect(f1.mime).toBe("image/png")
  expect(existsSync(f1.path)).toBe(true)

  const second = svc.setImage(p.id, new Uint8Array([2]), "image/webp")
  const f2 = svc.imageFile(p.id)!
  expect(second.image_id).not.toBe(first.image_id)
  expect(f2.mime).toBe("image/webp")
  expect(existsSync(f1.path)).toBe(false)
  expect(existsSync(f2.path)).toBe(true)

  const cleared = svc.clearImage(p.id)
  expect(cleared.image_id).toBeUndefined()
  expect(svc.imageFile(p.id)).toBeUndefined()
  expect(existsSync(f2.path)).toBe(false)
  expect(() => svc.setImage("nope", new Uint8Array([1]), "image/png")).toThrow(ProjectNotFoundError)
})

test("membership maps workspace ids to project ids and omits unresolved rows", () => {
  const { svc } = make()
  const { projectId } = svc.ensureLocation({ workdir: "/h/app" })!
  expect(svc.membership([
    { id: "w1", workdir: "/h/app" },
    { id: "w2", workdir: "/h/.mux/worktrees/a/b", repo_root: "/h/app" },
    { id: "w3", workdir: "/h/other" },
    { id: "w4", workdir: "/h/.mux/worktrees/c/d", repo_root: null },
  ])).toEqual({ w1: projectId, w2: projectId })
})
