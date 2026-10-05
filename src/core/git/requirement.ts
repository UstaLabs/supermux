// Git is required to host agents (spec 2026-09-30-desktop-hosting-lifecycle-design, "Git is required
// for hosting agents"). Worktrees, the finish flow, diffs and the agents themselves all run git; on a
// computer without one an agent session is half-broken at best, and on a Mac without the Command
// Line Tools every git run pops Apple's install dialog.
//
// This module is the broker's single answer to "is there a usable git here?":
//   - darwin: the first `git` on PATH (past our own shim) is not Apple's stub, or `xcode-select -p`
//     succeeds. While missing, `clt-guard`'s failing shim stays first on PATH (no dialogs).
//   - linux: a `git` resolvable on PATH.
//   - win32: a `git` on PATH, or — because an installer only updates the REGISTRY Path, never this
//     running process's — in the fresh user/machine registry Path or Git for Windows' default dirs.
// While git is missing it re-checks every 10 s (async, never blocking the event loop); the moment
// git appears it fixes PATH (drops the shim / adds git's dir), flips the state and tells its
// listeners (the WS `host_requirements` frame, deferred resumes, tmux's global PATH).
//
// Nothing is bundled (the user's decision): the install action is one click into the OS's own
// installer — `xcode-select --install` on macOS, `winget` (or the download page) on Windows — and
// a hint on Linux.
import { execFile, spawn as nodeSpawn } from "child_process"
import { delimiter, win32 as winPath } from "path"
import { APPLE_GIT_STUB, applyCltGuard, noCltDir } from "./clt-guard"

export type GitInstall = "xcode-select" | "winget" | "browser" | "manual"

export interface GitRequirement {
  ok: boolean
  install: GitInstall
  hint: string
}

export interface HostRequirements {
  git: GitRequirement
}

export const GIT_REQUIRED_MESSAGE = "This computer needs git to run agents. Install it, then try again."

export const GIT_WINDOWS_DOWNLOAD_URL = "https://git-scm.com/download/win"
export const GIT_HINT_DARWIN = "Install Apple's Command Line Tools (xcode-select --install)"
export const GIT_HINT_WINGET = "Install Git for Windows with winget"
export const GIT_HINT_WINDOWS_BROWSER = `Download Git for Windows from ${GIT_WINDOWS_DOWNLOAD_URL}`
export const GIT_HINT_LINUX = "Install git with your package manager (e.g. sudo apt install git)"

export const GIT_RECHECK_MS = 10_000

/** `Bun.which` over an explicit PATH; null when absent. */
export function bunWhich(bin: string, path: string): string | null {
  try {
    return Bun.which(bin, { PATH: path }) ?? null
  } catch {
    return null
  }
}

export type RegistryScope = "user" | "machine"

const REGISTRY_KEYS: Record<RegistryScope, string> = {
  user: "HKCU\\Environment",
  machine: "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment",
}

/** The `Path` value out of `reg query <key> /v Path` output, or null. */
export function parseRegQueryPath(out: string): string | null {
  const m = out.match(/^\s*Path\s+REG_(?:EXPAND_)?SZ\s+(.*?)\s*$/im)
  return m?.[1] ? m[1] : null
}

/** The real registry reader: async `reg query`, 5 s cap, null on any failure. */
export function readRegistryPath(scope: RegistryScope): Promise<string | null> {
  return new Promise((resolve) => {
    try {
      execFile("reg", ["query", REGISTRY_KEYS[scope], "/v", "Path"], { timeout: 5_000, windowsHide: true }, (err, stdout) => {
        resolve(err ? null : parseRegQueryPath(String(stdout)))
      })
    } catch {
      resolve(null)
    }
  })
}

/** The real async `xcode-select -p`: its exit code, 1 on any failure (5 s cap). */
export function xcodeSelectExit(): Promise<number> {
  return new Promise((resolve) => {
    try {
      execFile("xcode-select", ["-p"], { timeout: 5_000 }, (err) => {
        if (!err) return resolve(0)
        const code = (err as { code?: unknown }).code
        resolve(typeof code === "number" && code !== 0 ? code : 1)
      })
    } catch {
      resolve(1)
    }
  })
}

export interface GitCheckDeps {
  platform: NodeJS.Platform
  /** The first `bin` on [path], or null. */
  which: (bin: string, path: string) => string | null
  /** `xcode-select -p`'s exit code, synchronously: the boot check only (before listen). */
  runXcodeSelectSync: () => number
  /** `xcode-select -p`'s exit code: every re-check. */
  runXcodeSelect: () => Promise<number>
  /** win32: the registry `Path` for [scope], null when absent. Defaults to none. */
  readRegistryPath?: (scope: RegistryScope) => Promise<string | null>
  stateDir: string
  env: Record<string, string | undefined>
}

