// Card #75: a session born asleep has no folder until its first wake checks its worktree out (a
// fraction of a second after Send; a large repo takes a few). The files pane and a terminal that ask
// for it meanwhile wait until git has checked the files out, instead of failing or listing a folder
// half full. The repo's setup hook is not waited for. Any other path answers at once.
import { paths } from "@supermux/platform"

export type FolderReadyDeps = {
  /** The live sessions. */
  sessions(): Array<{ id: string; workdir: string }>
  /** Something is queued or running on the session's chain (a wake in flight). */
  pending(id: string): boolean
  exists(path: string): boolean
  /** The folders git is checking a session's worktree out into right now. */
  checkoutsInFlight(): string[]
  sleep(ms: number): Promise<void>
  now(): number
}

const POLL_MS = 50

/** Resolves once `path` is checked out, or at once when no session's wake is about to make it. */
export async function folderReady(deps: FolderReadyDeps, path: string, timeoutMs = 30_000): Promise<void> {
  if (!path) return
  // The host's rule (separators, letter case, drives), not a prefix: on Windows a folder spelled with `/` or another
  // case never matched, so nothing waited.
  const inside = (dir: string) => paths.isInside(path, dir)
  const until = deps.now() + timeoutMs
  let owner: { id: string; workdir: string } | undefined | null = null
  while (deps.now() < until) {
    if (!deps.checkoutsInFlight().some(inside)) {
      if (deps.exists(path)) return
      owner ??= deps.sessions().find((s) => inside(s.workdir))
      // Nothing is making it: answer now (the caller reports the missing folder, as before).
      if (!owner || deps.exists(owner.workdir) || !deps.pending(owner.id)) return
    }
    await deps.sleep(POLL_MS)
  }
}
