# Host FileSystemService (sub-project A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a host-wide, path-addressed, subscribable `FileSystemService` on the broker (non-blocking listings, git info, search, file ops, folder subscriptions over WebSocket) and its per-host counterpart in the Kotlin app, with today's `/workspaces/:id/fs*` and `/sessions/:id/fs*` routes rebuilt on top of it.

**Architecture:** New broker module `src/core/fs/` of small single-purpose files (paths, repo info, dir cache, file ops, search index, dir watchers, subscription registry) behind one `FileSystemService` facade. The web channel routes `fs_sub`/`fs_unsub` WebSocket frames and `/fs/*` HTTP routes into it; the legacy routes become wrappers. The app gets `dev.supermux.fs.FileSystemService` inside `HostStore` with ref-counted subscriptions and reconnect re-subscription.

**Tech Stack:** Bun + TypeScript (`bun test`), `node:fs/promises`, `gitAsync` from `src/core/git/exec.ts`; Kotlin Multiplatform (`apps/shared`), kotlinx.serialization, kotlinx.coroutines, atomicfu, Ktor MockEngine tests.

**Spec:** `docs/superpowers/specs/2026-09-27-host-filesystem-service-design.md`

**Conventions for every task**
- Worktree: run everything from the repository root of this worktree. Fresh worktrees have empty `node_modules`: run `bun install` once before the first `bun test`.
- Broker tests: `bun test <file>`. App tests: from `apps/`, `./gradlew :shared:jvmTest --tests '<FQN>'` (add `--max-workers=2` on this box; if Gradle is OOM-killed, rerun once, then report it).
- No `*Sync` fs calls and no `execSync` in `src/core/fs/`. Git goes through `gitAsync` (async `execFile`, timeout).
- Commit after each task with the message given. End every commit message with a blank line and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- `docs/` is in the local git exclude; plan/spec edits need `git add -f`.

---

## File map

**Broker (create)**

| File | Responsibility |
|---|---|
| `src/core/fs/types.ts` | Shared types: `FsEntry`, `DirSnapshot`, `SearchHit`, `FsOp`, `FsFrame`, `GitLetter`. |
| `src/core/fs/errors.ts` | `FsError` (code + message) and `toFsError(unknown)`. |
| `src/core/fs/paths.ts` | `normalizeAbsPath`, `realKey`. |
| `src/core/fs/pool.ts` | `mapLimit` (bounded-concurrency map). |
| `src/core/fs/repo-info.ts` | `RepoInfoCache`: repo lookup, ignored set, status, TTL + invalidate, `annotate`. |
| `src/core/fs/dir-cache.ts` | `DirCache`: async load, single-flight, compare, versions, truncation, pin + LRU. |
| `src/core/fs/file-ops.ts` | `readText`, `writeText`, `statEntry`, `applyOp` (incl. trash). |
| `src/core/fs/search-index.ts` | `fuzzyMatch`, `SearchIndex`, `SearchIndexes`. |
| `src/core/fs/dir-watchers.ts` | `DirWatchers`: non-recursive watch per folder, debounce, polling fallback. |
| `src/core/fs/subscriptions.ts` | `SubscriptionRegistry<S>`: ref-counts, grace teardown, per-socket cap. |
| `src/core/fs/file-system-service.ts` | `FileSystemService<S>` facade. |
| `src/core/fs/legacy.ts` | Workdir-scoped wrappers for the old routes (containment + old response shapes). |
| `src/core/fs/*.test.ts` | One test file per module. |
| `src/channels/web/fs-routes.test.ts` | Live WebChannel tests for `/fs/*` and `fs_sub`. |

**Broker (modify)**
- `src/channels/web/index.ts`: legacy routes call `legacy.ts`; new `/fs/*` routes; `fs_sub`/`fs_unsub` frames; `dropSocket` on close; `editor_open` leak fix.
- `src/core/editor/fs-service.ts`: keep only `DiffEntry`, `parseDiff` and their helpers; remove `FsService`.
- `src/channels/web/workspace-fs.test.ts`: move to `legacy.ts`.

**App (create/modify)**
- Create `apps/shared/src/commonMain/kotlin/dev/supermux/fs/FsModels.kt`, `.../fs/FileSystemService.kt`.
- Modify `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt` (FsEntry fields + `/fs/*` calls), `.../proto/Frames.kt` (frames), `.../state/HostStore.kt` (instance + reducer).
- Create `apps/shared/src/jvmTest/resources/frames/fs_dir.json`, `fs_gone.json`, `fs_err.json`; modify `apps/shared/src/jvmTest/kotlin/dev/supermux/proto/ContractTest.kt`.
- Create `apps/shared/src/jvmTest/kotlin/dev/supermux/fs/FileSystemServiceTest.kt`.
- Create `apps/ui/src/commonMain/kotlin/dev/supermux/ui/fs/CollectDir.kt`.

---

# Part A1: broker core (no protocol change)

### Task 1: Types, errors, paths, pool

**Files:**
- Create: `src/core/fs/types.ts`, `src/core/fs/errors.ts`, `src/core/fs/paths.ts`, `src/core/fs/pool.ts`
- Test: `src/core/fs/paths.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// src/core/fs/paths.test.ts
import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, symlinkSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { normalizeAbsPath, realKey } from "./paths"
import { FsError } from "./errors"
import { mapLimit } from "./pool"

test("normalizeAbsPath resolves dot segments and strips the trailing slash", () => {
  expect(normalizeAbsPath("/a/b/../c/./d/")).toBe("/a/c/d")
  expect(normalizeAbsPath("/")).toBe("/")
})

test("normalizeAbsPath rejects relative, empty and NUL paths with EINVAL", () => {
  for (const bad of ["", "a/b", "./x", "/a\0b"]) {
    let err: unknown
    try { normalizeAbsPath(bad) } catch (e) { err = e }
    expect(err).toBeInstanceOf(FsError)
    expect((err as FsError).code).toBe("EINVAL")
  }
})

test("realKey follows symlinks", async () => {
  const root = mkdtempSync(join(tmpdir(), "fs-paths-"))
  mkdirSync(join(root, "real"))
  symlinkSync(join(root, "real"), join(root, "link"))
  expect(await realKey(join(root, "link"))).toBe(await realKey(join(root, "real")))
})

test("realKey maps a missing path to FsError ENOENT", async () => {
  await expect(realKey("/definitely/not/here")).rejects.toMatchObject({ code: "ENOENT" })
})

test("mapLimit keeps order and never exceeds the limit", async () => {
  let active = 0
  let peak = 0
  const out = await mapLimit([1, 2, 3, 4, 5, 6, 7, 8], 3, async (n) => {
    active++; peak = Math.max(peak, active)
    await new Promise((r) => setTimeout(r, 5))
    active--
    return n * 2
  })
  expect(out).toEqual([2, 4, 6, 8, 10, 12, 14, 16])
  expect(peak).toBeLessThanOrEqual(3)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/fs/paths.test.ts`
Expected: FAIL (cannot resolve `./paths`).

- [ ] **Step 3: Write the implementation**

```ts
// src/core/fs/types.ts
export type GitLetter = "M" | "A" | "D" | "R" | "?" | "U" | "*"

export interface FsEntry {
  name: string
  type: "file" | "dir" | "symlink"
  size?: number
  mtime?: number
  ignored: boolean
  git?: GitLetter
  target?: "file" | "dir"
}

export interface DirSnapshot {
  path: string
  real: string
  version: string
  entries: FsEntry[]
  truncated?: { total: number }
}

export interface SearchHit {
  path: string
  name: string
  type: "file" | "dir"
  score: number
  hits: number[]
}

export type FsOp =
  | { op: "rename" | "move"; path: string; to: string }
  | { op: "mkdir" | "touch" | "delete"; path: string }

export type FsFrame =
  | { type: "fs_dir"; path: string; version: string; entries: FsEntry[]; truncated?: { total: number } }
  | { type: "fs_dir"; path: string; version: string; unchanged: true }
  | { type: "fs_gone"; path: string }
  | { type: "fs_err"; path: string; code: string; message: string }
```

```ts
// src/core/fs/errors.ts
const KNOWN = new Set(["ENOENT", "EACCES", "EPERM", "ENOTDIR", "EISDIR", "EEXIST", "EINVAL", "ENOTEMPTY", "EXDEV"])

export class FsError extends Error {
  constructor(readonly code: string, message: string) {
    super(message)
    this.name = "FsError"
  }
}

/** Normalise any thrown value into an FsError with a stable code. */
export function toFsError(e: unknown): FsError {
  if (e instanceof FsError) return e
  const code = (e as { code?: unknown })?.code
  const message = (e as { message?: unknown })?.message
  const c = typeof code === "string" && KNOWN.has(code) ? (code === "EPERM" ? "EACCES" : code) : "EIO"
  return new FsError(c, typeof message === "string" ? message : String(e))
}
```

```ts
// src/core/fs/paths.ts
import { isAbsolute, resolve } from "path"
import { realpath } from "fs/promises"
import { FsError, toFsError } from "./errors"

/** Absolute, normalised path: no `.`/`..`, no trailing slash (except "/"). Throws FsError EINVAL. */
export function normalizeAbsPath(p: string): string {
  if (typeof p !== "string" || p.length === 0) throw new FsError("EINVAL", "empty path")
  if (p.includes("\0")) throw new FsError("EINVAL", "path contains a NUL byte")
  if (!isAbsolute(p)) throw new FsError("EINVAL", `path must be absolute: ${p}`)
  return resolve(p)
}

/** realpath(), with errors mapped to FsError. The sharing key for watchers and caches. */
export async function realKey(p: string): Promise<string> {
  try {
    return await realpath(p)
  } catch (e) {
    throw toFsError(e)
  }
}
```

```ts
// src/core/fs/pool.ts
/** Map with at most `limit` promises in flight; results keep input order. */
export async function mapLimit<T, R>(items: readonly T[], limit: number, fn: (item: T, i: number) => Promise<R>): Promise<R[]> {
  const out = new Array<R>(items.length)
  let next = 0
  const worker = async () => {
    while (true) {
      const i = next++
      if (i >= items.length) return
      out[i] = await fn(items[i]!, i)
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker))
  return out
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/fs/paths.test.ts`
Expected: 5 pass.

- [ ] **Step 5: Commit**

```bash
git add src/core/fs/types.ts src/core/fs/errors.ts src/core/fs/paths.ts src/core/fs/pool.ts src/core/fs/paths.test.ts
git commit -m "feat(fs): path normalisation, error codes and a bounded map for the host fs service"
```

---

### Task 2: RepoInfoCache (repo lookup, ignored set, status)

**Files:**
- Create: `src/core/fs/repo-info.ts`
- Test: `src/core/fs/repo-info.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// src/core/fs/repo-info.test.ts
import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, realpathSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { execFileSync } from "child_process"
import { RepoInfoCache, parseStatusZ } from "./repo-info"
import type { FsEntry } from "./types"

function git(cwd: string, ...args: string[]) {
  execFileSync("git", args, { cwd, stdio: "pipe" }) // test-only setup, sync is fine here
}

function repoFixture() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "fs-repo-")))
  git(root, "init", "-q")
  git(root, "config", "user.email", "t@t")
  git(root, "config", "user.name", "t")
  writeFileSync(join(root, ".gitignore"), "node_modules/\n*.log\n")
  mkdirSync(join(root, "src"))
  writeFileSync(join(root, "src", "a.ts"), "a\n")
  git(root, "add", ".")
  git(root, "commit", "-q", "-m", "init")
  mkdirSync(join(root, "node_modules", "pkg"), { recursive: true })
  writeFileSync(join(root, "node_modules", "pkg", "i.js"), "x")
  writeFileSync(join(root, "debug.log"), "x")
  writeFileSync(join(root, "src", "a.ts"), "changed\n")
  writeFileSync(join(root, "src", "new.ts"), "n\n")
  return root
}

const e = (name: string, type: FsEntry["type"] = "file"): FsEntry => ({ name, type, ignored: false })

test("repoFor finds the repository root from a nested folder, and null outside git", async () => {
  const root = repoFixture()
  const c = new RepoInfoCache()
  expect(await c.repoFor(join(root, "src"))).toBe(root)
  const plain = realpathSync(mkdtempSync(join(tmpdir(), "fs-plain-")))
  expect(await c.repoFor(plain)).toBeNull()
})

test("annotate marks ignored entries and git letters, and dirty folders get *", async () => {
  const root = repoFixture()
  const c = new RepoInfoCache()
  const top = [e("node_modules", "dir"), e("debug.log"), e("src", "dir"), e(".gitignore")]
  await c.annotate(root, top)
  expect(top.find((x) => x.name === "node_modules")!.ignored).toBe(true)
  expect(top.find((x) => x.name === "debug.log")!.ignored).toBe(true)
  expect(top.find((x) => x.name === "src")!.git).toBe("*")
  expect(top.find((x) => x.name === ".gitignore")!.git).toBeUndefined()

  const src = [e("a.ts"), e("new.ts")]
  await c.annotate(join(root, "src"), src)
  expect(src[0]!.git).toBe("M")
  expect(src[1]!.git).toBe("?")
})

test("entries inside an ignored folder are ignored too", async () => {
  const root = repoFixture()
  const c = new RepoInfoCache()
  const inside = [e("i.js")]
  await c.annotate(join(root, "node_modules", "pkg"), inside)
  expect(inside[0]!.ignored).toBe(true)
})

test("invalidate forces a fresh git read", async () => {
  const root = repoFixture()
  const c = new RepoInfoCache({ ttlMs: 60_000 })
  const before = [e("b.ts")]
  await c.annotate(join(root, "src"), before)
  expect(before[0]!.git).toBeUndefined()
  writeFileSync(join(root, "src", "b.ts"), "b\n")
  const stale = [e("b.ts")]
  await c.annotate(join(root, "src"), stale)
  expect(stale[0]!.git).toBeUndefined() // still cached
  c.invalidate(root)
  const fresh = [e("b.ts")]
  await c.annotate(join(root, "src"), fresh)
  expect(fresh[0]!.git).toBe("?")
})

test("outside a repository nothing is annotated", async () => {
  const plain = realpathSync(mkdtempSync(join(tmpdir(), "fs-plain-")))
  const list = [e("x")]
  await new RepoInfoCache().annotate(plain, list)
  expect(list[0]).toEqual({ name: "x", type: "file", ignored: false })
})

test("parseStatusZ reads ordinary, renamed, unmerged and untracked records", () => {
  const z = [
    "1 .M N... 100644 100644 100644 aaa bbb src/a.ts",
    "1 A. N... 000000 100644 100644 000 bbb src/added.ts",
    "1 D. N... 100644 000000 000000 aaa 000 gone.ts",
    "2 R. N... 100644 100644 100644 aaa bbb R100 src/new-name.ts", "src/old-name.ts",
    "u UU N... 100644 100644 100644 100644 a b c conflict.ts",
    "? untracked.txt",
    "",
  ].join("\0")
  const m = parseStatusZ(z)
  expect(m.get("src/a.ts")).toBe("M")
  expect(m.get("src/added.ts")).toBe("A")
  expect(m.get("gone.ts")).toBe("D")
  expect(m.get("src/new-name.ts")).toBe("R")
  expect(m.has("src/old-name.ts")).toBe(false)
  expect(m.get("conflict.ts")).toBe("U")
  expect(m.get("untracked.txt")).toBe("?")
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/fs/repo-info.test.ts`
Expected: FAIL (cannot resolve `./repo-info`).

- [ ] **Step 3: Write the implementation**