/** What a re-check found: git, and on Windows the dir to add to PATH when it was off-PATH. */
export interface GitFound {
  found: boolean
  addDir?: string
}

/** PATH without our own failing-git shim dir: the shim is never "a git". */
export function pathWithoutShim(path: string | undefined, stateDir: string): string {
  const shim = noCltDir(stateDir)
  return (path ?? "").split(delimiter).filter((d) => d && d !== shim).join(delimiter)
}

/** `%VAR%` → env value (case-insensitive, as Windows does); unknown vars stay as written. */
export function expandWindowsVars(s: string, env: Record<string, string | undefined>): string {
  return s.replace(/%([^%]+)%/g, (whole, name: string) => {
    const key = Object.keys(env).find((k) => k.toLowerCase() === name.toLowerCase())
    const v = key ? env[key] : undefined
    return v ?? whole
  })
}

/** Git for Windows' default install dirs (per-user first, then machine-wide). */
export function windowsGitDefaultDirs(env: Record<string, string | undefined>): string[] {
  const dirs: string[] = []
  const local = expandWindowsVars("%LOCALAPPDATA%", env)
  if (!local.includes("%")) dirs.push(winPath.join(local, "Programs", "Git", "cmd"))
  const pf = expandWindowsVars("%ProgramFiles%", env)
  if (!pf.includes("%")) dirs.push(winPath.join(pf, "Git", "cmd"))
  return dirs
}

/** The boot check: synchronous, PATH only (plus `xcode-select -p` behind Apple's stub). */
export function checkGitSync(deps: GitCheckDeps): boolean {
  const git = deps.which("git", pathWithoutShim(deps.env.PATH, deps.stateDir))
  if (deps.platform === "darwin") {
    if (!git) return false
    if (git !== APPLE_GIT_STUB) return true
    return deps.runXcodeSelectSync() === 0
  }
  return git !== null
}

/** The re-check: async; on Windows it also looks where an installer puts git, off this PATH. */
export async function checkGit(deps: GitCheckDeps): Promise<GitFound> {
  const git = deps.which("git", pathWithoutShim(deps.env.PATH, deps.stateDir))
  if (deps.platform === "darwin") {
    if (!git) return { found: false }
    if (git !== APPLE_GIT_STUB) return { found: true }
    return { found: (await deps.runXcodeSelect()) === 0 }
  }
  if (git !== null) return { found: true }
  if (deps.platform !== "win32") return { found: false }

  const read = deps.readRegistryPath ?? (async () => null)
  const [user, machine] = await Promise.all([read("user").catch(() => null), read("machine").catch(() => null)])
  const fromRegistry = [user, machine]
    .flatMap((p) => (p ?? "").split(";"))
    .map((d) => expandWindowsVars(d.trim(), deps.env))
    .filter(Boolean)
  const seen = new Set<string>()
  for (const dir of [...fromRegistry, ...windowsGitDefaultDirs(deps.env)]) {
    const key = dir.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    if (deps.which("git", dir)) return { found: true, addDir: dir }
  }
  return { found: false }
}

/** How this OS installs git, and what to tell the user. */
export function gitInstallFor(
  platform: NodeJS.Platform,
  which: (bin: string, path: string) => string | null,
  path: string,
): { install: GitInstall; hint: string } {
  if (platform === "darwin") return { install: "xcode-select", hint: GIT_HINT_DARWIN }
  if (platform === "win32") {
    return which("winget", path)
      ? { install: "winget", hint: GIT_HINT_WINGET }
      : { install: "browser", hint: GIT_HINT_WINDOWS_BROWSER }
  }
  return { install: "manual", hint: GIT_HINT_LINUX }
}

export interface GitRequirementDeps extends GitCheckDeps {
  setInterval?: (fn: () => void, ms: number) => unknown
  clearInterval?: (handle: unknown) => void
  intervalMs?: number
  log?: (event: string, data: Record<string, unknown>) => void
  /** Git appeared and PATH changed (e.g. push it into tmux's global env). Best-effort. */
  onPathChanged?: (path: string) => void
}

/** Thrown (or reported) when an agent session is refused because git is missing. */
export class GitRequiredError extends Error {
  readonly code = "git_required"
  constructor(readonly requirements: HostRequirements) {
    super(GIT_REQUIRED_MESSAGE)
    this.name = "GitRequiredError"
  }
}

/** The body every refusal carries: the message plus the requirement object. */
export function gitRequiredBody(requirements: HostRequirements): { error: string; code: "git_required"; requirements: HostRequirements } {
  return { error: GIT_REQUIRED_MESSAGE, code: "git_required", requirements }
}

