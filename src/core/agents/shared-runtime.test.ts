import { test, expect, beforeEach, afterEach } from "bun:test"
import {
  mkdirSync,
  mkdtempSync,
  writeFileSync,
  readFileSync,
  rmSync,
  lstatSync,
  readlinkSync,
  symlinkSync,
  existsSync,
} from "fs"
import { tmpdir } from "os"
import { join } from "path"
import {
  ensureSharedCursorRuntime,
  gcOrphanAgentHomes,
  reclaimCursorHomes,
  sharedCursorDir,
} from "./shared-runtime"

let root: string
let stateDir: string
let shared: string

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), "amux-shared-"))
  stateDir = join(root, "state")
  shared = sharedCursorDir(stateDir)
})

afterEach(() => {
  rmSync(root, { recursive: true, force: true })
})

const RUNTIME_REL = ".local/share/cursor-agent"

function home(name: string): string {
  const h = join(stateDir, "agents", "cursor", name)
  mkdirSync(h, { recursive: true })
  return h
}

function seedRealRuntime(payload = "BUILD-A"): string {
  const ur = join(root, "user-runtime")
  mkdirSync(join(ur, "versions"), { recursive: true })
  writeFileSync(join(ur, "versions", "build"), payload)
  return ur
}

test("fresh home: seeds shared from user runtime and creates a symlink", () => {
  const userRuntime = seedRealRuntime("BUILD-A")
  const h = home("s1")

  ensureSharedCursorRuntime(h, { sharedDir: shared, userRuntime })

  const link = join(h, RUNTIME_REL)
  expect(lstatSync(link).isSymbolicLink()).toBe(true)
  expect(readlinkSync(link)).toBe(shared)
  // shared got populated from the user runtime
  expect(readFileSync(join(shared, "versions", "build"), "utf8")).toBe("BUILD-A")
})

test("existing real runtime dir is migrated into the (empty) shared copy", () => {
  const h = home("s1")
  const link = join(h, RUNTIME_REL)
  mkdirSync(join(link, "versions"), { recursive: true })
  writeFileSync(join(link, "versions", "build"), "FROM-HOME")

  ensureSharedCursorRuntime(h, { sharedDir: shared, userRuntime: join(root, "nope") })

  expect(lstatSync(link).isSymbolicLink()).toBe(true)
  expect(readFileSync(join(shared, "versions", "build"), "utf8")).toBe("FROM-HOME")
})

test("second home collapses to a symlink without re-copying", () => {
  const userRuntime = seedRealRuntime("BUILD-A")
  const h1 = home("s1")
  ensureSharedCursorRuntime(h1, { sharedDir: shared, userRuntime })

  // Second home arrives with its own bootstrapped real dir.
  const h2 = home("s2")
  const link2 = join(h2, RUNTIME_REL)
  mkdirSync(join(link2, "versions"), { recursive: true })
  writeFileSync(join(link2, "versions", "build"), "STALE-COPY")

  ensureSharedCursorRuntime(h2, { sharedDir: shared, userRuntime })

  expect(lstatSync(link2).isSymbolicLink()).toBe(true)
  expect(readlinkSync(link2)).toBe(shared)
  // shared still holds the first seed, not the second home's stale copy
  expect(readFileSync(join(shared, "versions", "build"), "utf8")).toBe("BUILD-A")
})

test("already-correct symlink is a no-op", () => {
  const userRuntime = seedRealRuntime()
  const h = home("s1")
  ensureSharedCursorRuntime(h, { sharedDir: shared, userRuntime })
  const link = join(h, RUNTIME_REL)
  const before = readlinkSync(link)

  ensureSharedCursorRuntime(h, { sharedDir: shared, userRuntime })

  expect(readlinkSync(link)).toBe(before)
})

test("foreign/broken symlink is repaired to point at shared", () => {
  const userRuntime = seedRealRuntime()
  const h = home("s1")
  const link = join(h, RUNTIME_REL)
  mkdirSync(join(h, ".local", "share"), { recursive: true })
  symlinkSync(join(root, "does-not-exist"), link)

  ensureSharedCursorRuntime(h, { sharedDir: shared, userRuntime })

  expect(readlinkSync(link)).toBe(shared)
})

test("no shared, no user runtime: leaves an empty shared dir + symlink", () => {
  const h = home("s1")
  ensureSharedCursorRuntime(h, { sharedDir: shared, userRuntime: join(root, "nope") })
  const link = join(h, RUNTIME_REL)
  expect(lstatSync(link).isSymbolicLink()).toBe(true)
  expect(existsSync(shared)).toBe(true)
})

test("reclaimCursorHomes collapses every cursor home (incl. archived) to a symlink", () => {
  // Two homes, each with its own real 'bootstrapped' runtime dir.
  for (const n of ["a", "b"]) {
    const link = join(home(n), RUNTIME_REL)
    mkdirSync(join(link, "versions"), { recursive: true })
    writeFileSync(join(link, "versions", "build"), n === "a" ? "BUILD-A" : "STALE")
  }

  // No user runtime → forces migration from a session home (not a 355 MB copy).
  const { linked } = reclaimCursorHomes({ stateDir, userRuntime: join(root, "nope") })

  expect(linked.length).toBe(2)
  for (const n of ["a", "b"]) {
    const link = join(stateDir, "agents", "cursor", n, RUNTIME_REL)
    expect(lstatSync(link).isSymbolicLink()).toBe(true)
    expect(readlinkSync(link)).toBe(shared)
  }
  // shared was populated exactly once (from whichever home was migrated first);
  // the other home's real dir was discarded in favour of the symlink.
  const seeded = readFileSync(join(shared, "versions", "build"), "utf8")
  expect(["BUILD-A", "STALE"]).toContain(seeded)
})

test("gc removes only homes with no active session, keeps shared", () => {
  const userRuntime = seedRealRuntime()
  const active = home("alive")
  const dead1 = home("dead1")
  home("dead2")
  const codexDead = join(stateDir, "agents", "codex", "oldcodex")
  mkdirSync(codexDead, { recursive: true })
  ensureSharedCursorRuntime(active, { sharedDir: shared, userRuntime })

  const { removed } = gcOrphanAgentHomes(new Set([active]), { stateDir })

  expect(existsSync(active)).toBe(true)
  expect(existsSync(dead1)).toBe(false)
  expect(existsSync(codexDead)).toBe(false)
  expect(removed.length).toBe(3)
  // shared survives — only the orphan home's symlink was removed
  expect(existsSync(shared)).toBe(true)
})

test("gc dryRun reports orphan candidates but deletes nothing (startup safety)", () => {
  const keep = home("keep")
  const orphan1 = home("orphan1")
  const orphan2 = join(stateDir, "agents", "codex", "orphan2")
  mkdirSync(orphan2, { recursive: true })

  const { removed, candidates } = gcOrphanAgentHomes(new Set([keep]), { stateDir, dryRun: true })

  expect(removed.length).toBe(0)
  expect(candidates.sort()).toEqual([orphan2, orphan1].sort())
  // nothing was actually deleted
  expect(existsSync(orphan1)).toBe(true)
  expect(existsSync(orphan2)).toBe(true)
  expect(existsSync(keep)).toBe(true)
})