```ts
// src/core/fs/repo-info.ts
import { stat } from "fs/promises"
import { dirname, join, relative, sep } from "path"
import { gitAsync } from "../git/exec"
import type { FsEntry, GitLetter } from "./types"

export interface RepoState {
  root: string
  ignored: Set<string>          // repo-relative, no trailing slash
  status: Map<string, GitLetter> // repo-relative file → letter
  dirty: Set<string>            // repo-relative folders with changes inside ("" = root)
  loadedAt: number
}

interface Slot { state?: RepoState; loading?: Promise<RepoState>; stale: boolean }

const GIT_TIMEOUT_MS = 5_000

/** Parse `git status --porcelain=v2 -z --untracked-files=all` into path → letter. */
export function parseStatusZ(out: string): Map<string, GitLetter> {
  const m = new Map<string, GitLetter>()
  const recs = out.split("\0")
  for (let i = 0; i < recs.length; i++) {
    const r = recs[i]!
    if (!r) continue
    const kind = r[0]
    if (kind === "?") { m.set(r.slice(2), "?"); continue }
    if (kind === "!") continue
    if (kind === "1") {
      const parts = r.split(" ")
      const xy = parts[1]!
      m.set(parts.slice(8).join(" "), xy.includes("A") ? "A" : xy.includes("D") ? "D" : "M")
      continue
    }
    if (kind === "2") {
      const parts = r.split(" ")
      m.set(parts.slice(9).join(" "), "R")
      i++ // the next record is the original path
      continue
    }
    if (kind === "u") {
      const parts = r.split(" ")
      m.set(parts.slice(10).join(" "), "U")
    }
  }
  return m
}

function ancestorsOf(rel: string): string[] {
  const out: string[] = [""]
  const parts = rel.split("/")
  for (let i = 1; i < parts.length; i++) out.push(parts.slice(0, i).join("/"))
  return out
}

/**
 * Git facts per repository, read with async git and cached. A state younger than `ttlMs` is reused;
 * `invalidate(root)` forces the next read to go back to git (the watchers call it in A2).
 */
export class RepoInfoCache {
  private readonly slots = new Map<string, Slot>()
  private readonly rootOf = new Map<string, string | null>()
  private readonly ttlMs: number

  constructor(opts: { ttlMs?: number } = {}) {
    this.ttlMs = opts.ttlMs ?? 2_000
  }

  /** Nearest folder at or above `dirReal` that contains a `.git` entry (folder or file), else null. */
  async repoFor(dirReal: string): Promise<string | null> {
    const cached = this.rootOf.get(dirReal)
    if (cached !== undefined) return cached
    let d = dirReal
    let found: string | null = null
    while (true) {
      try {
        await stat(join(d, ".git"))
        found = d
        break
      } catch {
        const parent = dirname(d)
        if (parent === d) break
        d = parent
      }
    }
    this.rootOf.set(dirReal, found)
    return found
  }

  /** Cached repo root for a folder if known (no I/O). */
  knownRepoFor(dirReal: string): string | null | undefined {
    return this.rootOf.get(dirReal)
  }

  invalidate(root: string): void {
    const s = this.slots.get(root)
    if (s) s.stale = true
  }

  async state(root: string): Promise<RepoState> {
    let slot = this.slots.get(root)
    if (!slot) { slot = { stale: true }; this.slots.set(root, slot) }
    const fresh = slot.state && !slot.stale && Date.now() - slot.state.loadedAt < this.ttlMs
    if (fresh) return slot.state!
    if (slot.loading) return slot.loading
    const s = slot
    s.stale = false
    s.loading = this.load(root).then(
      (st) => { s.state = st; s.loading = undefined; return st },
      (err) => { s.loading = undefined; s.stale = true; throw err },
    )
    return s.loading
  }

  private async load(root: string): Promise<RepoState> {
    const [ign, stat] = await Promise.all([
      gitAsync(root, ["ls-files", "--others", "--ignored", "--exclude-standard", "--directory", "-z"], { timeoutMs: GIT_TIMEOUT_MS, trim: false }).catch(() => ""),
      gitAsync(root, ["status", "--porcelain=v2", "-z", "--untracked-files=all"], { timeoutMs: GIT_TIMEOUT_MS, trim: false }).catch(() => ""),
    ])
    const ignored = new Set<string>()
    for (const p of ign.split("\0")) if (p) ignored.add(p.endsWith("/") ? p.slice(0, -1) : p)
    const status = parseStatusZ(stat)
    const dirty = new Set<string>()
    for (const p of status.keys()) for (const a of ancestorsOf(p)) dirty.add(a)
    return { root, ignored, status, dirty, loadedAt: Date.now() }
  }

  /** Fill `ignored` and `git` on entries of the folder `dirReal`, in place. Never throws. */
  async annotate(dirReal: string, entries: FsEntry[]): Promise<void> {
    const root = await this.repoFor(dirReal)
    if (!root) return
    let st: RepoState
    try { st = await this.state(root) } catch { return }
    const base = relative(root, dirReal).split(sep).join("/")
    const baseIgnored = base !== "" && isIgnored(st.ignored, base)
    for (const e of entries) {
      const rel = base ? `${base}/${e.name}` : e.name
      if (rel === ".git" || rel.startsWith(".git/")) continue
      e.ignored = baseIgnored || st.ignored.has(rel)
      const isDir = e.type === "dir" || e.target === "dir"
      const letter = st.status.get(rel)
      if (letter) e.git = letter
      else if (isDir && st.dirty.has(rel)) e.git = "*"
    }
  }
}

function isIgnored(set: Set<string>, rel: string): boolean {
  if (set.has(rel)) return true
  const parts = rel.split("/")
  for (let i = 1; i < parts.length; i++) if (set.has(parts.slice(0, i).join("/"))) return true
  return false
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/fs/repo-info.test.ts`
Expected: 6 pass.

- [ ] **Step 5: Commit**

```bash
git add src/core/fs/repo-info.ts src/core/fs/repo-info.test.ts
git commit -m "feat(fs): per-repository git info (ignored set, status) read with async git"
```

---

### Task 3: DirCache

**Files:**
- Create: `src/core/fs/dir-cache.ts`
- Test: `src/core/fs/dir-cache.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// src/core/fs/dir-cache.test.ts
import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, symlinkSync, realpathSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { DirCache } from "./dir-cache"
import { RepoInfoCache } from "./repo-info"

function fixture() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "fs-cache-")))
  mkdirSync(join(root, "b-dir"))
  mkdirSync(join(root, "A-dir"))
  writeFileSync(join(root, "file10.txt"), "1234")
  writeFileSync(join(root, "file2.txt"), "12")
  symlinkSync(join(root, "A-dir"), join(root, "link-to-dir"))
  symlinkSync(join(root, "nope"), join(root, "broken"))
  return root
}

const cache = (opts: Partial<ConstructorParameters<typeof DirCache>[0]> = {}) =>
  new DirCache({ repo: new RepoInfoCache(), bootId: "b1", ...opts })

test("load lists dirs first, natural order, with sizes and symlink targets", async () => {
  const root = fixture()
  const snap = (await cache().load(root)).snap
  expect(snap.entries.map((e) => e.name)).toEqual(["A-dir", "b-dir", "link-to-dir", "broken", "file2.txt", "file10.txt"])
  const byName = Object.fromEntries(snap.entries.map((e) => [e.name, e]))
  expect(byName["file10.txt"]!.size).toBe(4)
  expect(byName["link-to-dir"]).toMatchObject({ type: "symlink", target: "dir" })
  expect(byName["broken"]!.type).toBe("symlink")
  expect(byName["broken"]!.target).toBeUndefined()
  expect(snap.version).toBe("b1:1")
})

test("concurrent loads of one folder share a single read", async () => {
  const root = fixture()
  const c = cache()
  const [a, b, d] = await Promise.all([c.load(root), c.load(root), c.load(root)])
  expect(a.snap).toBe(b.snap)
  expect(b.snap).toBe(d.snap)
  expect(c.readCount).toBe(1)
})

test("an unchanged reload keeps the version and reports changed=false", async () => {
  const root = fixture()
  const c = cache()
  const first = await c.load(root)
  const again = await c.load(root)
  expect(first.changed).toBe(true)
  expect(again.changed).toBe(false)
  expect(again.snap.version).toBe(first.snap.version)
  writeFileSync(join(root, "new.txt"), "n")
  const third = await c.load(root)
  expect(third.changed).toBe(true)
  expect(third.snap.version).toBe("b1:2")
})

test("large folders are truncated and report the total", async () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "fs-cache-big-")))
  for (let i = 0; i < 30; i++) writeFileSync(join(root, `f${i}`), "")
  const snap = (await cache({ maxEntries: 10 }).load(root)).snap
  expect(snap.entries).toHaveLength(10)
  expect(snap.truncated).toEqual({ total: 30 })
})

test("loading a missing folder rejects with ENOENT and forgets it", async () => {
  const root = fixture()
  const c = cache()
  await c.load(join(root, "b-dir"))
  rmSync(join(root, "b-dir"), { recursive: true })
  await expect(c.load(join(root, "b-dir"))).rejects.toMatchObject({ code: "ENOENT" })
  expect(c.get(join(root, "b-dir"))).toBeUndefined()
})

test("unpinned folders are evicted least-recently-used; pinned ones stay", async () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "fs-cache-lru-")))
  for (const n of ["a", "b", "c"]) mkdirSync(join(root, n))
  const c = cache({ maxDirs: 2 })
  c.pin(join(root, "a"))
  await c.load(join(root, "a"))
  await c.load(join(root, "b"))
  await c.load(join(root, "c"))
  expect(c.get(join(root, "a"))).toBeDefined()
  expect(c.get(join(root, "b"))).toBeUndefined()
  expect(c.get(join(root, "c"))).toBeDefined()
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/fs/dir-cache.test.ts`
Expected: FAIL (cannot resolve `./dir-cache`).

- [ ] **Step 3: Write the implementation**

```ts
// src/core/fs/dir-cache.ts
import { readdir, lstat, stat } from "fs/promises"
import { join } from "path"
import { toFsError } from "./errors"
import { mapLimit } from "./pool"
import type { RepoInfoCache } from "./repo-info"
import type { FsEntry } from "./types"

export interface CachedDir {
  real: string
  version: string
  entries: FsEntry[]
  truncated?: { total: number }
}

export interface DirCacheOpts {
  repo: RepoInfoCache
  bootId: string
  maxEntries?: number
  maxDirs?: number
  statConcurrency?: number
}

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: "base" })
const isDirish = (e: FsEntry) => e.type === "dir" || e.target === "dir"

export function sortEntries(entries: FsEntry[]): FsEntry[] {
  return entries.sort((a, b) => {
    const da = isDirish(a), db = isDirish(b)
    if (da !== db) return da ? -1 : 1
    return collator.compare(a.name, b.name) || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)
  })
}

/** Folder listings keyed by real path. Loads are async, single-flight, compared, versioned. */
export class DirCache {
  private readonly dirs = new Map<string, CachedDir>() // insertion order = LRU order
  private readonly inflight = new Map<string, Promise<{ snap: CachedDir; changed: boolean }>>()
  private readonly pins = new Map<string, number>()
  private counter = 0
  /** Number of real directory reads performed (tests). */
  readCount = 0

  constructor(private readonly opts: DirCacheOpts) {}

  get(real: string): CachedDir | undefined {
    const d = this.dirs.get(real)
    if (d) { this.dirs.delete(real); this.dirs.set(real, d) }
    return d
  }

  pin(real: string): void { this.pins.set(real, (this.pins.get(real) ?? 0) + 1) }
  unpin(real: string): void {
    const n = (this.pins.get(real) ?? 0) - 1
    if (n <= 0) this.pins.delete(real)
    else this.pins.set(real, n)
    this.evict()
  }
  isPinned(real: string): boolean { return this.pins.has(real) }

  forget(real: string): void { this.dirs.delete(real) }

  /** Cached listing, or load it. */
  async getOrLoad(real: string): Promise<CachedDir> {
    return this.get(real) ?? (await this.load(real)).snap
  }

  /** Read the folder now (joining an in-flight read) and compare with the previous listing. */
  load(real: string): Promise<{ snap: CachedDir; changed: boolean }> {
    const running = this.inflight.get(real)
    if (running) return running
    const p = this.read(real).finally(() => this.inflight.delete(real))
    this.inflight.set(real, p)
    return p
  }

  private async read(real: string): Promise<{ snap: CachedDir; changed: boolean }> {
    this.readCount++
    let dirents: import("fs").Dirent[]
    try {
      dirents = await readdir(real, { withFileTypes: true })
    } catch (e) {
      this.dirs.delete(real)
      throw toFsError(e)
    }
    const all = await mapLimit(dirents, this.opts.statConcurrency ?? 32, async (d) => this.entry(real, d.name))
    const entries = sortEntries(all.filter((e): e is FsEntry => e !== null))
    const max = this.opts.maxEntries ?? 5_000
    const truncated = entries.length > max ? { total: entries.length } : undefined
    const kept = truncated ? entries.slice(0, max) : entries
    await this.opts.repo.annotate(real, kept)

    const prev = this.dirs.get(real)
    const same = prev !== undefined
      && JSON.stringify(prev.entries) === JSON.stringify(kept)
      && JSON.stringify(prev.truncated) === JSON.stringify(truncated)
    if (same) {
      this.get(real) // touch LRU
      return { snap: prev!, changed: false }
    }
    const snap: CachedDir = { real, version: `${this.opts.bootId}:${++this.counter}`, entries: kept, ...(truncated ? { truncated } : {}) }
    this.dirs.delete(real)
    this.dirs.set(real, snap)
    this.evict()
    return { snap, changed: true }
  }

  private async entry(dir: string, name: string): Promise<FsEntry | null> {
    const full = join(dir, name)
    let l: import("fs").Stats
    try { l = await lstat(full) } catch { return null }
    if (l.isSymbolicLink()) {
      try {
        const s = await stat(full)
        const target = s.isDirectory() ? "dir" : "file"
        return target === "file"
          ? { name, type: "symlink", target, size: s.size, mtime: Math.round(s.mtimeMs), ignored: false }
          : { name, type: "symlink", target, mtime: Math.round(s.mtimeMs), ignored: false }
      } catch {
        return { name, type: "symlink", mtime: Math.round(l.mtimeMs), ignored: false }
      }
    }
    if (l.isDirectory()) return { name, type: "dir", mtime: Math.round(l.mtimeMs), ignored: false }
    return { name, type: "file", size: l.size, mtime: Math.round(l.mtimeMs), ignored: false }
  }

  private evict(): void {
    const max = this.opts.maxDirs ?? 2_000
    if (this.dirs.size <= max) return
    for (const key of this.dirs.keys()) {
      if (this.dirs.size <= max) break
      if (!this.pins.has(key)) this.dirs.delete(key)
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/fs/dir-cache.test.ts`
Expected: 6 pass.

- [ ] **Step 5: Commit**

```bash
git add src/core/fs/dir-cache.ts src/core/fs/dir-cache.test.ts
git commit -m "feat(fs): async, single-flight, versioned folder cache"
```

---

### Task 4: File ops (read, write, stat, rename/move/mkdir/touch/delete)

