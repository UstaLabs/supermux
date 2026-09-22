import {
  existsSync,
  lstatSync,
  readdirSync,
  rmSync,
} from "fs"
import { join } from "path"
import { STATE_DIR } from "../../shared/paths"
import { home } from "../../shared/home"
import { makeLogger } from "../../shared/log"
import {
  ensureSharedCursorRuntime as ensureSharedCursorRuntimeLib,
  sharedCursorDir as sharedCursorDirLib,
  cursorRuntimeRel,
} from "../../../packages/supermux-core/src/environment/index.js"

const log = makeLogger("agents/shared-runtime")

export function sharedCursorDir(stateDir: string = STATE_DIR): string {
  return sharedCursorDirLib(stateDir)
}

export function ensureSharedCursorRuntime(
  sessionHome: string,
  opts?: { sharedDir?: string; userRuntime?: string },
): void {
  ensureSharedCursorRuntimeLib(sessionHome, {
    sharedDir: opts?.sharedDir ?? sharedCursorDir(),
    userRuntime: opts?.userRuntime ?? join(home(), cursorRuntimeRel()),
  })
}

/**
 * Delete agent home dirs under `state/agents/{cursor,codex}` that have NO
 * registry entry at all (true orphans left by renames, crashes, or manual
 * mucking). `knownHomes` is the set of `agent_home` paths for EVERY session in
 * the registry regardless of status — archived sessions are resumable
 * (`resumeFromArchive`), so their homes must be kept. Because cursor homes hold
 * a symlink to the shared runtime, deleting an orphan reclaims only that home's
 * own footprint, never the shared copy. Never throws.
 */
export function gcOrphanAgentHomes(
  knownHomes: Set<string>,
  opts?: { stateDir?: string; dryRun?: boolean },
): { removed: string[]; candidates: string[] } {
  const stateDir = opts?.stateDir ?? STATE_DIR
  const dryRun = opts?.dryRun ?? false
  const removed: string[] = []
  const candidates: string[] = []
  for (const kind of ["cursor", "codex"] as const) {
    const base = join(stateDir, "agents", kind)
    if (!existsSync(base)) continue
    let names: string[]
    try {
      names = readdirSync(base)
    } catch {
      continue
    }
    for (const name of names) {
      const homePath = join(base, name)
      if (knownHomes.has(homePath)) continue
      candidates.push(homePath)
      if (dryRun) continue
      try {
        rmSync(homePath, { recursive: true, force: true })
        removed.push(homePath)
      } catch (err: unknown) {
        const message = err instanceof Error ? err.message : String(err)
        log.warn("gc_orphan_home_failed", { homePath, err: message })
      }
    }
  }
  if (removed.length) log.info("gc_orphan_homes", { count: removed.length })
  return { removed, candidates }
}

function lstatSafe(p: string) {
  try {
    return lstatSync(p)
  } catch {
    return undefined
  }
}

/**
 * Collapse every cursor home under `state/agents/cursor` to a symlink at the
 * shared runtime (seeding the shared copy on first use). Safe for archived homes
 * too — a symlinked runtime works identically on resume. Idempotent; never
 * throws. Returns the homes that were (re)linked this pass.
 */
export function reclaimCursorHomes(opts?: { stateDir?: string; userRuntime?: string }): { linked: string[] } {
  const stateDir = opts?.stateDir ?? STATE_DIR
  const base = join(stateDir, "agents", "cursor")
  const shared = sharedCursorDir(stateDir)
  const linked: string[] = []
  if (!existsSync(base)) return { linked }
  let names: string[]
  try {
    names = readdirSync(base)
  } catch {
    return { linked }
  }
  for (const name of names) {
    const homePath = join(base, name)
    if (!lstatSafe(homePath)?.isDirectory()) continue
    ensureSharedCursorRuntime(homePath, { sharedDir: shared, userRuntime: opts?.userRuntime })
    linked.push(homePath)
  }
  return { linked }
}
