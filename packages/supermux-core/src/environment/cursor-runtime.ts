import {
  existsSync,
  mkdirSync,
  lstatSync,
  readlinkSync,
  readdirSync,
  rmSync,
  renameSync,
  cpSync,
  symlinkSync,
  type Stats,
} from "node:fs"
import { join, dirname } from "node:path"

// The cursor-agent runtime payload (`versions/<build>/`, ~178 MB) is identical
// across sessions — keyed by build id, additive, read at runtime. We keep one
// shared copy and symlink each session home's `.local/share/cursor-agent` at it
// instead of letting every isolated $HOME re-bootstrap its own copy.
//
// Codex is intentionally NOT shared: its `.tmp/plugins` is a per-session write
// target whose content depends on the session's enabled plugin set (the
// `plugins.sha` differs across homes), so a shared dir would clobber + churn.

const CURSOR_RUNTIME_REL = join(".local", "share", "cursor-agent")

export function cursorRuntimeRel(): string {
  return CURSOR_RUNTIME_REL
}

export function sharedCursorDir(stateDir: string): string {
  if (!stateDir) throw new TypeError("sharedCursorDir stateDir is required")
  return join(stateDir, "shared", "cursor-agent")
}

function lstatSafe(p: string): Stats | undefined {
  try {
    return lstatSync(p)
  } catch {
    return undefined
  }
}

function isNonEmptyDir(p: string): boolean {
  try {
    return readdirSync(p).length > 0
  } catch {
    return false
  }
}

/**
 * Ensure a cursor session's `.local/share/cursor-agent` is a symlink to the one
 * shared runtime copy, populating the shared copy on first use. Idempotent and
 * never throws — a failure here must not block a spawn (worst case the session
 * keeps its own private copy).
 *
 * Population order (first time the shared dir is empty):
 *   1. Seed from the user's real `~/.local/share/cursor-agent` if present — the
 *      canonical, known-complete copy. (Preferred over adopting a session home,
 *      whose runtime may be partial/incomplete and would poison the shared dir
 *      every other session then links to.)
 *   2. Else, if THIS home already has a real bootstrapped runtime dir, move it
 *      into the shared location (instant rename, no copy).
 *   3. Else leave the shared dir empty — cursor-agent bootstraps into it
 *      (through the symlink) on first run.
 *
 * Synchronous on purpose: JS is single-threaded, so the one-time populate step
 * cannot race a concurrent spawn, and no async mutex is needed.
 */
export function ensureSharedCursorRuntime(
  sessionHome: string,
  opts: { sharedDir: string; userRuntime: string },
): void {
  if (!sessionHome) return
  const shared = opts.sharedDir
  const userRuntime = opts.userRuntime
  const linkPath = join(sessionHome, CURSOR_RUNTIME_REL)

  try {
    mkdirSync(dirname(shared), { recursive: true })
    mkdirSync(dirname(linkPath), { recursive: true, mode: 0o700 })

    // 1. Make sure the shared copy is populated. Prefer the user's canonical
    //    install (known complete) over adopting this home's runtime, which may
    //    be partial and would poison every session that links to shared.
    if (!isNonEmptyDir(shared)) {
      const here = lstatSafe(linkPath)
      if (isNonEmptyDir(userRuntime)) {
        cpSync(userRuntime, shared, { recursive: true, dereference: false })
      } else if (here && here.isDirectory() && !here.isSymbolicLink()) {
        // No canonical install — adopt this home's runtime (instant move).
        rmSync(shared, { recursive: true, force: true })
        mkdirSync(dirname(shared), { recursive: true })
        renameSync(linkPath, shared)
      } else {
        mkdirSync(shared, { recursive: true })
      }
    }

    // 2. Point the link at the shared copy.
    const cur = lstatSafe(linkPath)
    if (cur) {
      if (cur.isSymbolicLink()) {
        let target: string | undefined
        try {
          target = readlinkSync(linkPath)
        } catch {
          /* dangling */
        }
        if (target === shared) return // already correct
        rmSync(linkPath, { force: true })
      } else {
        // A real dir/file (another home's leftover copy) — drop it; the shared
        // copy already holds the runtime.
        rmSync(linkPath, { recursive: true, force: true })
      }
    }
    symlinkSync(shared, linkPath)
  } catch {
    // A failure here must not block a spawn (worst case the session keeps its
    // own private copy).
  }
}
