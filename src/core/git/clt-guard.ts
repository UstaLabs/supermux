// macOS without the Xcode Command Line Tools: `/usr/bin/git` is Apple's stub, and EVERY run of it
// pops the "git requires the command line developer tools" install dialog. The broker (worktrees,
// lite-status, forge, editor diff, plugins…) and every agent session run git by name, so on such a
// Mac the user gets that dialog over and over.
//
// The fix is central and happens once at startup, before anything spawns (driven by
// `requirement.ts`'s GitRequirementMonitor): put a tiny `git` that just fails (with a one-line
// reason) ahead of the stub on PATH. Every child — agent sessions,
// tmux, helpers — inherits it. A real git earlier on PATH (Homebrew, a CLT install) is left alone.
import { accessSync, chmodSync, constants, mkdirSync, writeFileSync } from "fs"
import { delimiter, join } from "path"

export const APPLE_GIT_STUB = "/usr/bin/git"

export const NO_CLT_MESSAGE = "git is not available: install the Xcode Command Line Tools (xcode-select --install)"

/** The shim's exact contents. */
export const NO_CLT_SHIM = `#!/bin/sh\necho "${NO_CLT_MESSAGE}" >&2\nexit 1\n`

/** `<stateDir>/run/no-clt`: the directory that holds the failing `git`. */
export function noCltDir(stateDir: string): string {
  return join(stateDir, "run", "no-clt")
}

export interface CltGuardDeps {
  platform: NodeJS.Platform
  /** The first `bin` on [path], or null. */
  which: (bin: string, path: string) => string | null
  /** `xcode-select -p`'s exit code (non-zero: no developer tools). */
  runXcodeSelect: () => number
  stateDir: string
  /** Mutated: PATH is prepended when the guard applies. */
  env: Record<string, string | undefined>
}

export interface CltGuardResult {
  gitUnavailable: boolean
  reason?: string
  shimDir?: string
}

/** The first executable [bin] on [path] (a plain PATH walk, no spawn). */
export function whichOnPath(bin: string, path: string): string | null {
  for (const dir of path.split(delimiter)) {
    if (!dir) continue
    const p = join(dir, bin)
    try {
      accessSync(p, constants.X_OK)
      return p
    } catch {}
  }
  return null
}

/**
 * Pure apart from [deps]: on darwin, when the first `git` on PATH is Apple's stub and the developer
 * tools are missing, write the failing shim and prepend its dir to `deps.env.PATH`.
 */
export function applyCltGuard(deps: CltGuardDeps): CltGuardResult {
  if (deps.platform !== "darwin") return { gitUnavailable: false }
  const dir = noCltDir(deps.stateDir)
  // Our own shim (inherited from a parent broker, or a second call) is not "a git": look past it.
  const rest = (deps.env.PATH ?? "").split(delimiter).filter((d) => d && d !== dir)
  const git = deps.which("git", rest.join(delimiter))
  if (git !== APPLE_GIT_STUB) return { gitUnavailable: false }
  if (deps.runXcodeSelect() === 0) return { gitUnavailable: false }

  mkdirSync(dir, { recursive: true, mode: 0o700 })
  const shim = join(dir, "git")
  writeFileSync(shim, NO_CLT_SHIM, { mode: 0o755 })
  chmodSync(shim, 0o755) // an existing file keeps its old mode on write
  deps.env.PATH = [dir, ...rest].join(delimiter)
  return {
    gitUnavailable: true,
    reason: "the Xcode Command Line Tools are not installed (/usr/bin/git is Apple's stub)",
    shimDir: dir,
  }
}