**Files:**
- Create: `src/core/fs/file-ops.ts`
- Test: `src/core/fs/file-ops.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// src/core/fs/file-ops.test.ts
import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, readdirSync, realpathSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { readText, writeText, statEntry, applyOp, MAX_READ_BYTES } from "./file-ops"

const tmp = () => realpathSync(mkdtempSync(join(tmpdir(), "fs-ops-")))

test("readText returns utf-8 text", async () => {
  const d = tmp()
  writeFileSync(join(d, "a.txt"), "héllo")
  expect(await readText(join(d, "a.txt"))).toBe("héllo")
})

test("readText refuses large and binary files with TOO_LARGE / BINARY", async () => {
  const d = tmp()
  writeFileSync(join(d, "big"), Buffer.alloc(MAX_READ_BYTES + 1, 65))
  writeFileSync(join(d, "bin"), Buffer.from([1, 0, 2]))
  await expect(readText(join(d, "big"))).rejects.toMatchObject({ code: "TOO_LARGE" })
  await expect(readText(join(d, "bin"))).rejects.toMatchObject({ code: "BINARY" })
})

test("writeText is atomic, creates parents and returns size + mtime", async () => {
  const d = tmp()
  const r = await writeText(join(d, "x", "y", "z.txt"), "abc")
  expect(r.size).toBe(3)
  expect(typeof r.mtime).toBe("number")
  expect(readFileSync(join(d, "x", "y", "z.txt"), "utf-8")).toBe("abc")
  expect(readdirSync(join(d, "x", "y"))).toEqual(["z.txt"])
})

test("statEntry describes one path with its real path", async () => {
  const d = tmp()
  writeFileSync(join(d, "f"), "12")
  expect(await statEntry(join(d, "f"))).toMatchObject({ name: "f", type: "file", size: 2, real: join(d, "f") })
  await expect(statEntry(join(d, "missing"))).rejects.toMatchObject({ code: "ENOENT" })
})

test("rename, move, mkdir and touch", async () => {
  const d = tmp()
  writeFileSync(join(d, "a"), "1")
  await applyOp({ op: "rename", path: join(d, "a"), to: join(d, "b") })
  expect(existsSync(join(d, "b"))).toBe(true)
  await applyOp({ op: "mkdir", path: join(d, "p", "q") })
  await applyOp({ op: "move", path: join(d, "b"), to: join(d, "p", "q", "b") })
  expect(existsSync(join(d, "p", "q", "b"))).toBe(true)
  await applyOp({ op: "touch", path: join(d, "t") })
  expect(readFileSync(join(d, "t"), "utf-8")).toBe("")
  await expect(applyOp({ op: "touch", path: join(d, "t") })).rejects.toMatchObject({ code: "EEXIST" })
  writeFileSync(join(d, "c"), "")
  await expect(applyOp({ op: "rename", path: join(d, "c"), to: join(d, "t") })).rejects.toMatchObject({ code: "EEXIST" })
})

test("delete moves the entry to the trash with a .trashinfo record", async () => {
  const d = tmp()
  const trash = tmp()
  mkdirSync(join(d, "folder"))
  writeFileSync(join(d, "folder", "x"), "1")
  await applyOp({ op: "delete", path: join(d, "folder") }, { trashDir: trash, platform: "linux" })
  expect(existsSync(join(d, "folder"))).toBe(false)
  expect(existsSync(join(trash, "files", "folder", "x"))).toBe(true)
  const info = readFileSync(join(trash, "info", "folder.trashinfo"), "utf-8")
  expect(info).toContain("[Trash Info]")
  expect(info).toContain(`Path=${encodeURI(join(d, "folder"))}`)
  // a second item with the same name gets a unique name
  mkdirSync(join(d, "folder"))
  await applyOp({ op: "delete", path: join(d, "folder") }, { trashDir: trash, platform: "linux" })
  expect(existsSync(join(trash, "files", "folder.2"))).toBe(true)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/fs/file-ops.test.ts`
Expected: FAIL (cannot resolve `./file-ops`).

- [ ] **Step 3: Write the implementation**

```ts
// src/core/fs/file-ops.ts
import { readFile, writeFile, rename, mkdir, open, lstat, stat, realpath, rm, access } from "fs/promises"
import { homedir } from "os"
import { basename, dirname, join } from "path"
import { FsError, toFsError } from "./errors"
import type { FsEntry, FsOp } from "./types"

export const MAX_READ_BYTES = 1024 * 1024
const BINARY_SCAN_BYTES = 8 * 1024

export async function readText(path: string): Promise<string> {
  let buf: Buffer
  try {
    const s = await stat(path)
    if (s.isDirectory()) throw new FsError("EISDIR", `is a directory: ${path}`)
    if (s.size > MAX_READ_BYTES) throw new FsError("TOO_LARGE", `File too large (${s.size} bytes); limit is 1MB`)
    buf = await readFile(path)
  } catch (e) {
    throw toFsErrorKeep(e)
  }
  const n = Math.min(buf.length, BINARY_SCAN_BYTES)
  for (let i = 0; i < n; i++) if (buf[i] === 0) throw new FsError("BINARY", "File appears to be binary (null byte detected)")
  return buf.toString("utf-8")
}

export async function writeText(path: string, text: string): Promise<{ size: number; mtime: number }> {
  try {
    await mkdir(dirname(path), { recursive: true })
    const tmp = `${path}.tmp`
    const buf = Buffer.from(text, "utf-8")
    await writeFile(tmp, buf)
    await rename(tmp, path)
    const s = await stat(path)
    return { size: buf.length, mtime: Math.round(s.mtimeMs) }
  } catch (e) {
    throw toFsError(e)
  }
}

export async function statEntry(path: string): Promise<FsEntry & { real: string }> {
  try {
    const l = await lstat(path)
    const real = await realpath(path).catch(() => path)
    const name = basename(path)
    if (l.isSymbolicLink()) {
      const s = await stat(path).catch(() => null)
      if (!s) return { name, type: "symlink", ignored: false, real }
      return s.isDirectory()
        ? { name, type: "symlink", target: "dir", mtime: Math.round(s.mtimeMs), ignored: false, real }
        : { name, type: "symlink", target: "file", size: s.size, mtime: Math.round(s.mtimeMs), ignored: false, real }
    }
    if (l.isDirectory()) return { name, type: "dir", mtime: Math.round(l.mtimeMs), ignored: false, real }
    return { name, type: "file", size: l.size, mtime: Math.round(l.mtimeMs), ignored: false, real }
  } catch (e) {
    throw toFsError(e)
  }
}

async function exists(p: string): Promise<boolean> {
  try { await lstat(p); return true } catch { return false }
}

export interface OpOptions { trashDir?: string; platform?: NodeJS.Platform }

export async function applyOp(op: FsOp, opts: OpOptions = {}): Promise<void> {
  try {
    switch (op.op) {
      case "rename":
      case "move":
        if (await exists(op.to)) throw new FsError("EEXIST", `already exists: ${op.to}`)
        await rename(op.path, op.to)
        return
      case "mkdir":
        await mkdir(op.path, { recursive: true })
        return
      case "touch": {
        const fh = await open(op.path, "wx") // fails with EEXIST if present
        await fh.close()
        return
      }
      case "delete":
        await lstat(op.path) // ENOENT early
        await moveToTrash(op.path, opts)
        return
    }
  } catch (e) {
    throw toFsError(e)
  }
}

function defaultTrashDir(platform: NodeJS.Platform): string {
  return platform === "darwin" ? join(homedir(), ".Trash") : join(homedir(), ".local", "share", "Trash")
}

async function uniqueName(dir: string, name: string): Promise<string> {
  if (!(await exists(join(dir, name)))) return name
  for (let i = 2; ; i++) {
    const candidate = `${name}.${i}`
    if (!(await exists(join(dir, candidate)))) return candidate
  }
}

async function moveToTrash(path: string, opts: OpOptions): Promise<void> {
  const platform = opts.platform ?? process.platform
  const trash = opts.trashDir ?? defaultTrashDir(platform)
  const filesDir = platform === "darwin" ? trash : join(trash, "files")
  await mkdir(filesDir, { recursive: true })
  const name = await uniqueName(filesDir, basename(path))
  if (platform !== "darwin") {
    const infoDir = join(trash, "info")
    await mkdir(infoDir, { recursive: true })
    const date = new Date().toISOString().slice(0, 19)
    await writeFile(join(infoDir, `${name}.trashinfo`), `[Trash Info]\nPath=${encodeURI(path)}\nDeletionDate=${date}\n`)
  }
  try {
    await rename(path, join(filesDir, name))
  } catch (e) {
    if ((e as { code?: string }).code === "EXDEV") {
      // The trash is on another filesystem; the app already confirmed, so delete for real.
      await rm(path, { recursive: true, force: true })
      if (platform !== "darwin") await rm(join(trash, "info", `${name}.trashinfo`), { force: true })
      return
    }
    throw e
  }
}

/** Keep our own TOO_LARGE/BINARY/EISDIR codes; map node errors. */
function toFsErrorKeep(e: unknown): FsError {
  return e instanceof FsError ? e : toFsError(e)
}

// `access` is re-exported for callers that need a cheap existence probe without stat data.
export { access }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/fs/file-ops.test.ts`
Expected: 6 pass.

- [ ] **Step 5: Commit**

```bash
git add src/core/fs/file-ops.ts src/core/fs/file-ops.test.ts
git commit -m "feat(fs): async read/write/stat and file operations with trash-based delete"
```

---

### Task 5: Fuzzy search index

**Files:**
- Create: `src/core/fs/search-index.ts`
- Test: `src/core/fs/search-index.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// src/core/fs/search-index.test.ts
import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, realpathSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { execFileSync } from "child_process"
import { fuzzyMatch, SearchIndexes } from "./search-index"
import { RepoInfoCache } from "./repo-info"

test("fuzzyMatch prefers the file name, consecutive runs and word starts", () => {
  const a = fuzzyMatch("ftree", "apps/ui/editor/FileTree.kt")!
  const b = fuzzyMatch("ftree", "apps/fixtures/tree/data.json")!
  expect(a.score).toBeGreaterThan(b.score)
  expect(fuzzyMatch("xyz", "apps/ui/editor/FileTree.kt")).toBeNull()
  const hits = fuzzyMatch("ft", "a/FileTree.kt")!.hits
  expect(hits.map((i) => "a/FileTree.kt"[i])).toEqual(["F", "T"])
})

function repo() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "fs-search-")))
  execFileSync("git", ["init", "-q"], { cwd: root })
  writeFileSync(join(root, ".gitignore"), "node_modules/\n")
  mkdirSync(join(root, "src", "editor"), { recursive: true })
  writeFileSync(join(root, "src", "editor", "FileTree.kt"), "")
  writeFileSync(join(root, "src", "editor", "EditorPanes.kt"), "")
  writeFileSync(join(root, "README.md"), "")
  mkdirSync(join(root, "node_modules", "filetree"), { recursive: true })
  writeFileSync(join(root, "node_modules", "filetree", "index.js"), "")
  return root
}

test("a repo scope searches tracked + untracked files, never ignored ones", async () => {
  const root = repo()
  const idx = new SearchIndexes(new RepoInfoCache())
  const hits = await idx.query(root, "filetree", 50)
  expect(hits[0]!.path).toBe(join(root, "src", "editor", "FileTree.kt"))
  expect(hits.some((h) => h.path.includes("node_modules"))).toBe(false)
  expect(hits[0]!.hits.every((i) => i >= root.length + 1)).toBe(true) // indexes into the absolute path
})

test("folders are searchable and results respect the limit", async () => {
  const root = repo()
  const idx = new SearchIndexes(new RepoInfoCache())
  const hits = await idx.query(root, "editor", 1)
  expect(hits).toHaveLength(1)
  expect(hits[0]).toMatchObject({ path: join(root, "src", "editor"), type: "dir" })
})

test("outside git the walk skips heavy folders", async () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "fs-search-plain-")))
  mkdirSync(join(root, "node_modules", "x"), { recursive: true })
  writeFileSync(join(root, "node_modules", "x", "target.txt"), "")
  writeFileSync(join(root, "target.txt"), "")
  const hits = await new SearchIndexes(new RepoInfoCache()).query(root, "target", 50)
  expect(hits.map((h) => h.path)).toEqual([join(root, "target.txt")])
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/fs/search-index.test.ts`
Expected: FAIL (cannot resolve `./search-index`).

- [ ] **Step 3: Write the implementation**

```ts
// src/core/fs/search-index.ts
import { readdir } from "fs/promises"
import { join, relative, sep } from "path"
import { gitAsync } from "../git/exec"
import type { RepoInfoCache } from "./repo-info"
import type { SearchHit } from "./types"

const SKIP = new Set(["node_modules", ".git", "build", "dist", ".next", ".nuxt", "out", "target", ".gradle", "Pods"])
const MAX_PATHS = 200_000
const REBUILD_AFTER_MS = 30_000
const EVICT_AFTER_MS = 10 * 60_000

const isBoundary = (s: string, i: number) => {
  if (i === 0) return true
  const p = s[i - 1]!
  if (p === "/" || p === "-" || p === "_" || p === "." || p === " ") return true
  return p === p.toLowerCase() && s[i] !== s[i]!.toLowerCase() // camelCase hump
}

function greedy(q: string, s: string, from: number): number[] | null {
  const ql = q.toLowerCase(), sl = s.toLowerCase()
  const hits: number[] = []
  let j = from
  for (const ch of ql) {
    const k = sl.indexOf(ch, j)
    if (k < 0) return null
    hits.push(k)
    j = k + 1
  }
  return hits
}

function scoreHits(s: string, hits: number[], nameStart: number): number {
  let score = 0
  for (let n = 0; n < hits.length; n++) {
    const i = hits[n]!
    score += 1
    if (i >= nameStart) score += 10
    if (n > 0 && hits[n - 1] === i - 1) score += 8
    if (isBoundary(s, i)) score += 6
  }
  return score - s.length * 0.05
}

/** fzf-style subsequence match on a relative path. Null when `q` is not a subsequence. */
export function fuzzyMatch(q: string, s: string): { score: number; hits: number[] } | null {
  if (!q) return null
  const nameStart = s.lastIndexOf("/") + 1
  const inName = greedy(q, s, nameStart)
  const anywhere = greedy(q, s, 0)
  if (!inName && !anywhere) return null
  const a = inName ? { hits: inName, score: scoreHits(s, inName, nameStart) } : null
  const b = anywhere ? { hits: anywhere, score: scoreHits(s, anywhere, nameStart) } : null
  return a && (!b || a.score >= b.score) ? a : b!
}

interface Built { scope: string; rels: Array<{ rel: string; dir: boolean }>; builtAt: number; lastUsed: number }

async function walk(scope: string): Promise<Array<{ rel: string; dir: boolean }>> {
  const out: Array<{ rel: string; dir: boolean }> = []
  const queue = [scope]
  while (queue.length && out.length < MAX_PATHS) {
    const d = queue.shift()!
    let ents: import("fs").Dirent[]
    try { ents = await readdir(d, { withFileTypes: true }) } catch { continue }
    for (const e of ents) {
      if (out.length >= MAX_PATHS) break
      const full = join(d, e.name)
      const rel = relative(scope, full).split(sep).join("/")
      if (e.isDirectory()) {
        if (SKIP.has(e.name)) continue
        out.push({ rel, dir: true })
        queue.push(full)
      } else {
        out.push({ rel, dir: false })
      }
    }
  }
  return out
}

function withDirs(files: string[]): Array<{ rel: string; dir: boolean }> {
  const dirs = new Set<string>()
  for (const f of files) {
    const parts = f.split("/")
    for (let i = 1; i < parts.length; i++) dirs.add(parts.slice(0, i).join("/"))
  }
  return [...[...dirs].map((rel) => ({ rel, dir: true })), ...files.map((rel) => ({ rel, dir: false }))].slice(0, MAX_PATHS)
}

/** One lazily built path index per scope folder, rebuilt after 30 s, evicted after 10 min idle. */
export class SearchIndexes {
  private readonly built = new Map<string, Built>()
  private readonly building = new Map<string, Promise<Built>>()

  constructor(private readonly repo: RepoInfoCache) {}

  private async build(scope: string): Promise<Built> {
    const root = await this.repo.repoFor(scope)
    let rels: Array<{ rel: string; dir: boolean }>
    if (root) {
      const out = await gitAsync(scope, ["ls-files", "-co", "--exclude-standard", "-z"], { timeoutMs: 5_000, trim: false }).catch(() => null)
      rels = out === null ? await walk(scope) : withDirs(out.split("\0").filter(Boolean))
    } else {
      rels = await walk(scope)
    }
    const b: Built = { scope, rels, builtAt: Date.now(), lastUsed: Date.now() }
    this.built.set(scope, b)
    return b
  }

  private async get(scope: string): Promise<Built> {
    const now = Date.now()
    for (const [k, v] of this.built) if (now - v.lastUsed > EVICT_AFTER_MS) this.built.delete(k)
    const b = this.built.get(scope)
    if (b && now - b.builtAt < REBUILD_AFTER_MS) { b.lastUsed = now; return b }
    const running = this.building.get(scope)
    if (running) return running
    const p = this.build(scope).finally(() => this.building.delete(scope))
    this.building.set(scope, p)
    return p
  }

  async query(scope: string, q: string, limit: number): Promise<SearchHit[]> {
    const b = await this.get(scope)
    const offset = scope === "/" ? 1 : scope.length + 1
    const scored: SearchHit[] = []
    for (const { rel, dir } of b.rels) {
      const m = fuzzyMatch(q, rel)
      if (!m) continue
      scored.push({
        path: scope === "/" ? `/${rel}` : `${scope}/${rel}`,
        name: rel.slice(rel.lastIndexOf("/") + 1),
        type: dir ? "dir" : "file",
        score: m.score,
        hits: m.hits.map((i) => i + offset),
      })
    }
    scored.sort((x, y) => y.score - x.score || x.path.length - y.path.length || (x.path < y.path ? -1 : 1))
    return scored.slice(0, Math.max(1, Math.min(limit, 200)))
  }

  /** Drop a scope's index (tests, or after a burst of changes). */
  invalidate(scope: string): void { this.built.delete(scope) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/fs/search-index.test.ts`
