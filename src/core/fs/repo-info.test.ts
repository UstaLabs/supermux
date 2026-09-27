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

test("a cached 'no repo' answer expires, so a later git init is noticed; a found root is kept", async () => {
  const plain = realpathSync(mkdtempSync(join(tmpdir(), "fs-plain-")))
  const c = new RepoInfoCache({ noRepoTtlMs: 600 })
  expect(await c.repoFor(plain)).toBeNull()
  git(plain, "init", "-q")
  expect(await c.repoFor(plain)).toBeNull() // still cached
  expect(c.knownRepoFor(plain)).toBeNull()
  await new Promise((r) => setTimeout(r, 700))
  expect(c.knownRepoFor(plain)).toBeUndefined()
  expect(await c.repoFor(plain)).toBe(plain)
  await new Promise((r) => setTimeout(r, 700))
  expect(c.knownRepoFor(plain)).toBe(plain)
  c.forgetRootOf(plain)
  expect(c.knownRepoFor(plain)).toBeUndefined()
})

test("reading git state never touches the git dir (no index.lock churn a git-dir watcher would see)", async () => {
  const root = repoFixture()
  const { watch } = await import("fs")
  const events: string[] = []
  const w = watch(join(root, ".git"), (t, f) => events.push(`${t}:${f}`))
  const r = new RepoInfoCache()
  await r.state(root)
  r.invalidate(root)
  await r.state(root)
  await new Promise((res) => setTimeout(res, 150))
  w.close()
  expect(events).toEqual([])
})

test("a state invalidated while its git read is in flight is read again", async () => {
  const root = repoFixture()
  const r = new RepoInfoCache()
  const inner = r as unknown as { load: (root: string) => Promise<unknown> }
  const orig = inner.load.bind(r)
  let release: (() => void) | undefined
  let calls = 0
  inner.load = async (rt: string) => {
    const st = await orig(rt)
    if (++calls === 1) await new Promise<void>((res) => { release = res })
    return st
  }
  const first = r.state(root)
  while (!release) await new Promise((res) => setTimeout(res, 5))
  writeFileSync(join(root, "late.ts"), "l")
  r.invalidate(root)
  const second = r.state(root)
  release()
  await first
  expect((await second).status.get("late.ts")).toBe("?")
})

test("the folder → repo map is bounded: the oldest answers are dropped", async () => {
  const root = repoFixture()
  const r = new RepoInfoCache({ maxRoots: 2 })
  mkdirSync(join(root, "x"))
  await r.repoFor(join(root, "src"))
  await r.repoFor(join(root, "x"))
  await r.repoFor(root)
  expect(r.knownRepoFor(join(root, "src"))).toBeUndefined()
  expect(r.knownRepoFor(join(root, "x"))).toBe(root)
  expect(r.knownRepoFor(root)).toBe(root)
})

test("callers arriving while a refresh is in flight get the refreshed state, not the previous one", async () => {
  const root = repoFixture()
  const r = new RepoInfoCache()
  await r.state(root)
  writeFileSync(join(root, "later.ts"), "l")
  r.invalidate(root)
  const a = r.state(root)
  const b = r.state(root) // previous state is younger than the TTL, but a refresh is already running
  expect((await b).status.get("later.ts")).toBe("?")
  await a
})