/**
 * The broker's live git requirement. [start] runs once at boot, synchronously (before anything
 * spawns git and before listen); while git is missing a 10 s timer re-checks asynchronously and,
 * once git is found, unblocks everything.
 */
export class GitRequirementMonitor {
  private state: GitRequirement
  private timer: unknown = undefined
  private inflight: Promise<boolean> | undefined
  private readonly listeners = new Set<(r: HostRequirements) => void>()

  constructor(private readonly deps: GitRequirementDeps) {
    this.state = { ok: true, ...this.installInfo() }
  }

  private installInfo() {
    return gitInstallFor(this.deps.platform, this.deps.which, pathWithoutShim(this.deps.env.PATH, this.deps.stateDir))
  }

  /** The boot check. On darwin, Apple's stub without the developer tools also gets the shim. */
  start(): GitRequirement {
    let ok: boolean
    const git = this.deps.which("git", pathWithoutShim(this.deps.env.PATH, this.deps.stateDir))
    if (this.deps.platform === "darwin" && git === APPLE_GIT_STUB) {
      // clt-guard writes the shim and prepends it exactly when the stub would open a dialog.
      ok = !applyCltGuard({ ...this.deps, runXcodeSelect: this.deps.runXcodeSelectSync }).gitUnavailable
    } else {
      // No git at all (or not a Mac): there is no stub to shim. Spawns of a missing git fail with
      // ENOENT, and the session refusal keeps most of them from happening at all.
      ok = checkGitSync(this.deps)
    }
    this.state = { ok, ...this.installInfo() }
    if (!ok) {
      this.deps.log?.("git_missing", { platform: this.deps.platform, install: this.state.install })
      this.startTimer()
    }
    return this.state
  }

  get git(): GitRequirement {
    return { ...this.state }
  }

  get ok(): boolean {
    return this.state.ok
  }

  requirements(): HostRequirements {
    return { git: this.git }
  }