Expected: 4 pass. If the ranking assertion in the first test fails, adjust only the bonus constants (name 10 / consecutive 8 / boundary 6), not the test.

- [ ] **Step 5: Commit**

```bash
git add src/core/fs/search-index.ts src/core/fs/search-index.test.ts
git commit -m "feat(fs): per-scope fuzzy file search built from git ls-files"
```

---

### Task 6: FileSystemService facade (request/response part)

**Files:**
- Create: `src/core/fs/file-system-service.ts`
- Test: `src/core/fs/file-system-service.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// src/core/fs/file-system-service.test.ts
import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, realpathSync, symlinkSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { FileSystemService } from "./file-system-service"

const tmp = () => realpathSync(mkdtempSync(join(tmpdir(), "fss-")))

test("list returns a snapshot addressed by the path the caller used", async () => {
  const d = tmp()
  mkdirSync(join(d, "real"))
  writeFileSync(join(d, "real", "a"), "")
  symlinkSync(join(d, "real"), join(d, "link"))
  const fss = new FileSystemService({ bootId: "t" })
  const snap = await fss.list(join(d, "link") + "/")
  expect(snap.path).toBe(join(d, "link"))
  expect(snap.real).toBe(join(d, "real"))
  expect(snap.entries.map((e) => e.name)).toEqual(["a"])
})

test("list of an unwatched folder always re-reads it", async () => {
  const d = tmp()
  const fss = new FileSystemService({ bootId: "t" })
  expect((await fss.list(d)).entries).toHaveLength(0)
  writeFileSync(join(d, "new"), "")
  expect((await fss.list(d)).entries.map((e) => e.name)).toEqual(["new"])
})

test("relative paths are rejected with EINVAL", async () => {
  const fss = new FileSystemService({ bootId: "t" })
  await expect(fss.list("relative/path")).rejects.toMatchObject({ code: "EINVAL" })
})

test("read, write, stat, search and op go through one service", async () => {
  const d = tmp()
  const fss = new FileSystemService({ bootId: "t", trashDir: tmp() })
  await fss.write(join(d, "src", "FileTree.kt"), "hello")
  expect(await fss.read(join(d, "src", "FileTree.kt"))).toBe("hello")
  expect((await fss.stat(join(d, "src"))).type).toBe("dir")
  expect((await fss.search(d, "ftree", 10))[0]!.path).toBe(join(d, "src", "FileTree.kt"))
  await fss.op({ op: "rename", path: join(d, "src", "FileTree.kt"), to: join(d, "src", "Tree.kt") })
  expect((await fss.list(join(d, "src"))).entries.map((e) => e.name)).toEqual(["Tree.kt"])
  await expect(fss.op({ op: "rename", path: "src/x", to: join(d, "y") })).rejects.toMatchObject({ code: "EINVAL" })
})

test("listing a big folder does not block the event loop", async () => {
  const d = tmp()
  for (let i = 0; i < 5_000; i++) writeFileSync(join(d, `f${i}.txt`), "")
  const fss = new FileSystemService({ bootId: "t" })
  let maxGap = 0
  let last = performance.now()
  const timer = setInterval(() => { const now = performance.now(); maxGap = Math.max(maxGap, now - last); last = now }, 5)
  await fss.list(d)
  clearInterval(timer)
  // Generous bound for a loaded CI box; the old sync implementation blocked for the whole listing.
  expect(maxGap).toBeLessThan(250)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/fs/file-system-service.test.ts`
Expected: FAIL (cannot resolve `./file-system-service`).

- [ ] **Step 3: Write the implementation**

```ts
// src/core/fs/file-system-service.ts
import { randomBytes } from "crypto"
import { dirname } from "path"
import { DirCache, type CachedDir } from "./dir-cache"
import { applyOp, readText, statEntry, writeText, type OpOptions } from "./file-ops"
import { normalizeAbsPath, realKey } from "./paths"
import { RepoInfoCache } from "./repo-info"
import { SearchIndexes } from "./search-index"
import type { DirSnapshot, FsEntry, FsOp, SearchHit } from "./types"

export interface FileSystemServiceOpts {
  bootId?: string
  trashDir?: string
  maxEntries?: number
  maxDirs?: number
}

const toSnapshot = (path: string, c: CachedDir): DirSnapshot => ({
  path, real: c.real, version: c.version, entries: c.entries, ...(c.truncated ? { truncated: c.truncated } : {}),
})

/** The host's one file-system service: addressed by absolute path, shared by every caller. */
export class FileSystemService {
  readonly bootId: string
  readonly repo = new RepoInfoCache()
  readonly cache: DirCache
  private readonly searches: SearchIndexes
  private readonly opOpts: OpOptions

  constructor(opts: FileSystemServiceOpts = {}) {
    this.bootId = opts.bootId ?? randomBytes(4).toString("hex")
    this.cache = new DirCache({ repo: this.repo, bootId: this.bootId, maxEntries: opts.maxEntries, maxDirs: opts.maxDirs })
    this.searches = new SearchIndexes(this.repo)
    this.opOpts = { trashDir: opts.trashDir }
  }

  /** A folder's snapshot. Watched folders are served from cache; others are re-read. */
  async list(path: string): Promise<DirSnapshot> {
    const p = normalizeAbsPath(path)
    const real = await realKey(p)
    const c = this.cache.isPinned(real) ? await this.cache.getOrLoad(real) : (await this.cache.load(real)).snap
    return toSnapshot(p, c)
  }

  async stat(path: string): Promise<FsEntry & { real: string }> {
    return statEntry(normalizeAbsPath(path))
  }

  async read(path: string): Promise<string> {
    return readText(normalizeAbsPath(path))
  }

  async write(path: string, text: string): Promise<{ size: number; mtime: number }> {
    const p = normalizeAbsPath(path)
    const r = await writeText(p, text)
    await this.refresh([dirname(p)])
    return r
  }

  async search(scope: string, q: string, limit = 50): Promise<SearchHit[]> {
    const s = await realKey(normalizeAbsPath(scope))
    return this.searches.query(s, q, limit)
  }

  async op(op: FsOp): Promise<void> {
    const path = normalizeAbsPath(op.path)
    const norm: FsOp = "to" in op ? { ...op, path, to: normalizeAbsPath(op.to) } : { ...op, path }
    await applyOp(norm, this.opOpts)
    await this.refresh("to" in norm ? [dirname(norm.path), dirname(norm.to)] : [dirname(norm.path)])
  }

  /**
   * Re-read folders that changed because of our own write/op, if they are cached. Overridden
   * behaviour in A2 (Task 10): subscribers of those folders get the new snapshot.
   */
  protected async refresh(dirs: string[]): Promise<void> {
    for (const d of new Set(dirs)) {
      const real = await realKey(d).catch(() => null)
      if (!real) continue
      const root = this.repo.knownRepoFor(real)
      if (root) this.repo.invalidate(root)
      if (this.cache.get(real)) await this.onDirChanged(real)
    }
  }

  /** Hook for A2: called with a real folder path after it may have changed. */
  protected async onDirChanged(real: string): Promise<void> {
    await this.cache.load(real).catch(() => undefined)
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/fs/file-system-service.test.ts`
Expected: 5 pass.

- [ ] **Step 5: Commit**

```bash
git add src/core/fs/file-system-service.ts src/core/fs/file-system-service.test.ts
git commit -m "feat(fs): FileSystemService facade for list/stat/read/write/search/op"
```

---

### Task 7: Rebuild the legacy workspace/session routes on the service

**Files:**
- Create: `src/core/fs/legacy.ts`
- Modify: `src/channels/web/index.ts` (the `/sessions/:id/fs*` and `/workspaces/:id/fs*` list/read/write/search handlers, ~lines 2531–2657; import at line 13)
- Modify: `src/core/editor/fs-service.ts` (remove the `FsService` class and its sync helpers; keep `DiffEntry`, `parseDiff` and anything `parseDiff` uses)
- Modify: `src/channels/web/workspace-fs.test.ts`

- [ ] **Step 1: Rewrite the legacy test against the new module (failing)**

```ts
// src/channels/web/workspace-fs.test.ts
import { test, expect } from "bun:test"
import { mkdtempSync, writeFileSync, mkdirSync, symlinkSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { FileSystemService } from "../../core/fs/file-system-service"
import { WorkdirFs } from "../../core/fs/legacy"

function fixture() {
  const root = mkdtempSync(join(tmpdir(), "ws-fs-"))
  mkdirSync(join(root, "src"))
  writeFileSync(join(root, "src", "a.ts"), "export const a = 1\n")
  writeFileSync(join(root, "README.md"), "# hi\n")
  return root
}
const fss = new FileSystemService({ bootId: "t" })

test("a workspace fs lists its own directory in the old shape", async () => {
  const root = fixture()
  const entries = await new WorkdirFs(fss, root).listDir(".")
  expect(entries.map((e) => e.name)).toEqual(["src", "README.md"])
  expect(entries[0]).toMatchObject({ name: "src", type: "dir", ignored: false })
  expect(typeof entries[1]!.modified).toBe("string")
  expect(entries[1]!.size).toBe(5)
})

test("a workspace fs reads a file under its root", async () => {
  const root = fixture()
  expect(await new WorkdirFs(fss, root).readFile("src/a.ts")).toBe("export const a = 1\n")
})

test("a path that escapes the workspace root is refused", async () => {
  const root = fixture()
  await expect(new WorkdirFs(fss, root).readFile("../../etc/passwd")).rejects.toThrow()
})

test("an absolute path outside the root is refused", async () => {
  const root = fixture()
  await expect(new WorkdirFs(fss, root).readFile("/etc/passwd")).rejects.toThrow()
})

test("a symlink that points outside the root is refused", async () => {
  const root = fixture()
  symlinkSync("/etc", join(root, "out"))
  await expect(new WorkdirFs(fss, root).readFile("out/passwd")).rejects.toThrow(/traversal/)
  await expect(new WorkdirFs(fss, root).writeFile("out/x", "y")).rejects.toThrow(/traversal/)
})

test("write and search keep their old response shapes", async () => {
  const root = fixture()
  const w = new WorkdirFs(fss, root)
  expect(await w.writeFile("src/b.ts", "b")).toEqual({ ok: true, size: 1 })
  const hits = await w.searchFiles("b.ts")
  expect(hits[0]).toEqual({ path: "src/b.ts", name: "b.ts", type: "file", ignored: false })
})

test("two workspaces on the same repo see the same files", async () => {
  const root = fixture()
  const a = await new WorkdirFs(fss, root).listDir(".")
  const b = await new WorkdirFs(fss, root).listDir(".")
  expect(a.map((e) => e.name)).toEqual(b.map((e) => e.name))
})
```

Run: `bun test src/channels/web/workspace-fs.test.ts`
Expected: FAIL (cannot resolve `../../core/fs/legacy`).

- [ ] **Step 2: Write `legacy.ts`**

```ts
// src/core/fs/legacy.ts
// The pre-2026-09-27 `/workspaces/:id/fs*` and `/sessions/:id/fs*` contract, served by the host
// FileSystemService. Relative paths, containment inside the workdir (the security boundary for
// these routes), and the old response shapes are all preserved here.
import { realpath } from "fs/promises"
import { dirname, join, relative, resolve, sep } from "path"
import type { FileSystemService } from "./file-system-service"
import type { FsEntry } from "./types"

export interface LegacyFsEntry { name: string; type: "file" | "dir"; size: number; modified: string; ignored: boolean }
export interface LegacySearchResult { path: string; name: string; type: "file" | "dir"; ignored: boolean }

export function toLegacyEntry(e: FsEntry): LegacyFsEntry {
  const type = e.type === "dir" || e.target === "dir" ? "dir" : "file"
  return { name: e.name, type, size: e.size ?? 0, modified: new Date(e.mtime ?? 0).toISOString(), ignored: e.ignored }
}

export class WorkdirFs {
  private rootReal?: Promise<string>

  constructor(private readonly fss: FileSystemService, private readonly workdir: string) {}

  private root(): Promise<string> {
    this.rootReal ??= realpath(this.workdir)
    return this.rootReal
  }

  private inside(root: string, p: string): boolean {
    return p === root || p.startsWith(root.endsWith(sep) ? root : root + sep)
  }

  /** Resolve a workdir-relative path; refuse anything that lands outside the workdir. */
  private async resolveExisting(rel: string): Promise<string> {
    if (rel.includes("\0")) throw new Error("Path contains null byte")
    const root = await this.root()
    const joined = join(root, rel.replace(/^\/+/, ""))
    const resolved = await realpath(joined).catch(() => resolve(joined))
    if (!this.inside(root, resolved)) throw new Error(`Path traversal detected: "${rel}" resolves outside workdir`)
    return resolved
  }

  /** Same, for a path that may not exist yet: the deepest existing ancestor must be inside. */
  private async resolveForWrite(rel: string): Promise<string> {
    if (rel.includes("\0")) throw new Error("Path contains null byte")
    const root = await this.root()
    const target = resolve(join(root, rel.replace(/^\/+/, "")))
    if (!this.inside(root, target)) throw new Error(`Path traversal detected: "${rel}" resolves outside workdir`)
    let ancestor = dirname(target)
    while (true) {
      const real = await realpath(ancestor).catch(() => null)
      if (real) {
        if (!this.inside(root, real)) throw new Error(`Path traversal detected: "${rel}" resolves outside workdir via symlink`)
        return target
      }
      const parent = dirname(ancestor)
      if (parent === ancestor) throw new Error(`Path traversal detected: "${rel}" resolves outside workdir`)
      ancestor = parent
    }
  }

  async listDir(rel: string): Promise<LegacyFsEntry[]> {
    const snap = await this.fss.list(await this.resolveExisting(rel))
    return snap.entries.map(toLegacyEntry)
  }

  async readFile(rel: string): Promise<string> {
    return this.fss.read(await this.resolveExisting(rel))
  }

  async writeFile(rel: string, content: string): Promise<{ ok: true; size: number }> {
    const r = await this.fss.write(await this.resolveForWrite(rel), content)
    return { ok: true, size: r.size }
  }

  async searchFiles(q: string, max = 20): Promise<LegacySearchResult[]> {
    if (!q.trim()) return []
    const root = await this.root()
    const hits = await this.fss.search(root, q, max)
    return hits.map((h) => ({ path: relative(root, h.path).split(sep).join("/"), name: h.name, type: h.type, ignored: false }))
  }
}
```

Run: `bun test src/channels/web/workspace-fs.test.ts`
Expected: 7 pass.

- [ ] **Step 3: Point the routes at it**

In `src/channels/web/index.ts`:

1. Replace `import { FsService } from "../../core/editor/fs-service"` (line 13) with:
   ```ts
   import { FileSystemService } from "../../core/fs/file-system-service"
   import { WorkdirFs } from "../../core/fs/legacy"
   ```
2. Add a field next to `private readonly fsWatcher?: FsWatcher` (~line 456):
   ```ts
   /** The host's single file-system service (spec 2026-09-27). */
   readonly fss: FileSystemService
   ```
   and in the constructor next to `this.fsWatcher = opts.fsWatcher` (~line 475):
   ```ts
   this.fss = new FileSystemService()
   ```
3. In all eight list/read/write/search handlers (four under `/sessions/:id/fs*`, four under `/workspaces/:id/fs*`) replace `const fs = new FsService(workdir)` with `const fs = new WorkdirFs(this.fss, workdir)`. The rest of each handler stays as is: the method names (`listDir`, `readFile`, `writeFile`, `searchFiles`) and the error-message checks (`"too large"`, `"binary"`) match. Wrap the two `listDir` calls so a missing folder is a 404 instead of a crash:
   ```ts
   try {
     return this.json(await fs.listDir(relPath))
   } catch (err: any) {
     return this.json({ error: err?.message ?? String(err) }, err?.code === "ENOENT" ? 404 : 400)
   }
   ```
4. Update the comment above the workspace block: containment is now enforced by `WorkdirFs`.

- [ ] **Step 4: Remove the old implementation**

In `src/core/editor/fs-service.ts`, delete the `FsService` class, `FsEntry`, `SearchResult`, `WriteResult`, `MAX_FILE_SIZE`, `BINARY_SCAN_SIZE` and the now-unused imports (`readdirSync`, `statSync`, `readFileSync`, `writeFileSync`, `mkdirSync`, `renameSync`, `realpathSync`, `realpath`, `stat`, `execSync`, and path helpers no longer used). Keep `DiffEntry`, `parseDiff` and whatever `parseDiff` calls. Then:

