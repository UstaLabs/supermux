// Git is required to host agents (spec 2026-09-30-desktop-hosting-lifecycle-design, "Git is required
// for hosting agents"). Worktrees, the finish flow, diffs and the agents themselves all run git; on a
// computer without one an agent session is half-broken at best, and on a Mac without the Command
// Line Tools every git run pops Apple's install dialog.
//
// This module is the broker's single answer to "is there a usable git here?":
//   - darwin: the first `git` on PATH (past our own shim) is not Apple's stub, or `xcode-select -p`
//     succeeds. While missing, `clt-guard`'s failing shim stays first on PATH (no dialogs).
//   - linux / win32: a `git` resolvable on PATH.
// While git is missing it re-checks every 10 s; the moment git appears it drops the shim from PATH,
// flips the state and tells its listeners (the WS `host_requirements` frame, deferred resumes).
//
// Nothing is bundled (the user's decision): the install action is one click into the OS's own
// installer — `xcode-select --install` on macOS, `winget` on Windows — and a hint elsewhere.
import { spawn as nodeSpawn } from "child_process"
import { delimiter } from "path"
import { APPLE_GIT_STUB, applyCltGuard, noCltDir } from "./clt-guard"

export type GitInstall = "xcode-select" | "winget" | "manual"

export interface GitRequirement {
  ok: boolean
  install: GitInstall
  hint: string
}

export interface HostRequirements {
  git: GitRequirement
}

export const GIT_REQUIRED_MESSAGE = "This computer needs git to run agents. Install it, then try again."

export const GIT_HINT_DARWIN = "Install Apple's Command Line Tools (xcode-select --install)"
export const GIT_HINT_WINGET = "Install Git for Windows with winget"
export const GIT_HINT_WINDOWS_MANUAL = "Install Git for Windows from https://git-scm.com/download/win"
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

export interface GitCheckDeps {
  platform: NodeJS.Platform
  /** The first `bin` on [path], or null. */
  which: (bin: string, path: string) => string | null
  /** `xcode-select -p`'s exit code (darwin only). */
  runXcodeSelect: () => number
  stateDir: string
  env: Record<string, string | undefined>
}

/** PATH without our own failing-git shim dir: the shim is never "a git". */
export function pathWithoutShim(path: string | undefined, stateDir: string): string {
  const shim = noCltDir(stateDir)
  return (path ?? "").split(delimiter).filter((d) => d && d !== shim).join(delimiter)
}

/** Is there a usable git? Pure apart from [deps]. */
export function checkGit(deps: GitCheckDeps): boolean {
  const path = pathWithoutShim(deps.env.PATH, deps.stateDir)
  const git = deps.which("git", path)
  if (deps.platform === "darwin") {
    if (git && git !== APPLE_GIT_STUB) return true
    if (!git) return false
    return deps.runXcodeSelect() === 0
  }
  return git !== null
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
      : { install: "manual", hint: GIT_HINT_WINDOWS_MANUAL }
  }
  return { install: "manual", hint: GIT_HINT_LINUX }
}

export interface GitRequirementDeps extends GitCheckDeps {
  setInterval?: (fn: () => void, ms: number) => unknown
  clearInterval?: (handle: unknown) => void
  intervalMs?: number
  log?: (event: string, data: Record<string, unknown>) => void
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
 * The broker's live git requirement. [start] runs once at boot (before anything spawns git); while
 * git is missing a 10 s timer re-checks and, once git is found, unblocks everything.
 */
export class GitRequirementMonitor {
  private state: GitRequirement
  private timer: unknown = undefined
  private readonly listeners = new Set<(r: HostRequirements) => void>()

  constructor(private readonly deps: GitRequirementDeps) {
    this.state = { ok: true, ...this.installInfo() }
  }

  private installInfo() {
    return gitInstallFor(this.deps.platform, this.deps.which, pathWithoutShim(this.deps.env.PATH, this.deps.stateDir))
  }

  /** The boot check. On darwin a missing git also puts the failing shim first on PATH. */
  start(): GitRequirement {
    let ok: boolean
    if (this.deps.platform === "darwin") {
      // clt-guard writes the shim and prepends it exactly when git is missing here.
      ok = !applyCltGuard(this.deps).gitUnavailable
    } else {
      // No stub to shim elsewhere: spawns of a missing git fail with ENOENT, and the session
      // refusal keeps most of them from happening at all.
      ok = checkGit(this.deps)
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

  /** One re-check. Returns true when it flipped to "git found". */
  recheck(): boolean {
    if (this.state.ok) {
      this.stopTimer()
      return false
    }
    const found = checkGit(this.deps)
    const info = this.installInfo()
    if (!found) {
      // winget can appear (or vanish) while we wait: keep the action current.
      if (info.install !== this.state.install || info.hint !== this.state.hint) {
        this.state = { ok: false, ...info }
        this.emit()
      }
      return false
    }
    this.deps.env.PATH = pathWithoutShim(this.deps.env.PATH, this.deps.stateDir)
    this.state = { ok: true, ...info }
    this.stopTimer()
    this.deps.log?.("git_found", { platform: this.deps.platform })
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
    const h = set(() => this.recheck(), this.deps.intervalMs ?? GIT_RECHECK_MS)
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

// ── POST /system/install-git ─────────────────────────────────────────────────────────────────

export type InstallGitResponse =
  | { status: 200; body: { ok: true; alreadyInstalled?: boolean } }
  | { status: 400; body: { error: "manual"; hint: string } }

/** Starts [cmd] detached. Never throws, never leaves an unhandled `error` event. */
export type DetachedSpawner = (cmd: string[]) => void

export const XCODE_SELECT_INSTALL = ["xcode-select", "--install"]
export const WINGET_INSTALL_GIT = [
  "winget", "install", "--id", "Git.Git", "-e", "--scope", "user",
  "--accept-source-agreements", "--accept-package-agreements",
]

/**
 * The real spawner. A missing binary surfaces as an async `error` event on the child (ENOENT): an
 * unhandled one takes the whole broker down, so the handler is attached before anything else.
 */
export function spawnDetached(cmd: string[], log?: (event: string, data: Record<string, unknown>) => void): void {
  try {
    const child = nodeSpawn(cmd[0]!, cmd.slice(1), { detached: true, stdio: "ignore", windowsHide: false })
    child.on("error", (err) => log?.("install_git_spawn_failed", { cmd: cmd[0], err: String(err) }))
    child.unref()
  } catch (err) {
    log?.("install_git_spawn_failed", { cmd: cmd[0], err: String(err) })
  }
}

/** Decide and start the one-click install for this OS. */
export function installGit(opts: {
  platform: NodeJS.Platform
  requirement: GitRequirement
  /** Is `winget` on PATH (win32)? */
  hasWinget: () => boolean
  spawn: DetachedSpawner
}): InstallGitResponse {
  if (opts.requirement.ok) return { status: 200, body: { ok: true, alreadyInstalled: true } }
  if (opts.platform === "darwin") {
    opts.spawn(XCODE_SELECT_INSTALL)
    return { status: 200, body: { ok: true } }
  }
  if (opts.platform === "win32" && opts.hasWinget()) {
    opts.spawn(WINGET_INSTALL_GIT)
    return { status: 200, body: { ok: true } }
  }
  const hint = opts.platform === "win32" ? GIT_HINT_WINDOWS_MANUAL : GIT_HINT_LINUX
  return { status: 400, body: { error: "manual", hint } }
}