  onChange(fn: (r: HostRequirements) => void): () => void {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  /** One re-check (overlapping calls share one). Resolves true when it flipped to "git found". */
  recheck(): Promise<boolean> {
    if (this.inflight) return this.inflight
    this.inflight = this.doRecheck().finally(() => { this.inflight = undefined })
    return this.inflight
  }

  private async doRecheck(): Promise<boolean> {
    if (this.state.ok) {
      this.stopTimer()
      return false
    }
    let r: GitFound
    try {
      r = await checkGit(this.deps)
    } catch (err) {
      this.deps.log?.("git_recheck_failed", { err: String(err) })
      return false
    }
    if (this.state.ok) return false // a stop()/concurrent path already settled it
    const info = this.installInfo()
    if (!r.found) {
      // winget can appear (or vanish) while we wait: keep the action current.
      if (info.install !== this.state.install || info.hint !== this.state.hint) {
        this.state = { ok: false, ...info }
        this.emit()
      }
      return false
    }
    const rest = pathWithoutShim(this.deps.env.PATH, this.deps.stateDir)
    this.deps.env.PATH = r.addDir ? [r.addDir, rest].filter(Boolean).join(delimiter) : rest
    this.state = { ok: true, ...this.installInfo() }
    this.stopTimer()
    this.deps.log?.("git_found", { platform: this.deps.platform, addedDir: r.addDir })
    try {
      this.deps.onPathChanged?.(this.deps.env.PATH)
    } catch (err) {
      this.deps.log?.("git_path_propagation_failed", { err: String(err) })
    }
    this.emit()
    return true
  }

  stop(): void {
    this.stopTimer()
  }

  private emit() {
    const r = this.requirements()
    for (const fn of this.listeners) {
      try {
        fn(r)
      } catch (err) {
        this.deps.log?.("git_requirement_listener_failed", { err: String(err) })
      }
    }
  }

  private startTimer() {
    if (this.timer !== undefined) return
    const set = this.deps.setInterval ?? ((fn: () => void, ms: number) => setInterval(fn, ms))
    const h = set(() => { void this.recheck() }, this.deps.intervalMs ?? GIT_RECHECK_MS)
    ;(h as { unref?: () => void } | undefined)?.unref?.()
    this.timer = h
  }

  private stopTimer() {
    if (this.timer === undefined) return
    const clear = this.deps.clearInterval ?? ((h: unknown) => clearInterval(h as ReturnType<typeof setInterval>))
    clear(this.timer)
    this.timer = undefined
  }
}

/** `tmux set-environment -g PATH <path>`: new tmux windows lose the shim. Async, errors ignored. */
export function setTmuxGlobalPath(path: string): void {
  try {
    execFile("tmux", ["set-environment", "-g", "PATH", path], { timeout: 5_000 }, () => {})
  } catch {}
}

// ── POST /system/install-git ─────────────────────────────────────────────────────────────────

export type InstallGitResponse =
  | { status: 200; body: { ok: true; alreadyInstalled?: boolean; inProgress?: boolean } }
  | { status: 400; body: { error: "manual"; hint: string } }

/** A started installer: [onExit] fires once, when it exits or fails to start. */
export interface RunningInstall {
  onExit(cb: () => void): void
}

/** Starts [cmd] detached. Never throws, never leaves an unhandled `error` event. */
export type DetachedSpawner = (cmd: string[]) => RunningInstall

export const XCODE_SELECT_INSTALL = ["xcode-select", "--install"]
/**
 * `--source winget`: Git.Git lives in the community source. Without it winget also queries the
 * Microsoft Store source, and when that one fails (certificate pinning behind TLS inspection, seen
 * on a fresh Windows 11 VM: 0x8A15005E) `install` aborts although the package was found.
 */
export const WINGET_INSTALL_GIT = [
  "winget", "install", "--id", "Git.Git", "-e", "--source", "winget", "--scope", "user",
  "--accept-source-agreements", "--accept-package-agreements",
]
/** Fixed argv, no shell: the default browser on the Git for Windows download page. */
export const OPEN_GIT_DOWNLOAD_PAGE = ["explorer.exe", GIT_WINDOWS_DOWNLOAD_URL]

/**
 * The real spawner. A missing binary surfaces as an async `error` event on the child (ENOENT): an
 * unhandled one takes the whole broker down, so the handler is attached before anything else.
 */
export function spawnDetached(cmd: string[], log?: (event: string, data: Record<string, unknown>) => void): RunningInstall {
  let done = false
  const waiters: Array<() => void> = []
  const finish = () => {
    if (done) return
    done = true
    for (const w of waiters.splice(0)) {
      try { w() } catch {}
    }
  }
  try {
    const child = nodeSpawn(cmd[0]!, cmd.slice(1), { detached: true, stdio: "ignore", windowsHide: false })
    child.on("error", (err) => {
      log?.("install_git_spawn_failed", { cmd: cmd[0], err: String(err) })
      finish()
    })
    child.on("exit", (code) => {
      log?.("install_git_exited", { cmd: cmd[0], code })
      finish()
    })
    child.unref()
  } catch (err) {
    log?.("install_git_spawn_failed", { cmd: cmd[0], err: String(err) })
    finish()
  }
  return { onExit: (cb) => { if (done) cb(); else waiters.push(cb) } }
}

export const INSTALL_COOLDOWN_MS = 60_000
export const WINGET_MAX_MS = 15 * 60_000

/**
 * The one-click install, debounced: Apple's installer and the download page get a 60 s cooldown
 * after a launch; a winget install is tracked and refused while it runs (at most 15 min). Both
 * answer `{ok:true, inProgress:true}` meanwhile.
 */
export class GitInstaller {
  private lastLaunchAt: number | undefined
  private winget: { startedAt: number } | undefined

  constructor(private readonly deps: {
    platform: NodeJS.Platform
    requirement: () => GitRequirement
    /** Is `winget` on PATH (win32)? */
    hasWinget: () => boolean
    spawn: DetachedSpawner
    now?: () => number
  }) {}

  private now(): number {
    return this.deps.now?.() ?? Date.now()
  }

  install(): InstallGitResponse {
    if (this.deps.requirement().ok) return { status: 200, body: { ok: true, alreadyInstalled: true } }
    const { platform } = this.deps
    const now = this.now()
    if (this.winget && now - this.winget.startedAt < WINGET_MAX_MS) return { status: 200, body: { ok: true, inProgress: true } }
    this.winget = undefined
    if (this.lastLaunchAt !== undefined && now - this.lastLaunchAt < INSTALL_COOLDOWN_MS) {
      return { status: 200, body: { ok: true, inProgress: true } }
    }

    if (platform === "darwin") {
      this.deps.spawn(XCODE_SELECT_INSTALL)
      this.lastLaunchAt = now
      return { status: 200, body: { ok: true } }
    }
    if (platform === "win32") {
      if (this.deps.hasWinget()) {
        const run = { startedAt: now }
        this.winget = run
        this.deps.spawn(WINGET_INSTALL_GIT).onExit(() => { if (this.winget === run) this.winget = undefined })
        return { status: 200, body: { ok: true } }
      }
      this.deps.spawn(OPEN_GIT_DOWNLOAD_PAGE)
      this.lastLaunchAt = now
      return { status: 200, body: { ok: true } }
    }
    return { status: 400, body: { error: "manual", hint: GIT_HINT_LINUX } }
  }
}