Run: `grep -rn "FsService\b\|fs-service\"" src --include=*.ts`
Expected: only imports of `DiffEntry`/`parseDiff` (`src/core/walkthrough/author.ts`, `src/core/editor/workdir-diff.ts`).

- [ ] **Step 5: Run the broker tests**

Run: `bun test src/core/fs src/channels/web/workspace-fs.test.ts src/core/editor`
Expected: all pass.
Run: `bunx tsc --noEmit -p .`
Expected: no new errors. Run the same command before starting this task and note the error count; it must not grow.

- [ ] **Step 6: Commit**

```bash
git add src/core/fs/legacy.ts src/channels/web/index.ts src/core/editor/fs-service.ts src/channels/web/workspace-fs.test.ts
git commit -m "refactor(web): workspace/session fs routes run on the host FileSystemService

The old FsService listed with readdirSync/statSync and two execSync git calls per folder and
searched with a synchronous full walk, freezing the broker on every expand and search."
```

---

# Part A2: subscriptions

### Task 8: DirWatchers

**Files:**
- Create: `src/core/fs/dir-watchers.ts`
- Test: `src/core/fs/dir-watchers.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// src/core/fs/dir-watchers.test.ts
import { test, expect } from "bun:test"
import { mkdtempSync, writeFileSync, realpathSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { DirWatchers } from "./dir-watchers"

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const tmp = () => realpathSync(mkdtempSync(join(tmpdir(), "fs-watch-")))

test("a burst of changes produces one flush after the debounce", async () => {
  const d = tmp()
  const flushed: string[] = []
  const w = new DirWatchers((dir) => flushed.push(dir), { debounceMs: 60, maxWaitMs: 1_000 })
  w.watch(d)
  for (let i = 0; i < 5; i++) writeFileSync(join(d, `f${i}`), "")
  await sleep(250)
  expect(flushed).toEqual([d])
  w.closeAll()
})

test("constant churn still flushes at least every maxWait", async () => {
  const d = tmp()
  const flushed: number[] = []
  const w = new DirWatchers(() => flushed.push(Date.now()), { debounceMs: 100, maxWaitMs: 150 })
  w.watch(d)
  const end = Date.now() + 500
  let i = 0
  while (Date.now() < end) { writeFileSync(join(d, `c${i++ % 3}`), String(i)); await sleep(20) }
  await sleep(200)
  expect(flushed.length).toBeGreaterThanOrEqual(3)
  w.closeAll()
})

test("unwatch stops flushes; watch is idempotent", async () => {
  const d = tmp()
  let n = 0
  const w = new DirWatchers(() => n++, { debounceMs: 30 })
  w.watch(d); w.watch(d)
  expect(w.size).toBe(1)
  w.unwatch(d)
  writeFileSync(join(d, "x"), "")
  await sleep(120)
  expect(n).toBe(0)
})

test("when watching fails, the folder is polled instead", async () => {
  const d = tmp()
  const flushed: string[] = []
  const fallbacks: string[] = []
  const w = new DirWatchers((dir) => flushed.push(dir), {
    pollMs: 40,
    watchFn: () => { throw Object.assign(new Error("no watches"), { code: "ENOSPC" }) },
    onFallback: (dir) => fallbacks.push(dir),
  })
  w.watch(d)
  await sleep(130)
  expect(fallbacks).toEqual([d])
  expect(flushed.length).toBeGreaterThanOrEqual(2)
  w.closeAll()
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/fs/dir-watchers.test.ts`
Expected: FAIL (cannot resolve `./dir-watchers`).

- [ ] **Step 3: Write the implementation**

```ts
// src/core/fs/dir-watchers.ts
import { watch as fsWatch, type FSWatcher } from "fs"

export interface DirWatchersOpts {
  debounceMs?: number
  maxWaitMs?: number
  pollMs?: number
  watchFn?: (dir: string, listener: () => void) => FSWatcher
  onFallback?: (dir: string) => void
}

interface Slot { watcher?: FSWatcher; poll?: ReturnType<typeof setInterval>; timer?: ReturnType<typeof setTimeout>; firstAt?: number }

const defaultWatch = (dir: string, listener: () => void) => fsWatch(dir, { persistent: false }, listener)

/** One NON-recursive watch per folder. Events are debounced into `onFlush(dir)`. */
export class DirWatchers {
  private readonly slots = new Map<string, Slot>()
  private readonly debounceMs: number
  private readonly maxWaitMs: number
  private readonly pollMs: number
  private readonly watchFn: NonNullable<DirWatchersOpts["watchFn"]>

  constructor(private readonly onFlush: (dir: string) => void, private readonly opts: DirWatchersOpts = {}) {
    this.debounceMs = opts.debounceMs ?? 100
    this.maxWaitMs = opts.maxWaitMs ?? 1_000
    this.pollMs = opts.pollMs ?? 5_000
    this.watchFn = opts.watchFn ?? defaultWatch
  }

  get size(): number { return this.slots.size }
  has(dir: string): boolean { return this.slots.has(dir) }

  watch(dir: string): void {
    if (this.slots.has(dir)) return
    const slot: Slot = {}
    this.slots.set(dir, slot)
    try {
      slot.watcher = this.watchFn(dir, () => this.schedule(dir))
      slot.watcher.on?.("error", () => {
        // The folder vanished or the watch broke: flush (the reload reports gone) and stop.
        try { slot.watcher?.close() } catch {}
        slot.watcher = undefined
        this.schedule(dir)
      })
    } catch {
      this.opts.onFallback?.(dir)
      slot.poll = setInterval(() => this.onFlush(dir), this.pollMs)
    }
  }

  unwatch(dir: string): void {
    const slot = this.slots.get(dir)
    if (!slot) return
    this.slots.delete(dir)
    if (slot.timer) clearTimeout(slot.timer)
    if (slot.poll) clearInterval(slot.poll)
    try { slot.watcher?.close() } catch {}
  }

  closeAll(): void {
    for (const dir of [...this.slots.keys()]) this.unwatch(dir)
  }

  private schedule(dir: string): void {
    const slot = this.slots.get(dir)
    if (!slot) return
    const now = Date.now()
    slot.firstAt ??= now
    if (slot.timer) clearTimeout(slot.timer)
    const wait = Math.max(0, Math.min(this.debounceMs, this.maxWaitMs - (now - slot.firstAt)))
    slot.timer = setTimeout(() => {
      slot.timer = undefined
      slot.firstAt = undefined
      if (this.slots.get(dir) === slot) this.onFlush(dir)
    }, wait)
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/fs/dir-watchers.test.ts`
Expected: 4 pass.

- [ ] **Step 5: Commit**

```bash
git add src/core/fs/dir-watchers.ts src/core/fs/dir-watchers.test.ts
git commit -m "feat(fs): per-folder non-recursive watchers with debounce and a polling fallback"
```

---

### Task 9: SubscriptionRegistry

**Files:**
- Create: `src/core/fs/subscriptions.ts`
- Test: `src/core/fs/subscriptions.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// src/core/fs/subscriptions.test.ts
import { test, expect } from "bun:test"
import { SubscriptionRegistry } from "./subscriptions"

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

function reg(graceMs = 30, maxPerSocket = 500) {
  const events: string[] = []
  const r = new SubscriptionRegistry<string>({
    onFirst: (real) => events.push(`first ${real}`),
    onLast: (real) => events.push(`last ${real}`),
  }, { graceMs, maxPerSocket })
  return { r, events }
}

test("first subscriber starts, others share, the last leaves after the grace period", async () => {
  const { r, events } = reg()
  expect(r.add("s1", "/a", "/real/a")).toBe("ok")
  expect(r.add("s2", "/link/a", "/real/a")).toBe("ok")
  expect(events).toEqual(["first /real/a"])
  expect(r.subscribersOf("/real/a")).toEqual([{ sock: "s1", path: "/a" }, { sock: "s2", path: "/link/a" }])
  r.remove("s1", "/a")
  r.remove("s2", "/link/a")
  expect(events).toEqual(["first /real/a"])
  await sleep(60)
  expect(events).toEqual(["first /real/a", "last /real/a"])
})

test("re-subscribing within the grace period cancels the teardown", async () => {
  const { r, events } = reg()
  r.add("s1", "/a", "/real/a")
  r.remove("s1", "/a")
  r.add("s1", "/a", "/real/a")
  await sleep(60)
  expect(events).toEqual(["first /real/a"])
  expect(r.isWatched("/real/a")).toBe(true)
})

test("dropSocket removes every subscription of that socket", async () => {
  const { r, events } = reg()
  r.add("s1", "/a", "/real/a")
  r.add("s1", "/b", "/real/b")
  r.dropSocket("s1")
  expect(r.count("s1")).toBe(0)
  await sleep(60)
  expect(events.sort()).toEqual(["first /real/a", "first /real/b", "last /real/a", "last /real/b"])
})

test("adding the same subscription twice is idempotent; the per-socket cap is enforced", () => {
  const { r } = reg(30, 2)
  expect(r.add("s1", "/a", "/real/a")).toBe("ok")
  expect(r.add("s1", "/a", "/real/a")).toBe("ok")
  expect(r.count("s1")).toBe(1)
  expect(r.add("s1", "/b", "/real/b")).toBe("ok")
  expect(r.add("s1", "/c", "/real/c")).toBe("limit")
})

test("removeAllFor drops a folder for everyone at once without a grace period", () => {
  const { r, events } = reg()
  r.add("s1", "/a", "/real/a")
  r.add("s2", "/a", "/real/a")
  const removed = r.removeAllFor("/real/a")
  expect(removed).toEqual([{ sock: "s1", path: "/a" }, { sock: "s2", path: "/a" }])
  expect(events).toEqual(["first /real/a", "last /real/a"])
  expect(r.count("s1")).toBe(0)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/fs/subscriptions.test.ts`
Expected: FAIL (cannot resolve `./subscriptions`).

- [ ] **Step 3: Write the implementation**

```ts
// src/core/fs/subscriptions.ts
export interface SubscriptionHooks { onFirst: (real: string) => void; onLast: (real: string) => void }

/** Who watches which real folder. `S` is the socket handle (a ServerWebSocket in the broker). */
export class SubscriptionRegistry<S> {
  private readonly byDir = new Map<string, Map<S, Set<string>>>()
  private readonly bySocket = new Map<S, Map<string, string>>() // path → real
  private readonly lingering = new Map<string, ReturnType<typeof setTimeout>>()
  private readonly graceMs: number
  private readonly maxPerSocket: number

  constructor(private readonly hooks: SubscriptionHooks, opts: { graceMs?: number; maxPerSocket?: number } = {}) {
    this.graceMs = opts.graceMs ?? 10_000
    this.maxPerSocket = opts.maxPerSocket ?? 500
  }

  count(sock: S): number { return this.bySocket.get(sock)?.size ?? 0 }
  isWatched(real: string): boolean { return this.byDir.has(real) || this.lingering.has(real) }
  realOf(sock: S, path: string): string | undefined { return this.bySocket.get(sock)?.get(path) }

  /** Every real folder that currently has at least one subscriber. */
  watchedReals(): string[] { return [...this.byDir.keys()] }

  add(sock: S, path: string, real: string): "ok" | "limit" {
    const mine = this.bySocket.get(sock) ?? new Map<string, string>()
    if (mine.get(path) === real) return "ok"
    if (!mine.has(path) && mine.size >= this.maxPerSocket) return "limit"
    if (mine.has(path)) this.remove(sock, path)
    mine.set(path, real)
    this.bySocket.set(sock, mine)

    let subs = this.byDir.get(real)
    if (!subs) {
      subs = new Map()
      this.byDir.set(real, subs)
      const t = this.lingering.get(real)
      if (t) { clearTimeout(t); this.lingering.delete(real) } else this.hooks.onFirst(real)
    }
    const paths = subs.get(sock) ?? new Set<string>()
    paths.add(path)
    subs.set(sock, paths)
    return "ok"
  }

  remove(sock: S, path: string): void {
    const mine = this.bySocket.get(sock)
    const real = mine?.get(path)
    if (!mine || real === undefined) return
    mine.delete(path)
    if (mine.size === 0) this.bySocket.delete(sock)
    const subs = this.byDir.get(real)
    const paths = subs?.get(sock)
    paths?.delete(path)
    if (paths && paths.size === 0) subs!.delete(sock)
    if (subs && subs.size === 0) {
      this.byDir.delete(real)
      this.lingering.set(real, setTimeout(() => {
        this.lingering.delete(real)
        if (!this.byDir.has(real)) this.hooks.onLast(real)
      }, this.graceMs))
    }
  }

  dropSocket(sock: S): void {
    for (const path of [...(this.bySocket.get(sock)?.keys() ?? [])]) this.remove(sock, path)
  }

  subscribersOf(real: string): Array<{ sock: S; path: string }> {
    const out: Array<{ sock: S; path: string }> = []
    for (const [sock, paths] of this.byDir.get(real) ?? []) for (const path of paths) out.push({ sock, path })
    return out
  }

  /** The folder is gone: drop it for everyone immediately (no grace). */
  removeAllFor(real: string): Array<{ sock: S; path: string }> {
    const subs = this.subscribersOf(real)
    for (const { sock, path } of subs) {
      const mine = this.bySocket.get(sock)
      mine?.delete(path)
      if (mine && mine.size === 0) this.bySocket.delete(sock)
    }
    const had = this.byDir.delete(real)
    const t = this.lingering.get(real)
    if (t) { clearTimeout(t); this.lingering.delete(real) }
    if (had || t) this.hooks.onLast(real)
    return subs
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/fs/subscriptions.test.ts`
Expected: 5 pass.

- [ ] **Step 5: Commit**

```bash
git add src/core/fs/subscriptions.ts src/core/fs/subscriptions.test.ts
git commit -m "feat(fs): ref-counted folder subscriptions with a grace period"
```

---

### Task 10: Subscriptions in FileSystemService (watch → reload → push; git dir watch)

