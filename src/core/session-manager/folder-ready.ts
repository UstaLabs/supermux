// Card #75: a session born asleep has no folder until its first wake checks its worktree out (a
// fraction of a second after Send, plus the repo's setup hook). The files pane and a terminal that
// ask for it meanwhile wait for it instead of failing. Any other path answers at once.
import { resolve, sep } from "path"

export type FolderReadyDeps = {
  /** The live sessions. */
  sessions(): Array<{ id: string; workdir: string }>
  /** Something is queued or running on the session's chain (a wake in flight). */
  pending(id: string): boolean
  exists(path: string): boolean
  sleep(ms: number): Promise<void>
  now(): number
}

const POLL_MS = 50

/** Resolves once `path` exists, or at once when no session's wake is about to make it. */
export async function folderReady(deps: FolderReadyDeps, path: string, timeoutMs = 30_000): Promise<void> {
  if (!path || deps.exists(path)) return
  const target = resolve(path)
  const owner = deps.sessions().find((s) => target === s.workdir || target.startsWith(s.workdir + sep))
  if (!owner) return
  const until = deps.now() + timeoutMs
  while (!deps.exists(owner.workdir) && deps.pending(owner.id) && deps.now() < until) await deps.sleep(POLL_MS)
}
