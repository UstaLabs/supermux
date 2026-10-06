import { afterEach, beforeEach, expect, test } from "bun:test"
import { existsSync, mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { delimiter, join, win32 as winPath } from "path"
import { APPLE_GIT_STUB, noCltDir } from "./clt-guard"
import {
  GIT_HINT_DARWIN, GIT_HINT_LINUX, GIT_HINT_WINDOWS_BROWSER, GIT_REQUIRED_MESSAGE, GitInstaller, GitRequiredError,
  GitRequirementMonitor, type GitInstallStatus, INSTALL_COOLDOWN_MS, wingetFailure, OPEN_GIT_DOWNLOAD_PAGE, WINGET_INSTALL_GIT, WINGET_MAX_MS,
  XCODE_SELECT_INSTALL, checkGit, checkGitSync, expandWindowsVars, gitInstallFor, gitRequiredBody,
  spawnDetached, type GitRequirement, type GitRequirementDeps, type RegistryScope,
} from "./requirement"

let stateDir: string
beforeEach(() => { stateDir = mkdtempSync(join(tmpdir(), "git-req-")) })
afterEach(() => { rmSync(stateDir, { recursive: true, force: true }) })

const BASE_PATH = ["/usr/local/bin", "/usr/bin", "/bin"].join(delimiter)

/**
 * A fake PATH lookup: [bins] maps a binary to where it resolves (absent = not found), and
 * [dirBins] maps a single directory to the git found there (the Windows off-PATH probes).
 */
function fakeDeps(platform: NodeJS.Platform, bins: Record<string, string>, over: Partial<GitRequirementDeps> = {}) {
  const timers: Array<{ fn: () => void; ms: number; cleared: boolean; unrefd: boolean }> = []
  const seenPaths: string[] = []
  const dirBins: Record<string, string> = {}
  const registry: Partial<Record<RegistryScope, string>> = {}
  const xcode = { exit: 2, asyncCalls: 0, syncCalls: 0 }
  const pathChanges: string[] = []
  const d = {
    platform,
    which: (bin: string, path: string): string | null => {
      seenPaths.push(path)
      if (bin === "git" && dirBins[path]) return dirBins[path]!
      return bins[bin] ?? null
    },
    runXcodeSelectSync: (): number => { xcode.syncCalls++; return xcode.exit },
    runXcodeSelect: async (): Promise<number> => { xcode.asyncCalls++; return xcode.exit },
    readRegistryPath: async (scope: RegistryScope) => registry[scope] ?? null,
    stateDir,
    env: { PATH: BASE_PATH } as Record<string, string | undefined>,
    setInterval: (fn: () => void, ms: number) => {
      const t = { fn, ms, cleared: false, unrefd: false }
      timers.push(t)
      return { unref: () => { t.unrefd = true }, t }
    },
    clearInterval: (h: unknown) => { (h as { t: { cleared: boolean } }).t.cleared = true },
    onPathChanged: (p: string) => { pathChanges.push(p) },
    ...over,
  }
  return { d, timers, seenPaths, bins, dirBins, registry, xcode, pathChanges }
}

// ── checkGit per OS ────────────────────────────────────────────────────────────────────────

test("darwin: a real git first on PATH is usable", async () => {
  const f = fakeDeps("darwin", { git: "/opt/homebrew/bin/git" })
  expect(checkGitSync(f.d)).toBe(true)
  expect((await checkGit(f.d)).found).toBe(true)
})

test("darwin: Apple's stub with no developer tools is missing; with them it is usable", async () => {
  const f = fakeDeps("darwin", { git: APPLE_GIT_STUB })
  expect(checkGitSync(f.d)).toBe(false)
  expect((await checkGit(f.d)).found).toBe(false)
  f.xcode.exit = 0
  expect((await checkGit(f.d)).found).toBe(true)
  expect(f.xcode.asyncCalls).toBe(2) // the re-check uses the async xcode-select
})

test("darwin: no git on PATH at all is missing", async () => {
  const f = fakeDeps("darwin", {})
  expect(checkGitSync(f.d)).toBe(false)
  expect((await checkGit(f.d)).found).toBe(false)
})

test("linux: git on PATH is usable; none is missing", async () => {
  expect((await checkGit(fakeDeps("linux", { git: "/usr/bin/git" }).d)).found).toBe(true)
  expect((await checkGit(fakeDeps("linux", {}).d)).found).toBe(false)
})

test("win32: git on PATH is usable", async () => {
  expect((await checkGit(fakeDeps("win32", { git: "C:\\Program Files\\Git\\cmd\\git.exe" }).d))).toEqual({ found: true })
})

test("win32: git only in the fresh user registry Path is found, with its dir to add", async () => {
  const f = fakeDeps("win32", {})
  f.d.env.USERPROFILE = "C:\\Users\\a"
  f.registry.user = "%USERPROFILE%\\bin;%USERPROFILE%\\AppData\\Local\\Programs\\Git\\cmd"
  const dir = "C:\\Users\\a\\AppData\\Local\\Programs\\Git\\cmd"
  f.dirBins[dir] = `${dir}\\git.exe`
  expect(await checkGit(f.d)).toEqual({ found: true, addDir: dir })
})

test("win32: git only in the machine registry Path is found", async () => {
  const f = fakeDeps("win32", {})
  f.registry.machine = "C:\\Windows\\system32;D:\\Tools\\Git\\cmd"
  f.dirBins["D:\\Tools\\Git\\cmd"] = "D:\\Tools\\Git\\cmd\\git.exe"
  expect(await checkGit(f.d)).toEqual({ found: true, addDir: "D:\\Tools\\Git\\cmd" })
})

test("win32: Git for Windows' default dirs are probed even when the registry has nothing", async () => {
  const f = fakeDeps("win32", {})
  f.d.env.LOCALAPPDATA = "C:\\Users\\a\\AppData\\Local"
  f.d.env.ProgramFiles = "C:\\Program Files"
  const pf = winPath.join("C:\\Program Files", "Git", "cmd")
  f.dirBins[pf] = `${pf}\\git.exe`
  expect(await checkGit(f.d)).toEqual({ found: true, addDir: pf })
  // The per-user dir was asked first.
  expect(f.seenPaths).toContain(winPath.join("C:\\Users\\a\\AppData\\Local", "Programs", "Git", "cmd"))
})

test("win32: nowhere means missing; a failing registry read is just 'nothing there'", async () => {
  const f = fakeDeps("win32", {}, { readRegistryPath: async () => { throw new Error("reg failed") } })
  expect(await checkGit(f.d)).toEqual({ found: false })
})

test("our own shim dir is never looked at", () => {
  const f = fakeDeps("linux", {})
  f.d.env.PATH = `${noCltDir(stateDir)}${delimiter}${BASE_PATH}`
  checkGitSync(f.d)
  expect(f.seenPaths.every((p) => !p.includes(noCltDir(stateDir)))).toBe(true)
})

// ── install action per OS ──────────────────────────────────────────────────────────────────

test("the install action: xcode-select on macOS, winget or the browser on Windows, manual on Linux", () => {
  expect(gitInstallFor("darwin", () => null, "")).toEqual({ install: "xcode-select", hint: GIT_HINT_DARWIN })
  expect(gitInstallFor("win32", (b) => (b === "winget" ? "C:\\winget.exe" : null), "").install).toBe("winget")
  expect(gitInstallFor("win32", () => null, "")).toEqual({ install: "browser", hint: GIT_HINT_WINDOWS_BROWSER })
  expect(gitInstallFor("linux", () => null, "")).toEqual({ install: "manual", hint: GIT_HINT_LINUX })
})

// ── the monitor ────────────────────────────────────────────────────────────────────────────

test("start with git present: ok, no timer, PATH untouched", () => {
  const f = fakeDeps("linux", { git: "/usr/bin/git" })
  expect(new GitRequirementMonitor(f.d).start().ok).toBe(true)
  expect(f.timers).toHaveLength(0)
  expect(f.d.env.PATH).toBe(BASE_PATH)
})

test("linux without git: missing, manual hint, an unref'd 10 s re-check, PATH kept as is", () => {
  const f = fakeDeps("linux", {})
  expect(new GitRequirementMonitor(f.d).start()).toEqual({ ok: false, install: "manual", hint: GIT_HINT_LINUX })
  expect(f.timers).toHaveLength(1)
  expect(f.timers[0]!.ms).toBe(10_000)
  expect(f.timers[0]!.unrefd).toBe(true)
  expect(f.d.env.PATH).toBe(BASE_PATH)
})

test("darwin without the developer tools: the shim goes first on PATH (boot check is synchronous)", () => {
  const f = fakeDeps("darwin", { git: APPLE_GIT_STUB })
  expect(new GitRequirementMonitor(f.d).start()).toEqual({ ok: false, install: "xcode-select", hint: GIT_HINT_DARWIN })
  expect(f.d.env.PATH).toBe(`${noCltDir(stateDir)}${delimiter}${BASE_PATH}`)
  expect(existsSync(join(noCltDir(stateDir), "git"))).toBe(true)
  expect(f.xcode.syncCalls).toBeGreaterThan(0)
  expect(f.xcode.asyncCalls).toBe(0)
})

test("darwin with no git on PATH at all: missing like checkGit, and no shim (no stub, no dialog)", () => {
  const f = fakeDeps("darwin", {})
  expect(new GitRequirementMonitor(f.d).start().ok).toBe(false)
  expect(f.d.env.PATH).toBe(BASE_PATH)
  expect(existsSync(join(noCltDir(stateDir), "git"))).toBe(false)
  expect(f.timers).toHaveLength(1)
})

test("re-check finds git: the shim leaves PATH, the state flips, listeners and tmux hear it, the timer stops", async () => {
  const f = fakeDeps("darwin", { git: APPLE_GIT_STUB })
  const m = new GitRequirementMonitor(f.d)
  m.start()
  const heard: unknown[] = []
  m.onChange((r) => heard.push(r))

  expect(await m.recheck()).toBe(false) // still missing
  expect(heard).toHaveLength(0)

  f.xcode.exit = 0 // the user finished Apple's installer
  expect(await m.recheck()).toBe(true)
  expect(m.ok).toBe(true)
  expect(f.d.env.PATH).toBe(BASE_PATH)
  expect(f.pathChanges).toEqual([BASE_PATH])
  expect(heard).toEqual([{ git: { ok: true, install: "xcode-select", hint: GIT_HINT_DARWIN } }])
  expect(f.timers[0]!.cleared).toBe(true)
})

test("the timer tick runs the async re-check", async () => {
  const f = fakeDeps("linux", {})
  const m = new GitRequirementMonitor(f.d)
  m.start()
  f.bins.git = "/usr/bin/git"
  f.timers[0]!.fn()
  await m.recheck() // joins (or follows) the tick's re-check
  expect(m.ok).toBe(true)
})

test("overlapping re-checks share one check (in-flight guard)", async () => {
  let release!: (n: number) => void
  let calls = 0
  const f = fakeDeps("darwin", { git: APPLE_GIT_STUB }, {
    runXcodeSelect: () => { calls++; return new Promise<number>((r) => { release = r }) },
  })
  const m = new GitRequirementMonitor(f.d)
  m.start()
  const a = m.recheck()
  const b = m.recheck()
  expect(a).toBe(b)
  release(0)
  expect(await a).toBe(true)
  expect(calls).toBe(1)
  // A later call is a fresh one (and a no-op now that git is there).
  expect(await m.recheck()).toBe(false)
})

test("windows: an install that only updated the registry unblocks without a restart (dir added to PATH)", async () => {
  const f = fakeDeps("win32", {})
  f.d.env.LOCALAPPDATA = "C:\\Users\\a\\AppData\\Local"
  const m = new GitRequirementMonitor(f.d)
  expect(m.start().ok).toBe(false)
  const dir = winPath.join("C:\\Users\\a\\AppData\\Local", "Programs", "Git", "cmd")
  f.registry.user = dir
  f.dirBins[dir] = `${dir}\\git.exe`
  expect(await m.recheck()).toBe(true)
  expect(f.d.env.PATH).toBe(`${dir}${delimiter}${BASE_PATH}`)
  expect(m.ok).toBe(true)
})

test("windows: winget appearing while missing updates the action and notifies", async () => {
  const f = fakeDeps("win32", {})
  const m = new GitRequirementMonitor(f.d)
  expect(m.start().install).toBe("browser")
  const heard: string[] = []
  m.onChange((r) => heard.push(r.git.install))
  f.bins.winget = "C:\\winget.exe"
  await m.recheck()
  expect(m.git).toMatchObject({ ok: false, install: "winget" })
  expect(heard).toEqual(["winget"])
})

test("a throwing listener does not stop the others", async () => {
  const f = fakeDeps("linux", {})
  const m = new GitRequirementMonitor(f.d)
  m.start()
  let reached = false
  m.onChange(() => { throw new Error("boom") })
  m.onChange(() => { reached = true })
  f.bins.git = "/usr/bin/git"
  await m.recheck()
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

function installer(platform: NodeJS.Platform, opts: { winget?: boolean; ok?: boolean } = {}) {
  const spawned: string[][] = []
  const exits: Array<(code: number | null) => void> = []
  const statuses: GitInstallStatus[] = []
  const clock = { now: 1_000_000 }
  const req: GitRequirement = { ok: opts.ok ?? false, install: "manual", hint: "h" }
  const inst = new GitInstaller({
    platform,
    requirement: () => req,
    hasWinget: () => opts.winget ?? false,
    spawn: (cmd) => { spawned.push(cmd); return { onExit: (cb) => { exits.push(cb) } } },
    now: () => clock.now,
    onStatus: (st) => statuses.push(st),
  })
  return { inst, spawned, exits, clock, statuses }
}

test("install-git on macOS runs xcode-select --install, then cools down for 60 s", () => {
  const t = installer("darwin")
  expect(t.inst.install()).toEqual({ status: 200, body: { ok: true } })
  expect(t.spawned).toEqual([XCODE_SELECT_INSTALL])
  t.clock.now += INSTALL_COOLDOWN_MS - 1
  expect(t.inst.install()).toEqual({ status: 200, body: { ok: true, inProgress: true } })
  expect(t.spawned).toHaveLength(1)
  t.clock.now += 1
  expect(t.inst.install().body).toEqual({ ok: true })
  expect(t.spawned).toHaveLength(2)
})

test("install-git on Windows with winget runs the user-scope install and refuses while it runs", () => {
  const t = installer("win32", { winget: true })
  expect(t.inst.install().body).toEqual({ ok: true })
  expect(t.spawned).toEqual([WINGET_INSTALL_GIT])
  expect(WINGET_INSTALL_GIT.join(" ")).toBe(
    "winget install --id Git.Git -e --source winget --scope user --accept-source-agreements --accept-package-agreements",
  )
  t.clock.now += 5 * 60_000
  expect(t.inst.install().body).toEqual({ ok: true, inProgress: true })
  t.exits[0]!(0) // winget finished
  expect(t.inst.install().body).toEqual({ ok: true })
  expect(t.spawned).toHaveLength(2)
})

test("a winget run that never exits stops blocking after 15 min", () => {
  const t = installer("win32", { winget: true })
  t.inst.install()
  t.clock.now += WINGET_MAX_MS
  expect(t.inst.install().body).toEqual({ ok: true })
  expect(t.spawned).toHaveLength(2)
})

test("install-git on Windows without winget opens the download page (fixed argv), with the cooldown", () => {
  const t = installer("win32")
  expect(t.inst.install()).toEqual({ status: 200, body: { ok: true } })
  expect(t.spawned).toEqual([OPEN_GIT_DOWNLOAD_PAGE])
  expect(OPEN_GIT_DOWNLOAD_PAGE).toEqual(["explorer.exe", "https://git-scm.com/download/win"])
  expect(t.inst.install().body).toEqual({ ok: true, inProgress: true })
})

test("install-git on Linux is manual (400 + hint), nothing spawned", () => {
  const t = installer("linux")
  expect(t.inst.install()).toEqual({ status: 400, body: { error: "manual", hint: GIT_HINT_LINUX } })
  expect(t.spawned).toHaveLength(0)
})

test("install-git with git already present does nothing", () => {
  const t = installer("darwin", { ok: true })
  expect(t.inst.install()).toEqual({ status: 200, body: { ok: true, alreadyInstalled: true } })
  expect(t.spawned).toHaveLength(0)
})

test("the real spawner survives a missing binary (async ENOENT is handled, not thrown) and reports the exit", async () => {
  const logged: string[] = []
  const run = spawnDetached(["supermux-definitely-not-a-binary-xyz"], (e) => logged.push(e))
  const code = await new Promise<number | null>((r) => run.onExit(r))
  expect(code).toBe(null)
  expect(logged).toContain("install_git_spawn_failed")
})

test("a winget run that ends without git reports the failure, so the banner leaves Installing…", () => {
  const t = installer("win32", { winget: true })
  t.inst.install()
  expect(t.statuses).toEqual([{ installing: true }])
  t.exits[0]!(12) // UAC declined / timed out (0x8A15010C, low byte)
  expect(t.statuses[1]).toEqual({ installing: false, installError: wingetFailure(12) })
  // Retry starts a fresh run, which clears the error.
  expect(t.inst.install().body).toEqual({ ok: true })
  expect(t.statuses[2]).toEqual({ installing: true })
  t.exits[1]!(0)
  expect(t.statuses[3]).toEqual({ installing: false })
})

test("the monitor reports the install status in requirements.git while git is missing", () => {
  const m = new GitRequirementMonitor({
    platform: "win32", which: () => null, runXcodeSelectSync: () => 1, runXcodeSelect: async () => 1,
    stateDir: "/s", env: { PATH: "" }, setInterval: () => 0, clearInterval: () => {},
  })
  m.start()
  const seen: unknown[] = []
  m.onChange((r) => seen.push(r.git))
  m.setInstallStatus({ installing: true })
  expect(m.git.installing).toBe(true)
  m.setInstallStatus({ installing: false, installError: "nope" })
  expect(m.git).toMatchObject({ ok: false, installing: false, installError: "nope" })
  expect(seen).toHaveLength(2)
})

test("%VAR% expansion", () => {
  expect(expandWindowsVars("%userprofile%\\bin;%NOPE%", { USERPROFILE: "C:\\Users\\a" })).toBe("C:\\Users\\a\\bin;%NOPE%")
})