**Files:**
- Modify: `src/core/fs/file-system-service.ts`
- Test: `src/core/fs/file-system-service.subs.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// src/core/fs/file-system-service.subs.test.ts
import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, realpathSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { execFileSync } from "child_process"
import { FileSystemService } from "./file-system-service"
import type { FsFrame } from "./types"

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const tmp = () => realpathSync(mkdtempSync(join(tmpdir(), "fss-sub-")))

function harness(extra: Partial<ConstructorParameters<typeof FileSystemService<string>>[0]> = {}) {
  const frames: Array<{ sock: string; f: FsFrame }> = []
  const fss = new FileSystemService<string>({
    bootId: "b", emit: (sock, f) => frames.push({ sock, f }),
    debounceMs: 30, graceMs: 50, ...extra,
  })
  const waitFor = async (pred: (f: FsFrame, sock: string) => boolean, ms = 2_000) => {
    const end = Date.now() + ms
    while (Date.now() < end) {
      const hit = frames.find((x) => pred(x.f, x.sock))
      if (hit) return hit
      await sleep(10)
    }
    throw new Error("timed out; frames: " + JSON.stringify(frames.map((x) => x.f.type)))
  }
  return { fss, frames, waitFor }
}

test("subscribe replies with a snapshot, then pushes a new one when the folder changes", async () => {
  const d = tmp()
  const { fss, frames, waitFor } = harness()
  await fss.subscribe("s1", d)
  expect(frames[0]!.f).toMatchObject({ type: "fs_dir", path: d, version: "b:1", entries: [] })
  writeFileSync(join(d, "new.txt"), "")
  const next = await waitFor((f) => f.type === "fs_dir" && "entries" in f && f.entries.length === 1)
  expect(next.f).toMatchObject({ version: "b:2" })
  fss.close()
})

test("since equal to the current version gets unchanged:true", async () => {
  const d = tmp()
  const { fss, frames } = harness()
  await fss.subscribe("s1", d)
  await fss.subscribe("s2", d, "b:1")
  expect(frames[1]!.f).toEqual({ type: "fs_dir", path: d, version: "b:1", unchanged: true })
  fss.close()
})

test("a folder shared by two sockets under two paths is watched once and pushed to both", async () => {
  const d = tmp()
  mkdirSync(join(d, "real"))
  execFileSync("ln", ["-s", join(d, "real"), join(d, "link")])
  const { fss, frames, waitFor } = harness()
  await fss.subscribe("s1", join(d, "real"))
  await fss.subscribe("s2", join(d, "link"))
  expect(fss.watcherCount).toBe(1)
  writeFileSync(join(d, "real", "x"), "")
  await waitFor((f, s) => s === "s1" && f.type === "fs_dir" && "entries" in f && f.entries.length === 1)
  const two = await waitFor((f, s) => s === "s2" && f.type === "fs_dir" && "entries" in f && f.entries.length === 1)
  expect(two.f).toMatchObject({ path: join(d, "link") })
  expect(frames.filter((x) => x.f.type === "fs_dir").length).toBe(4)
  fss.close()
})

test("deleting a subscribed folder sends fs_gone and drops the subscription", async () => {
  const d = tmp()
  mkdirSync(join(d, "sub"))
  const { fss, waitFor } = harness()
  await fss.subscribe("s1", join(d, "sub"))
  rmSync(join(d, "sub"), { recursive: true })
  await waitFor((f) => f.type === "fs_gone")
  expect(fss.watcherCount).toBe(0)
  fss.close()
})

test("subscribing to a missing folder sends fs_err and holds nothing", async () => {
  const { fss, frames } = harness()
  await fss.subscribe("s1", "/no/such/dir")
  expect(frames[0]!.f).toMatchObject({ type: "fs_err", path: "/no/such/dir", code: "ENOENT" })
  expect(fss.watcherCount).toBe(0)
  await fss.subscribe("s1", "relative")
  expect(frames[1]!.f).toMatchObject({ type: "fs_err", code: "EINVAL" })
  fss.close()
})

test("the last unsubscribe stops the watcher after the grace period; dropSocket does too", async () => {
  const d = tmp()
  const { fss } = harness()
  await fss.subscribe("s1", d)
  fss.unsubscribe("s1", d)
  expect(fss.watcherCount).toBe(1)
  await sleep(120)
  expect(fss.watcherCount).toBe(0)
  await fss.subscribe("s2", d)
  fss.dropSocket("s2")
  await sleep(120)
  expect(fss.watcherCount).toBe(0)
  fss.close()
})

test("the socket cap answers TOO_MANY_SUBS", async () => {
  const a = tmp(), b = tmp()
  const { fss, frames } = harness({ maxSubsPerSocket: 1 })
  await fss.subscribe("s1", a)
  await fss.subscribe("s1", b)
  expect(frames[1]!.f).toMatchObject({ type: "fs_err", code: "TOO_MANY_SUBS" })
  fss.close()
})

test("git add pushes the new status of a subscribed folder (git dir watch)", async () => {
  const d = tmp()
  execFileSync("git", ["init", "-q"], { cwd: d })
  writeFileSync(join(d, "a.txt"), "a")
  const { fss, waitFor } = harness()
  await fss.subscribe("s1", d)
  await waitFor((f) => f.type === "fs_dir" && "entries" in f && f.entries.some((e) => e.name === "a.txt" && e.git === "?"))
  execFileSync("git", ["add", "a.txt"], { cwd: d })
  await waitFor((f) => f.type === "fs_dir" && "entries" in f && f.entries.some((e) => e.name === "a.txt" && e.git === "A"), 4_000)
  fss.close()
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/fs/file-system-service.subs.test.ts`
Expected: FAIL (`FileSystemService` is not generic / no `subscribe`).

- [ ] **Step 3: Extend the service**

Replace `src/core/fs/file-system-service.ts` with:

```ts
// src/core/fs/file-system-service.ts
import { randomBytes } from "crypto"
import { dirname, sep } from "path"
import { gitAsync } from "../git/exec"
import { DirCache, type CachedDir } from "./dir-cache"
import { DirWatchers } from "./dir-watchers"
import { toFsError } from "./errors"
import { applyOp, readText, statEntry, writeText, type OpOptions } from "./file-ops"
import { normalizeAbsPath, realKey } from "./paths"
import { RepoInfoCache } from "./repo-info"
import { SearchIndexes } from "./search-index"
import { SubscriptionRegistry } from "./subscriptions"
import type { DirSnapshot, FsEntry, FsFrame, FsOp, SearchHit } from "./types"
import { makeLogger } from "../../shared/log"

const log = makeLogger("fs")

export interface FileSystemServiceOpts<S> {
  bootId?: string
  trashDir?: string
  maxEntries?: number
  maxDirs?: number
  emit?: (sock: S, frame: FsFrame) => void
  debounceMs?: number
  graceMs?: number
  maxSubsPerSocket?: number
}

const toSnapshot = (path: string, c: CachedDir): DirSnapshot => ({
  path, real: c.real, version: c.version, entries: c.entries, ...(c.truncated ? { truncated: c.truncated } : {}),
})

const dirFrame = (path: string, c: CachedDir): FsFrame => ({
  type: "fs_dir", path, version: c.version, entries: c.entries, ...(c.truncated ? { truncated: c.truncated } : {}),
})

const isUnder = (root: string, p: string) => p === root || p.startsWith(root.endsWith(sep) ? root : root + sep)

/** The host's one file-system service: addressed by absolute path, shared by every caller. */
export class FileSystemService<S = unknown> {
  readonly bootId: string
  readonly repo = new RepoInfoCache()
  readonly cache: DirCache
  private readonly searches: SearchIndexes
  private readonly opOpts: OpOptions
  private readonly emit: (sock: S, frame: FsFrame) => void
  private readonly subs: SubscriptionRegistry<S>
  private readonly watchers: DirWatchers
  private readonly gitWatchers: DirWatchers
  /** git dir → repo root, and repo root → number of watched folders inside it. */
  private readonly gitDirOf = new Map<string, string>()
  private readonly repoRefs = new Map<string, number>()
  private readonly repoOfWatched = new Map<string, string>()

  constructor(opts: FileSystemServiceOpts<S> = {}) {
    this.bootId = opts.bootId ?? randomBytes(4).toString("hex")
    this.cache = new DirCache({ repo: this.repo, bootId: this.bootId, maxEntries: opts.maxEntries, maxDirs: opts.maxDirs })
    this.searches = new SearchIndexes(this.repo)
    this.opOpts = { trashDir: opts.trashDir }
    this.emit = opts.emit ?? (() => {})
    this.watchers = new DirWatchers((real) => void this.onFlush(real), {
      debounceMs: opts.debounceMs,
      onFallback: (dir) => log.warn("fs_watch_fallback", { dir }),
    })
    this.gitWatchers = new DirWatchers((gitDir) => void this.onGitFlush(gitDir), { debounceMs: Math.max(opts.debounceMs ?? 100, 50) * 5 })
    this.subs = new SubscriptionRegistry<S>({
      onFirst: (real) => { this.cache.pin(real); this.watchers.watch(real); void this.trackRepo(real) },
      onLast: (real) => { this.watchers.unwatch(real); this.cache.unpin(real); this.untrackRepo(real) },
    }, { graceMs: opts.graceMs, maxPerSocket: opts.maxSubsPerSocket })
  }

  get watcherCount(): number { return this.watchers.size }

  // ── request / response ────────────────────────────────────────────────────

  async list(path: string): Promise<DirSnapshot> {
    const p = normalizeAbsPath(path)
    const real = await realKey(p)
    const c = this.cache.isPinned(real) ? await this.cache.getOrLoad(real) : (await this.cache.load(real)).snap
    return toSnapshot(p, c)
  }

  async stat(path: string): Promise<FsEntry & { real: string }> {
    return statEntry(normalizeAbsPath(path))
  }

  async read(path: string): Promise<string> {
    return readText(normalizeAbsPath(path))
  }

  async write(path: string, text: string): Promise<{ size: number; mtime: number }> {
    const p = normalizeAbsPath(path)
    const r = await writeText(p, text)
    await this.refresh([dirname(p)])
    return r
  }

  async search(scope: string, q: string, limit = 50): Promise<SearchHit[]> {
    const s = await realKey(normalizeAbsPath(scope))
    return this.searches.query(s, q, limit)
  }

  async op(op: FsOp): Promise<void> {
    const path = normalizeAbsPath(op.path)
    const norm: FsOp = "to" in op ? { ...op, path, to: normalizeAbsPath(op.to) } : { ...op, path }
    await applyOp(norm, this.opOpts)
    await this.refresh("to" in norm ? [dirname(norm.path), dirname(norm.to)] : [dirname(norm.path)])
  }

  // ── subscriptions ─────────────────────────────────────────────────────────

  async subscribe(sock: S, path: string, since?: string): Promise<void> {
    let p = path
    try {
      p = normalizeAbsPath(path)
      const real = await realKey(p)
      if (this.subs.add(sock, p, real) === "limit") {
        this.emit(sock, { type: "fs_err", path, code: "TOO_MANY_SUBS", message: "too many folder subscriptions on this connection" })
        return
      }
      const c = await this.cache.getOrLoad(real)
      this.emit(sock, since !== undefined && since === c.version
        ? { type: "fs_dir", path: p, version: c.version, unchanged: true }
        : dirFrame(p, c))
    } catch (e) {
      const err = toFsError(e)
      const real = this.subs.realOf(sock, p)
      if (real !== undefined) this.subs.remove(sock, p)
      this.emit(sock, { type: "fs_err", path, code: err.code, message: err.message })
    }
  }

  unsubscribe(sock: S, path: string): void {
    try { this.subs.remove(sock, normalizeAbsPath(path)) } catch { /* bad path: nothing to remove */ }
  }

  dropSocket(sock: S): void {
    this.subs.dropSocket(sock)
  }

  close(): void {
    this.watchers.closeAll()
    this.gitWatchers.closeAll()
  }

  // ── change propagation ────────────────────────────────────────────────────

  private async refresh(dirs: string[]): Promise<void> {
    for (const d of new Set(dirs)) {
      const real = await realKey(d).catch(() => null)
      if (real && this.cache.get(real)) await this.onFlush(real)
    }
  }

  /** A watched (or cached) folder may have changed: re-read it and its watched ancestors in the repo. */
  private async onFlush(real: string): Promise<void> {
    const root = this.repo.knownRepoFor(real)
    if (root) this.repo.invalidate(root)
    await this.reloadAndPush(real)
    if (root) {
      for (const other of this.subs.watchedReals()) {
        if (other !== real && isUnder(other, real) && isUnder(root, other)) await this.reloadAndPush(other)
      }
    }
  }

  private async onGitFlush(gitDir: string): Promise<void> {
    const root = this.gitDirOf.get(gitDir)
    if (!root) return
    this.repo.invalidate(root)
    for (const real of this.subs.watchedReals()) if (isUnder(root, real)) await this.reloadAndPush(real)
  }

  private async reloadAndPush(real: string): Promise<void> {
    let res: { snap: CachedDir; changed: boolean }
    try {
      res = await this.cache.load(real)
    } catch (e) {
      if (toFsError(e).code === "ENOENT" || toFsError(e).code === "ENOTDIR") {
        for (const { sock, path } of this.subs.removeAllFor(real)) this.emit(sock, { type: "fs_gone", path })
        this.cache.forget(real)
      }
      return
    }
    if (!res.changed) return
    for (const { sock, path } of this.subs.subscribersOf(real)) this.emit(sock, dirFrame(path, res.snap))
  }

  // ── git dir watching (index / HEAD changes don't touch the working folders) ─

  private async trackRepo(real: string): Promise<void> {
    const root = await this.repo.repoFor(real)
    if (!root || !this.subs.isWatched(real)) return
    this.repoOfWatched.set(real, root)
    const n = (this.repoRefs.get(root) ?? 0) + 1
    this.repoRefs.set(root, n)
    if (n > 1) return
    const gitDir = await gitAsync(root, ["rev-parse", "--absolute-git-dir"], { timeoutMs: 5_000 }).catch(() => null)
    if (!gitDir || !this.repoRefs.has(root)) return
    this.gitDirOf.set(gitDir, root)
    this.gitWatchers.watch(gitDir)
  }

  private untrackRepo(real: string): void {
    const root = this.repoOfWatched.get(real)
    if (!root) return
    this.repoOfWatched.delete(real)
    const n = (this.repoRefs.get(root) ?? 1) - 1
    if (n > 0) { this.repoRefs.set(root, n); return }
    this.repoRefs.delete(root)
    for (const [gitDir, r] of this.gitDirOf) {
      if (r === root) { this.gitWatchers.unwatch(gitDir); this.gitDirOf.delete(gitDir) }
    }
  }
}
```


- [ ] **Step 4: Run tests**

Run: `bun test src/core/fs`
Expected: all pass (Task 6 tests still pass: `FileSystemService` without a type argument defaults `S = unknown`).

- [ ] **Step 5: Commit**

```bash
git add src/core/fs/file-system-service.ts src/core/fs/file-system-service.subs.test.ts
git commit -m "feat(fs): folder subscriptions push fresh snapshots; git index/HEAD changes re-annotate"
```

---

### Task 11: Web channel wiring: WS frames, `/fs/*` routes, dropSocket, editor_open leak

**Files:**
- Modify: `src/channels/web/index.ts`
- Test: `src/channels/web/fs-routes.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// src/channels/web/fs-routes.test.ts
// Live WebChannel on an ephemeral port, bearer token, real temp folders.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, writeFileSync, realpathSync, existsSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function boot() {
  const dir = mkdtempSync(join(tmpdir(), "mux-fs-routes-"))
  const devicesFile = join(dir, "devices.json")
  const opts: WebChannelOpts = {
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    updateChecker: null,
  }
  channel = new WebChannel(opts)
  await channel.start()
  const token = new DeviceStore(devicesFile).mint("t").token
  const base = `http://127.0.0.1:${channel.boundPort}`
  const auth = { Authorization: `Bearer ${token}` }
  return { base, auth, token }
}

const tmp = () => realpathSync(mkdtempSync(join(tmpdir(), "fs-http-")))

test("GET /fs/list returns a snapshot for an absolute path", async () => {
  const { base, auth } = await boot()
  const d = tmp()
  writeFileSync(join(d, "a.txt"), "hi")
  const r = await fetch(`${base}/fs/list?path=${encodeURIComponent(d)}`, { headers: auth })
  expect(r.status).toBe(200)
  const body = await r.json()
  expect(body).toMatchObject({ path: d, real: d })
  expect(body.entries.map((e: any) => e.name)).toEqual(["a.txt"])
})

test("/fs/* maps errors to status codes and requires a token", async () => {
  const { base, auth } = await boot()
  expect((await fetch(`${base}/fs/list?path=relative`, { headers: auth })).status).toBe(400)
  expect((await fetch(`${base}/fs/list?path=/no/such/dir`, { headers: auth })).status).toBe(404)
  expect((await fetch(`${base}/fs/list?path=/tmp`)).status).toBe(401)
})

test("read, write, stat, search and ops over HTTP", async () => {
  const { base, auth } = await boot()
  const d = tmp()
  const f = join(d, "src", "FileTree.kt")
  let r = await fetch(`${base}/fs/write?path=${encodeURIComponent(f)}`, { method: "PUT", headers: auth, body: "hello" })
  expect(r.status).toBe(200)
  expect((await r.json()).size).toBe(5)
  expect(await (await fetch(`${base}/fs/read?path=${encodeURIComponent(f)}`, { headers: auth })).text()).toBe("hello")
  expect((await (await fetch(`${base}/fs/stat?path=${encodeURIComponent(f)}`, { headers: auth })).json()).type).toBe("file")
  const hits = await (await fetch(`${base}/fs/search?scope=${encodeURIComponent(d)}&q=ftree`, { headers: auth })).json()
  expect(hits[0].path).toBe(f)
  r = await fetch(`${base}/fs/ops`, {
    method: "POST", headers: { ...auth, "content-type": "application/json" },
    body: JSON.stringify({ op: "rename", path: f, to: join(d, "src", "Tree.kt") }),
  })
  expect(r.status).toBe(204)
  expect(existsSync(join(d, "src", "Tree.kt"))).toBe(true)
  r = await fetch(`${base}/fs/ops`, {
    method: "POST", headers: { ...auth, "content-type": "application/json" },
    body: JSON.stringify({ op: "touch", path: join(d, "src", "Tree.kt") }),
  })
  expect(r.status).toBe(409)
})

