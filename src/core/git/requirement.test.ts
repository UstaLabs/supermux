import { afterEach, beforeEach, expect, test } from "bun:test"
import { existsSync, mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { APPLE_GIT_STUB, noCltDir } from "./clt-guard"
import {
  GIT_HINT_DARWIN, GIT_HINT_LINUX, GIT_HINT_WINDOWS_MANUAL, GIT_REQUIRED_MESSAGE, GitRequiredError,
  GitRequirementMonitor, WINGET_INSTALL_GIT, XCODE_SELECT_INSTALL, checkGit, gitInstallFor, gitRequiredBody,
  installGit, spawnDetached, type GitRequirementDeps,
} from "./requirement"

let stateDir: string
beforeEach(() => { stateDir = mkdtempSync(join(tmpdir(), "git-req-")) })
afterEach(() => { rmSync(stateDir, { recursive: true, force: true }) })

const BASE_PATH = "/usr/local/bin:/usr/bin:/bin"

/** A fake PATH lookup: [bins] maps a binary to the path it resolves to (absent = not found). */
function fakeDeps(platform: NodeJS.Platform, bins: Record<string, string>, over: Partial<GitRequirementDeps> = {}) {
  const timers: Array<{ fn: () => void; ms: number; cleared: boolean; unrefd: boolean }> = []
  const seenPaths: string[] = []
  const xcode = { exit: 2 }
  const d = {
    platform,
    which: (bin: string, path: string): string | null => { seenPaths.push(path); return bins[bin] ?? null },
    runXcodeSelect: (): number => xcode.exit,
    get xcodeExit() { return xcode.exit },
    set xcodeExit(v: number) { xcode.exit = v },
    stateDir,
    env: { PATH: BASE_PATH } as Record<string, string | undefined>,
    setInterval: (fn: () => void, ms: number) => {
      const t = { fn, ms, cleared: false, unrefd: false }
      timers.push(t)
      return { unref: () => { t.unrefd = true }, t }
    },
    clearInterval: (h: unknown) => { (h as { t: { cleared: boolean } }).t.cleared = true },
    timers,
    seenPaths,
    bins,
    ...over,
  }
  return d
}

// ── checkGit per OS ────────────────────────────────────────────────────────────────────────

test("darwin: a real git first on PATH is usable", () => {
  expect(checkGit(fakeDeps("darwin", { git: "/opt/homebrew/bin/git" }))).toBe(true)
})

test("darwin: Apple's stub with no developer tools is missing", () => {
  expect(checkGit(fakeDeps("darwin", { git: APPLE_GIT_STUB }))).toBe(false)
})

test("darwin: Apple's stub with the developer tools installed is usable", () => {
  const d = fakeDeps("darwin", { git: APPLE_GIT_STUB })
  d.xcodeExit = 0
  expect(checkGit(d)).toBe(true)
})

test("linux: git on PATH is usable; none is missing", () => {
  expect(checkGit(fakeDeps("linux", { git: "/usr/bin/git" }))).toBe(true)
  expect(checkGit(fakeDeps("linux", {}))).toBe(false)
})

test("win32: git on PATH is usable; none is missing", () => {
  expect(checkGit(fakeDeps("win32", { git: "C:\\Program Files\\Git\\cmd\\git.exe" }))).toBe(true)
  expect(checkGit(fakeDeps("win32", {}))).toBe(false)
})

test("our own shim dir is never looked at", () => {
  const d = fakeDeps("linux", {})
  d.env.PATH = `${noCltDir(stateDir)}:${BASE_PATH}`
  checkGit(d)
  expect(d.seenPaths.every((p) => !p.includes(noCltDir(stateDir)))).toBe(true)
})

// ── install action per OS ──────────────────────────────────────────────────────────────────

test("the install action: xcode-select on macOS, winget or manual on Windows, manual on Linux", () => {
  expect(gitInstallFor("darwin", () => null, "")).toEqual({ install: "xcode-select", hint: GIT_HINT_DARWIN })
  expect(gitInstallFor("win32", (b) => (b === "winget" ? "C:\\winget.exe" : null), "").install).toBe("winget")
  expect(gitInstallFor("win32", () => null, "")).toEqual({ install: "manual", hint: GIT_HINT_WINDOWS_MANUAL })
  expect(gitInstallFor("linux", () => null, "")).toEqual({ install: "manual", hint: GIT_HINT_LINUX })
})

// ── the monitor ────────────────────────────────────────────────────────────────────────────

test("start with git present: ok, no timer, PATH untouched", () => {
  const d = fakeDeps("linux", { git: "/usr/bin/git" })
  const m = new GitRequirementMonitor(d)
  expect(m.start().ok).toBe(true)
  expect(d.timers).toHaveLength(0)
  expect(d.env.PATH).toBe(BASE_PATH)
})

test("linux without git: missing, manual hint, an unref'd 10 s re-check, PATH kept as is", () => {
  const d = fakeDeps("linux", {})
  const m = new GitRequirementMonitor(d)
  expect(m.start()).toEqual({ ok: false, install: "manual", hint: GIT_HINT_LINUX })
  expect(d.timers).toHaveLength(1)
  expect(d.timers[0]!.ms).toBe(10_000)
  expect(d.timers[0]!.unrefd).toBe(true)
  expect(d.env.PATH).toBe(BASE_PATH)
})

test("darwin without the developer tools: the shim goes first on PATH", () => {
  const d = fakeDeps("darwin", { git: APPLE_GIT_STUB })
  const m = new GitRequirementMonitor(d)
  expect(m.start()).toEqual({ ok: false, install: "xcode-select", hint: GIT_HINT_DARWIN })
  expect(d.env.PATH).toBe(`${noCltDir(stateDir)}:${BASE_PATH}`)
  expect(existsSync(join(noCltDir(stateDir), "git"))).toBe(true)
})

test("re-check finds git: the shim leaves PATH, the state flips, listeners hear it, the timer stops", () => {
  const d = fakeDeps("darwin", { git: APPLE_GIT_STUB })
  const m = new GitRequirementMonitor(d)
  m.start()
  const heard: unknown[] = []
  m.onChange((r) => heard.push(r))

  d.timers[0]!.fn() // still missing
  expect(m.ok).toBe(false)
  expect(heard).toHaveLength(0)

  d.xcodeExit = 0 // the user finished Apple's installer
  d.timers[0]!.fn()
  expect(m.ok).toBe(true)
  expect(d.env.PATH).toBe(BASE_PATH)
  expect(heard).toEqual([{ git: { ok: true, install: "xcode-select", hint: GIT_HINT_DARWIN } }])
  expect(d.timers[0]!.cleared).toBe(true)
})

test("linux re-check finds a freshly installed git", () => {
  const d = fakeDeps("linux", {})
  const m = new GitRequirementMonitor(d)
  m.start()
  let calls = 0
  m.onChange(() => calls++)
  d.bins.git = "/usr/bin/git"
  expect(m.recheck()).toBe(true)
  expect(m.requirements().git.ok).toBe(true)
  expect(calls).toBe(1)
  expect(m.recheck()).toBe(false) // already ok: nothing more
  expect(calls).toBe(1)
})

test("windows: winget appearing while missing updates the action and notifies", () => {
  const d = fakeDeps("win32", {})
  const m = new GitRequirementMonitor(d)
  expect(m.start().install).toBe("manual")
  const heard: string[] = []
  m.onChange((r) => heard.push(r.git.install))
  d.bins.winget = "C:\\winget.exe"
  m.recheck()
  expect(m.git).toMatchObject({ ok: false, install: "winget" })
  expect(heard).toEqual(["winget"])
})

test("a throwing listener does not stop the others", () => {
  const d = fakeDeps("linux", {})
  const m = new GitRequirementMonitor(d)
  m.start()
  let reached = false
  m.onChange(() => { throw new Error("boom") })
  m.onChange(() => { reached = true })
  d.bins.git = "/usr/bin/git"
  m.recheck()
  expect(reached).toBe(true)
})

// ── refusal payload ────────────────────────────────────────────────────────────────────────

test("the refusal carries the message and the requirement object", () => {
  const req = { git: { ok: false, install: "manual" as const, hint: GIT_HINT_LINUX } }
  const e = new GitRequiredError(req)
  expect(e.message).toBe(GIT_REQUIRED_MESSAGE)
  expect(e.requirements).toBe(req)
  expect(gitRequiredBody(req)).toEqual({ error: GIT_REQUIRED_MESSAGE, code: "git_required", requirements: req })
})

// ── install-git per OS (injected spawner: no installer ever runs) ──────────────────────────

const missing = (install: "xcode-select" | "winget" | "manual") => ({ ok: false, install, hint: "h" })

test("install-git on macOS runs xcode-select --install", () => {
  const spawned: string[][] = []
  const r = installGit({ platform: "darwin", requirement: missing("xcode-select"), hasWinget: () => false, spawn: (c) => spawned.push(c) })
  expect(r).toEqual({ status: 200, body: { ok: true } })
  expect(spawned).toEqual([XCODE_SELECT_INSTALL])
})

test("install-git on Windows with winget runs the user-scope winget install", () => {
  const spawned: string[][] = []
  const r = installGit({ platform: "win32", requirement: missing("winget"), hasWinget: () => true, spawn: (c) => spawned.push(c) })
  expect(r.status).toBe(200)
  expect(spawned).toEqual([WINGET_INSTALL_GIT])
  expect(WINGET_INSTALL_GIT.join(" ")).toBe(
    "winget install --id Git.Git -e --scope user --accept-source-agreements --accept-package-agreements",
  )
})

test("install-git on Windows without winget, and on Linux, is manual (400 + hint), nothing spawned", () => {
  const spawned: string[][] = []
  const spawn = (c: string[]) => { spawned.push(c) }
  expect(installGit({ platform: "win32", requirement: missing("manual"), hasWinget: () => false, spawn }))
    .toEqual({ status: 400, body: { error: "manual", hint: GIT_HINT_WINDOWS_MANUAL } })
  expect(installGit({ platform: "linux", requirement: missing("manual"), hasWinget: () => false, spawn }))
    .toEqual({ status: 400, body: { error: "manual", hint: GIT_HINT_LINUX } })
  expect(spawned).toHaveLength(0)
})

test("install-git with git already present does nothing", () => {
  const spawned: string[][] = []
  const r = installGit({ platform: "darwin", requirement: { ok: true, install: "xcode-select", hint: "" }, hasWinget: () => false, spawn: (c) => spawned.push(c) })
  expect(r).toEqual({ status: 200, body: { ok: true, alreadyInstalled: true } })
  expect(spawned).toHaveLength(0)
})

test("the real spawner survives a missing binary (async ENOENT is handled, not thrown)", async () => {
  const logged: string[] = []
  spawnDetached(["supermux-definitely-not-a-binary-xyz"], (e) => logged.push(e))
  await new Promise((r) => setTimeout(r, 100))
  expect(logged).toContain("install_git_spawn_failed")
})