test("fs_sub over the WebSocket gets a snapshot and live updates", async () => {
  const { base, token } = await boot()
  const d = tmp()
  const ws = new WebSocket(base.replace("http", "ws") + "/ws", { headers: { Authorization: `Bearer ${token}` } } as any)
  const frames: any[] = []
  ws.onmessage = (e) => { const f = JSON.parse(String(e.data)); if (String(f.type).startsWith("fs_")) frames.push(f) }
  await new Promise((r) => (ws.onopen = r))
  ws.send(JSON.stringify({ type: "fs_sub", path: d }))
  const until = async (pred: () => boolean) => { const end = Date.now() + 3_000; while (!pred() && Date.now() < end) await new Promise((r) => setTimeout(r, 20)) }
  await until(() => frames.length >= 1)
  expect(frames[0]).toMatchObject({ type: "fs_dir", path: d, entries: [] })
  writeFileSync(join(d, "new.txt"), "")
  await until(() => frames.length >= 2)
  expect(frames[1].entries.map((e: any) => e.name)).toEqual(["new.txt"])
  ws.close()
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/channels/web/fs-routes.test.ts`
Expected: FAIL (404s from the unknown `/fs/*` routes; no `fs_dir` frames).

- [ ] **Step 3: Wire it**

In `src/channels/web/index.ts`:

1. Make the field typed by socket and emitting to it (replace the Task 7 lines):
   ```ts
   readonly fss: FileSystemService<import("bun").ServerWebSocket<WSData>>
   ```
   ```ts
   this.fss = new FileSystemService<import("bun").ServerWebSocket<WSData>>({
     emit: (ws, frame) => { try { ws.send(JSON.stringify(frame)) } catch {} },
   })
   ```
2. In `stop()`, before the server is stopped, add `this.fss.close()`.
3. In `onWsClose` (~line 904), first line:
   ```ts
   this.fss.dropSocket(ws)
   ```
4. In the frame handler next to `if (frame.type === "editor_open" && frame.session) {` (~line 1430), add before it:
   ```ts
   if (frame.type === "fs_sub" && typeof frame.path === "string") {
     void this.fss.subscribe(ws, frame.path, typeof frame.since === "string" ? frame.since : undefined)
     return
   }
   if (frame.type === "fs_unsub" && typeof frame.path === "string") {
     this.fss.unsubscribe(ws, frame.path)
     return
   }
   ```
5. Fix the `editor_open` leak: inside the `editor_open` branch, before `;(ws.data as any)._editorCb = cb`, add:
   ```ts
   const prevCb = (ws.data as any)._editorCb
   if (prevCb) this.fsWatcher.unsubscribe((ws.data as any)._editorSession, prevCb)
   ```
6. Add the HTTP routes right after the `// ── Editor filesystem routes, workspace-scoped` block ends (after the `/workspaces/:id/fs/refs` handler):
   ```ts
   // ── Host file system (spec 2026-09-27): absolute paths, same trust as a terminal ──────────
   if (path === "/fs/list" || path === "/fs/stat" || path === "/fs/read" || path === "/fs/write" || path === "/fs/search" || path === "/fs/ops") {
     const p = url.searchParams.get("path") ?? ""
     try {
       if (method === "GET" && path === "/fs/list") return this.json(await this.fss.list(p))
       if (method === "GET" && path === "/fs/stat") return this.json(await this.fss.stat(p))
       if (method === "GET" && path === "/fs/read") {
         return new Response(await this.fss.read(p), { headers: { "content-type": "text/plain; charset=utf-8" } })
       }
       if (method === "PUT" && path === "/fs/write") return this.json(await this.fss.write(p, await req.text()))
       if (method === "GET" && path === "/fs/search") {
         const limit = Number(url.searchParams.get("limit") ?? "50") || 50
         return this.json(await this.fss.search(url.searchParams.get("scope") ?? "", url.searchParams.get("q") ?? "", limit))
       }
       if (method === "POST" && path === "/fs/ops") {
         const body = await req.json().catch(() => null)
         if (!body || typeof body.op !== "string" || typeof body.path !== "string") return this.json({ error: "EINVAL", message: "op and path required" }, 400)
         await this.fss.op(body)
         return new Response(null, { status: 204 })
       }
       return this.json({ error: "method not allowed" }, 405)
     } catch (e) {
       return this.fsErrorResponse(e)
     }
   }
   ```
   and a private helper on the class:
   ```ts
   private fsErrorResponse(e: unknown): Response {
     const err = toFsError(e)
     const status: Record<string, number> = { EINVAL: 400, ENOENT: 404, ENOTDIR: 400, EISDIR: 400, EACCES: 403, EEXIST: 409, ENOTEMPTY: 409, TOO_LARGE: 413, BINARY: 415 }
     return this.json({ error: err.code, message: err.message }, status[err.code] ?? 500)
   }
   ```
   with `import { toFsError } from "../../core/fs/errors"` at the top.
7. Confirm the route block sits inside the authenticated API handler: run the test; the 401 assertion covers it. If the unauthenticated request gets 200, move the block below the auth check used by the other `/workspaces/*` routes.

- [ ] **Step 4: Run tests**

Run: `bun test src/channels/web/fs-routes.test.ts src/channels/web/workspace-fs.test.ts src/core/fs`
Expected: all pass.
Run: `bun test src/channels/web`
Expected: no new failures compared with before this task (note any pre-existing failures in the task report).

- [ ] **Step 5: Commit**

```bash
git add src/channels/web/index.ts src/channels/web/fs-routes.test.ts
git commit -m "feat(web): host fs service over WebSocket (fs_sub/fs_dir) and /fs/* routes

Also fixes the editor_open watcher leak: a second editor_open on the same socket used to replace
the stored callback without unsubscribing the first."
```

---

# Part A3: app service (Kotlin)

### Task 12: Kotlin models and frames

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/fs/FsModels.kt`
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt:793-799` (FsEntry)
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt` (ServerFrame near `FsChanged` ~line 476; ClientFrame near `EditorOpen` ~line 607)
- Create: `apps/shared/src/jvmTest/resources/frames/fs_dir.json`, `fs_gone.json`, `fs_err.json`
- Modify: `apps/shared/src/jvmTest/kotlin/dev/supermux/proto/ContractTest.kt`

- [ ] **Step 1: Add the fixtures and extend the contract test (failing)**

`apps/shared/src/jvmTest/resources/frames/fs_dir.json`:
```json
{"type":"fs_dir","path":"/home/u/p/src","version":"a1b2:7","entries":[{"name":"ui","type":"dir","mtime":1790000000000,"ignored":false,"git":"*"},{"name":"link","type":"symlink","target":"file","size":12,"mtime":1790000000000,"ignored":false},{"name":"a.kt","type":"file","size":42,"mtime":1790000000000,"ignored":false,"git":"M"}],"truncated":{"total":9000}}
```
`apps/shared/src/jvmTest/resources/frames/fs_gone.json`:
```json
{"type":"fs_gone","path":"/home/u/p/src"}
```
`apps/shared/src/jvmTest/resources/frames/fs_err.json`:
```json
{"type":"fs_err","path":"/root","code":"EACCES","message":"permission denied"}
```

In `ContractTest.kt` add `"fs_dir", "fs_gone", "fs_err"` to `names`, and add to the `when`:
```kotlin
                is ServerFrame.FsDir -> {}
                is ServerFrame.FsGone -> {}
                is ServerFrame.FsErr -> {}
```
Also add a test for the `unchanged` form and the client frames:
```kotlin
    @Test fun fs_dir_unchanged_and_client_fs_frames_round_trip() {
        val f = json.decodeFromString<ServerFrame>("""{"type":"fs_dir","path":"/a","version":"x:1","unchanged":true}""")
        kotlin.test.assertEquals(ServerFrame.FsDir(path = "/a", version = "x:1", unchanged = true), f)
        val out = json.encodeToString(ClientFrame.serializer(), ClientFrame.FsSub("/a", since = "x:1"))
        kotlin.test.assertEquals("""{"type":"fs_sub","path":"/a","since":"x:1"}""", out)
        val un = json.encodeToString(ClientFrame.serializer(), ClientFrame.FsUnsub("/a"))
        kotlin.test.assertEquals("""{"type":"fs_unsub","path":"/a"}""", un)
    }
```

Run (from `apps/`): `./gradlew :shared:jvmTest --tests 'dev.supermux.proto.ContractTest' --max-workers=2`
Expected: compile FAIL (unresolved `ServerFrame.FsDir` etc.).

- [ ] **Step 2: Implement models and frames**

`apps/shared/src/commonMain/kotlin/dev/supermux/fs/FsModels.kt`:
```kotlin
// Wire models of the host file-system service (spec docs/superpowers/specs/2026-09-27-host-filesystem-service-design.md).
package dev.supermux.fs

import dev.supermux.net.FsEntry
import kotlinx.serialization.Serializable

@Serializable
data class FsTruncated(val total: Int)

@Serializable
data class DirSnapshot(
    val path: String,
    val real: String = path,
    val version: String,
    val entries: List<FsEntry> = emptyList(),
    val truncated: FsTruncated? = null,
)

@Serializable
data class FsStat(
    val name: String,
    val type: String,
    val size: Long? = null,
    val mtime: Long? = null,
    val ignored: Boolean = false,
    val git: String? = null,
    val target: String? = null,
    val real: String,
)

@Serializable
data class SearchHit(
    val path: String,
    val name: String,
    val type: String,
    val score: Double,
    val hits: List<Int> = emptyList(),
)

@Serializable
data class FsWriteResult(val size: Long, val mtime: Long)

/** POST /fs/ops body. `to` only for rename/move. */
@Serializable
data class FsOpRequest(val op: String, val path: String, val to: String? = null)
```

In `BrokerApi.kt`, extend `FsEntry` (keep existing fields and their defaults):
```kotlin
@Serializable
data class FsEntry(
    val name: String,
    val type: String,            // "dir" | "file" | "symlink" (symlink only from the host fs service)
    val size: Long = 0,
    val modified: String? = null, // legacy routes only
    val ignored: Boolean = false,
    val mtime: Long? = null,      // host fs service, epoch ms
    val git: String? = null,      // "M" | "A" | "D" | "R" | "?" | "U" | "*"
    val target: String? = null,   // symlinks: "file" | "dir"
)
```

In `Frames.kt`, next to `FsChanged` in `ServerFrame`:
```kotlin
    /** Host fs service: a folder snapshot, or `unchanged` when the client's `since` is current. */
    @Serializable @SerialName("fs_dir")
    data class FsDir(
        val path: String,
        val version: String,
        val entries: List<dev.supermux.net.FsEntry> = emptyList(),
        val unchanged: Boolean = false,
        val truncated: dev.supermux.fs.FsTruncated? = null,
    ) : ServerFrame

    /** The subscribed folder was deleted or moved; the broker dropped the subscription. */
    @Serializable @SerialName("fs_gone")
    data class FsGone(val path: String) : ServerFrame

    /** A subscription could not be made (ENOENT, EACCES, ENOTDIR, EINVAL, TOO_MANY_SUBS). */
    @Serializable @SerialName("fs_err")
    data class FsErr(val path: String, val code: String, val message: String = "") : ServerFrame
```
Next to `EditorClose` in `ClientFrame`:
```kotlin
    @Serializable @SerialName("fs_sub")
    data class FsSub(val path: String, val since: String? = null) : ClientFrame

    @Serializable @SerialName("fs_unsub")
    data class FsUnsub(val path: String) : ClientFrame
```
Check how the `json` used for client frames treats `null` (`explicitNulls`): the round-trip test asserts `since` present when set; if the encoder emits `"since":null` for `FsUnsub`-style nulls that's fine for the broker (it checks `typeof since === "string"`), but don't change global Json settings.

- [ ] **Step 3: Run tests**

Run (from `apps/`): `./gradlew :shared:jvmTest --tests 'dev.supermux.proto.ContractTest' --max-workers=2`
Expected: PASS.
Run: `grep -rn "when (frame)\|when(frame)" apps --include=*.kt | grep -v Test`
Expected: only `else ->`-terminated `when`s besides ContractTest (confirm by reading each hit; add branches if any are exhaustive).

- [ ] **Step 4: Commit**

```bash
git add -f apps/shared/src/commonMain/kotlin/dev/supermux/fs/FsModels.kt apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt apps/shared/src/jvmTest/resources/frames/fs_dir.json apps/shared/src/jvmTest/resources/frames/fs_gone.json apps/shared/src/jvmTest/resources/frames/fs_err.json apps/shared/src/jvmTest/kotlin/dev/supermux/proto/ContractTest.kt
git commit -m "feat(shared): host fs wire models and fs_sub/fs_unsub/fs_dir/fs_gone/fs_err frames"
```

---

### Task 13: Kotlin FileSystemService

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/fs/FileSystemService.kt`
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt` (HTTP calls, near `fsSearch` ~line 2538)
- Test: `apps/shared/src/jvmTest/kotlin/dev/supermux/fs/FileSystemServiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// apps/shared/src/jvmTest/kotlin/dev/supermux/fs/FileSystemServiceTest.kt
package dev.supermux.fs

import dev.supermux.net.BrokerApi
import dev.supermux.net.FsEntry
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileSystemServiceTest {
    private fun TestScope.service(body: String = "{}"): Pair<FileSystemService, MutableList<ClientFrame>> {
        val sent = mutableListOf<ClientFrame>()
        val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        val fs = FileSystemService(BrokerApi("http://h", "t", http), send = { sent += it }, scope = backgroundScope, graceMs = 10_000)
        return fs to sent
    }

    private fun dir(path: String, v: String, vararg names: String) =
        ServerFrame.FsDir(path = path, version = v, entries = names.map { FsEntry(name = it, type = "file") })

    @Test fun firstSubscriberSendsOneFsSubAndSharesTheState() = runTest(StandardTestDispatcher()) {
        val (fs, sent) = service()
        val a = fs.subscribe("/p")
        val b = fs.subscribe("/p")
        runCurrent()
        assertEquals(listOf<ClientFrame>(ClientFrame.FsSub("/p")), sent)
        assertEquals(DirState.Loading(null), fs.dir("/p").value)
        fs.onFrame(dir("/p", "b:1", "x"))
        val ready = assertIs<DirState.Ready>(fs.dir("/p").value)
        assertEquals(listOf("x"), ready.snap.entries.map { it.name })
        a.close(); b.close()
    }

    @Test fun lastCloseUnsubscribesOnlyAfterTheGracePeriod() = runTest(StandardTestDispatcher()) {
        val (fs, sent) = service()
        val s = fs.subscribe("/p"); runCurrent()
        fs.onFrame(dir("/p", "b:1"))
        s.close()
        advanceTimeBy(9_000); runCurrent()
        assertEquals(1, sent.size)
        val again = fs.subscribe("/p"); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, sent.size) // re-subscribed within grace: no frames at all
        again.close()
        advanceTimeBy(10_001); runCurrent()
        assertEquals(ClientFrame.FsUnsub("/p"), sent.last())
        // the snapshot stays cached; a later subscribe sends since
        fs.subscribe("/p"); runCurrent()
        assertEquals(ClientFrame.FsSub("/p", since = "b:1"), sent.last())
    }

    @Test fun reconnectResubscribesWithSinceAndKeepsTheCachedRows() = runTest(StandardTestDispatcher()) {
        val (fs, sent) = service()
        fs.subscribe("/a"); fs.subscribe("/b"); runCurrent()
        fs.onFrame(dir("/a", "b:3", "one"))
        sent.clear()
        fs.onReconnect(); runCurrent()
        assertEquals(setOf<ClientFrame>(ClientFrame.FsSub("/a", "b:3"), ClientFrame.FsSub("/b")), sent.toSet())
        assertIs<DirState.Ready>(fs.dir("/a").value)
        fs.onFrame(ServerFrame.FsDir(path = "/a", version = "b:3", unchanged = true))
        assertEquals(listOf("one"), (fs.dir("/a").value as DirState.Ready).snap.entries.map { it.name })
    }

    @Test fun goneAndErrorStates() = runTest(StandardTestDispatcher()) {
        val (fs, _) = service()
        fs.subscribe("/a"); fs.subscribe("/b"); runCurrent()
        fs.onFrame(dir("/a", "b:1", "x"))
        fs.onFrame(ServerFrame.FsGone("/a"))
        assertEquals(DirState.Gone, fs.dir("/a").value)
        fs.onFrame(ServerFrame.FsErr("/b", "EACCES", "denied"))
        assertEquals(DirState.Failed("EACCES", "denied", null), fs.dir("/b").value)
    }

    @Test fun eviction_keeps_subscribed_folders() = runTest(StandardTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val http = HttpClient(MockEngine { respond("{}") })
        val fs = FileSystemService(BrokerApi("http://h", "t", http), send = { sent += it }, scope = backgroundScope, graceMs = 0, maxCached = 2)
        val keep = fs.subscribe("/keep"); runCurrent()
        fs.onFrame(dir("/keep", "b:1"))
        for (p in listOf("/x", "/y", "/z")) { val s = fs.subscribe(p); runCurrent(); fs.onFrame(dir(p, "b:1")); s.close(); advanceTimeBy(1); runCurrent() }
        assertTrue(fs.cachedCount <= 2) // before dir("/x") below, which re-creates an empty slot
        assertIs<DirState.Ready>(fs.dir("/keep").value)
        assertEquals(DirState.Unloaded, fs.dir("/x").value)
        keep.close()
    }
}
```

Run (from `apps/`): `./gradlew :shared:jvmTest --tests 'dev.supermux.fs.FileSystemServiceTest' --max-workers=2`
Expected: compile FAIL (no `FileSystemService`).

- [ ] **Step 2: Add the HTTP calls to BrokerApi**

Next to `fsSearch` in `BrokerApi.kt`:
```kotlin
    // ── Host file system (absolute paths; spec 2026-09-27) ─────────────────────

    suspend fun hostFsList(path: String): dev.supermux.fs.DirSnapshot =
        getJson("$httpBase/fs/list?path=${urlEncode(path)}")

    suspend fun hostFsStat(path: String): dev.supermux.fs.FsStat =
        getJson("$httpBase/fs/stat?path=${urlEncode(path)}")

    /** Throws FsException on non-2xx (413 too large, 415 binary, 404, 403). */
    suspend fun hostFsRead(path: String): String {
        val resp = http.get("$httpBase/fs/read?path=${urlEncode(path)}") { authHeader() }
        if (!resp.status.isSuccess()) throw FsException(resp.status.value, resp.bodyAsText().ifBlank { "read failed (${resp.status.value})" })
        return resp.bodyAsText()
    }

    suspend fun hostFsWrite(path: String, content: String): dev.supermux.fs.FsWriteResult {
        val resp = http.put("$httpBase/fs/write?path=${urlEncode(path)}") {
            authHeader()
            setBody(io.ktor.http.content.TextContent(content, io.ktor.http.ContentType.Text.Plain))
        }
        if (!resp.status.isSuccess()) throw FsException(resp.status.value, resp.bodyAsText())
        return json.decodeFromString(resp.bodyAsText())
    }

    suspend fun hostFsSearch(scope: String, q: String, limit: Int = 50): List<dev.supermux.fs.SearchHit> =
        getJson("$httpBase/fs/search?scope=${urlEncode(scope)}&q=${urlEncode(q)}&limit=$limit")

    suspend fun hostFsOp(op: dev.supermux.fs.FsOpRequest) {
        val resp = http.post("$httpBase/fs/ops") {
            authHeader()
            setBody(io.ktor.http.content.TextContent(json.encodeToString(dev.supermux.fs.FsOpRequest.serializer(), op), io.ktor.http.ContentType.Application.Json))
        }
        if (!resp.status.isSuccess()) throw FsException(resp.status.value, resp.bodyAsText())
    }
```
Match the surrounding code: use the same `json` instance, `authHeader()`, `urlEncode`, `FsException` and body-setting idiom that `fsWrite` already uses (copy its exact style if it differs from the above).

- [ ] **Step 3: Implement the service**

```kotlin
// apps/shared/src/commonMain/kotlin/dev/supermux/fs/FileSystemService.kt
// The app side of the host file-system service: one per HostStore. Folder listings are shared by
// every consumer on this host; subscriptions are ref-counted with a grace period, re-sent on
// reconnect with `since`, and cached snapshots stay on screen while a refresh is in flight.
package dev.supermux.fs

import dev.supermux.net.BrokerApi
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DirState {
    data object Unloaded : DirState
    data class Loading(val previous: DirSnapshot?) : DirState
    data class Ready(val snap: DirSnapshot) : DirState
    data class Failed(val code: String, val message: String, val previous: DirSnapshot?) : DirState
    data object Gone : DirState
}

/** The snapshot to draw for a state: the current one, or the previous one while loading/failed. */
val DirState.snapshotOrPrevious: DirSnapshot?
    get() = when (this) {
        is DirState.Ready -> snap
        is DirState.Loading -> previous
        is DirState.Failed -> previous
        else -> null
    }

fun interface DirSubscription { fun close() }

class FileSystemService(
    private val api: BrokerApi,
    private val send: suspend (ClientFrame) -> Unit,
    private val scope: CoroutineScope,
    private val graceMs: Long = 10_000,
    private val maxCached: Int = 1_000,
) {
    private class Slot(val state: MutableStateFlow<DirState>) {
        var refs = 0
        var subscribed = false      // an fs_sub is live on the broker
        var grace: Job? = null
        var used = 0L
    }

    private val lock = SynchronizedObject()
    private val slots = LinkedHashMap<String, Slot>()
    private var tick = 0L

    val cachedCount: Int get() = synchronized(lock) { slots.size }

    private fun slot(path: String): Slot = slots.getOrPut(path) { Slot(MutableStateFlow(DirState.Unloaded)) }.also { it.used = ++tick }

    fun dir(path: String): StateFlow<DirState> = synchronized(lock) { slot(path).state.asStateFlow() }

    fun subscribe(path: String): DirSubscription {
        val frame: ClientFrame? = synchronized(lock) {
            val s = slot(path)
            s.refs++
            s.grace?.cancel(); s.grace = null
            if (s.subscribed) return@synchronized null
            s.subscribed = true
            val cached = s.state.value.snapshotOrPrevious
            if (cached == null) s.state.value = DirState.Loading(null)
            ClientFrame.FsSub(path, since = cached?.version)
        }
        if (frame != null) scope.launch { send(frame) }
        var closed = false
        return DirSubscription {
            if (closed) return@DirSubscription
            closed = true
            release(path)
        }
    }

    private fun release(path: String) {
        synchronized(lock) {
            val s = slots[path] ?: return
            s.refs--
            if (s.refs > 0 || !s.subscribed) return
            s.grace = scope.launch {
                delay(graceMs)
                val unsub = synchronized(lock) {
                    if (s.refs > 0 || !s.subscribed) false else { s.subscribed = false; s.grace = null; evictLocked(); true }
                }
                if (unsub) send(ClientFrame.FsUnsub(path))
            }
        }
    }

    /** Called by HostStore's reducer. */
    fun onFrame(frame: ServerFrame) {
        synchronized(lock) {
            when (frame) {
                is ServerFrame.FsDir -> {
                    val s = slots[frame.path] ?: return
                    if (frame.unchanged) {
                        val prev = s.state.value.snapshotOrPrevious ?: return
                        s.state.value = DirState.Ready(prev)
                        return
                    }
                    val cur = (s.state.value as? DirState.Ready)?.snap
                    if (cur != null && cur.version == frame.version) return
                    s.state.value = DirState.Ready(
                        DirSnapshot(path = frame.path, version = frame.version, entries = frame.entries, truncated = frame.truncated),
                    )
                }
                is ServerFrame.FsGone -> {
                    val s = slots[frame.path] ?: return
                    s.subscribed = false
                    s.state.value = DirState.Gone
                }
                is ServerFrame.FsErr -> {
                    val s = slots[frame.path] ?: return
                    s.subscribed = false
                    s.state.value = DirState.Failed(frame.code, frame.message, s.state.value.snapshotOrPrevious)
                }
                else -> {}
            }
        }
    }

    /** The socket (re)connected: re-assert every live subscription, like `viewing` frames. */
    fun onReconnect() {
        val frames = synchronized(lock) {
            slots.filter { (_, s) -> s.refs > 0 }.map { (path, s) ->
                s.subscribed = true
                ClientFrame.FsSub(path, since = s.state.value.snapshotOrPrevious?.version)
            }
        }
        if (frames.isNotEmpty()) scope.launch { frames.forEach { send(it) } }
    }

    private fun evictLocked() {
        if (slots.size <= maxCached) return
        val victims = slots.entries
            .filter { it.value.refs == 0 && !it.value.subscribed }
            .sortedBy { it.value.used }
            .take(slots.size - maxCached)
        for (v in victims) slots.remove(v.key)
    }

    // ── request / response ────────────────────────────────────────────────────

    private suspend fun <T> call(block: suspend () -> T): Result<T> =
        try { Result.success(block()) } catch (c: CancellationException) { throw c } catch (e: Throwable) { Result.failure(e) }

    suspend fun list(path: String): Result<DirSnapshot> = call { api.hostFsList(path) }.onSuccess { snap ->
        synchronized(lock) {
            val s = slot(path)
            if (s.state.value !is DirState.Ready || (s.state.value as DirState.Ready).snap.version != snap.version) {
                s.state.value = DirState.Ready(snap)
            }
            evictLocked()
        }
    }
    suspend fun stat(path: String): Result<FsStat> = call { api.hostFsStat(path) }
    suspend fun read(path: String): Result<String> = call { api.hostFsRead(path) }
    suspend fun write(path: String, text: String): Result<FsWriteResult> = call { api.hostFsWrite(path, text) }
    suspend fun search(scope: String, q: String, limit: Int = 50): Result<List<SearchHit>> = call { api.hostFsSearch(scope, q, limit) }
    suspend fun op(op: FsOpRequest): Result<Unit> = call { api.hostFsOp(op) }
}
```

- [ ] **Step 4: Run tests**

Run (from `apps/`): `./gradlew :shared:jvmTest --tests 'dev.supermux.fs.FileSystemServiceTest' --max-workers=2`
Expected: 5 pass. If `eviction_keeps_subscribed_folders` fails because `/x` was evicted before its grace ran (`graceMs = 0` → launched job), keep the assertion and fix the code, not the test.

- [ ] **Step 5: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/fs/FileSystemService.kt apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt apps/shared/src/jvmTest/kotlin/dev/supermux/fs/FileSystemServiceTest.kt
git commit -m "feat(shared): per-host FileSystemService with ref-counted folder subscriptions"
```

---

### Task 14: Wire the service into HostStore

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/state/HostStore.kt` (field near `api`, ~line 187; `reduce`, ~line 422–458)
- Test: `apps/shared/src/jvmTest/kotlin/dev/supermux/state/HostStoreFileSystemTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// apps/shared/src/jvmTest/kotlin/dev/supermux/state/HostStoreFileSystemTest.kt
package dev.supermux.state

import dev.supermux.fs.DirState
import dev.supermux.net.BrokerApi
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HostStoreFileSystemTest {
    @Test fun fsFramesReachTheServiceAndASnapshotResubscribes() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val http = HttpClient(MockEngine { respond("{}") })
        val store = HostStore(
            "http://h", "t", backgroundScope, testDeps(http = http),
            connectOnInit = false,
            sendFrameOverride = { sent += it },
            apiOverride = BrokerApi("http://h", "t", http),
        )
        store.fileSystem.subscribe("/p")
        assertEquals(ClientFrame.FsSub("/p"), sent.last())
        store.reduce(ServerFrame.FsDir(path = "/p", version = "b:1"))
        assertIs<DirState.Ready>(store.fileSystem.dir("/p").value)
        sent.clear()
        store.reduce(ServerFrame.Snapshot())  // every (re)connect starts with a snapshot
        assertTrue(sent.contains(ClientFrame.FsSub("/p", since = "b:1")))
    }
}
```
Check `ServerFrame.Snapshot`'s constructor: if it has required parameters, build it the way `HostReducerLogTailTest`/other tests do (reuse their helper) instead of `Snapshot()`.

Run (from `apps/`): `./gradlew :shared:jvmTest --tests 'dev.supermux.state.HostStoreFileSystemTest' --max-workers=2`
Expected: compile FAIL (no `fileSystem`).

- [ ] **Step 2: Implement**

In `HostStore.kt`, after `private val sendFrame: suspend (ClientFrame) -> Unit = ...` (~line 209):
```kotlin
    /** The host's file-system service (spec 2026-09-27): shared folder listings for every pane. */
    val fileSystem = dev.supermux.fs.FileSystemService(api, send = { sendFrame(it) }, scope = stateScope)
```
In `reduce(frame)`'s `when`, add:
```kotlin
            is ServerFrame.FsDir, is ServerFrame.FsGone, is ServerFrame.FsErr -> fileSystem.onFrame(frame)
```
and in the branch that handles `is ServerFrame.Snapshot` (find it in `reduce`), add a call after the existing handling:
```kotlin
                fileSystem.onReconnect()
```
If `Snapshot` is handled outside this `when` (e.g. in a separate function), put the call there. `onReconnect` sends nothing when nothing is subscribed, so the first snapshot is harmless.

- [ ] **Step 3: Run tests**

Run (from `apps/`): `./gradlew :shared:jvmTest --max-workers=2`
Expected: all pass (report the count; note any pre-existing failures separately).

- [ ] **Step 4: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/state/HostStore.kt apps/shared/src/jvmTest/kotlin/dev/supermux/state/HostStoreFileSystemTest.kt
git commit -m "feat(shared): HostStore owns the host FileSystemService and re-subscribes on reconnect"
```

---

### Task 15: Compose helper + full verification

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/fs/CollectDir.kt`

- [ ] **Step 1: Implement**

```kotlin
// apps/ui/src/commonMain/kotlin/dev/supermux/ui/fs/CollectDir.kt
package dev.supermux.ui.fs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import dev.supermux.fs.DirState
import dev.supermux.fs.FileSystemService

/** Subscribe to [path] for as long as this composition lives, and read its state. */
@Composable
fun FileSystemService.collectDir(path: String): State<DirState> {
    DisposableEffect(this, path) {
        val sub = subscribe(path)
        onDispose { sub.close() }
    }
    val flow = remember(this, path) { dir(path) }
    return flow.collectAsState()
}
```

- [ ] **Step 2: Compile the UI module**

Run (from `apps/`): `./gradlew :ui:compileKotlinJvm --max-workers=2`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Full verification**

Run: `bun test src/core/fs src/channels/web`
Expected: all fs tests pass; no new failures in `src/channels/web` (list pre-existing ones).
Run: `bunx tsc --noEmit -p .`
Expected: no new type errors.
Run (from `apps/`): `./gradlew :shared:jvmTest --max-workers=2` then `./gradlew --stop`
Expected: pass.

- [ ] **Step 4: Manual smoke test on a preview broker**

Use the `mux:preview-broker` skill to run the broker from this worktree. Then, with a device token from `~/.mux/state` (see the skill), check:
```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:9898/fs/list?path=$HOME/projects/supermux" | head -c 400
curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:9898/fs/search?scope=$HOME/projects/supermux&q=filetree" | head -c 400
```
Open the current app's Files pane on a big folder (e.g. with `node_modules`) and confirm expanding does not stall chats. Let the preview auto-revert.

- [ ] **Step 5: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/dev/supermux/ui/fs/CollectDir.kt
git commit -m "feat(ui): collectDir, a composition-scoped folder subscription"
```

---

## Self-review notes

- Spec §4.1–4.3 → Tasks 1–10; §4.4 protocol → Tasks 10–12; §4.5 app → Tasks 12–15; §4.6 errors → Tasks 10, 11, 13; §6 limits → constants in `dir-cache.ts` (5,000 / 2,000 / 32), `dir-watchers.ts` (100 ms / 1 s / 5 s), `subscriptions.ts` (10 s / 500), `search-index.ts` (200k / 30 s / 10 min), `FileSystemService.kt` (10 s / 1,000). Phone cache size (300) is set by the caller when B wires per-platform values.
- Deviation from the spec, deliberate: git runs through the existing `gitAsync` helper (async `execFile` with timeout) instead of raw `Bun.spawn`; `RepoInfoCache` also has a 2 s TTL so unwatched listings (A1, before watchers exist) do not spawn git on every call.
- `fs_changed` / `FsWatcher` stay until sub-project B (spec §4.3); Task 11 only fixes the leak.
