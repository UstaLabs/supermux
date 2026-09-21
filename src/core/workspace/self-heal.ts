import type { Database as Db } from "bun:sqlite"
import type { WorkspaceStore } from "./store"
import { makeLogger } from "../../shared/log"

const log = makeLogger("workspace/self-heal")

type OrphanRow = {
  id: string
  name: string
  workdir: string
  repo_root: string | null
  base_branch: string | null
  session_branch: string | null
  sort_order: number
  internal: number
}

/**
 * Spec §9.3 — every live session must have exactly one chat view in exactly one
 * workspace. Migration 027 guarantees it for every row that existed then, and
 * the session-create path guarantees it for new rows.
 *
 * This repairs a database where it is false anyway: a crash between the session
 * insert and the workspace insert, or a workspace row deleted by hand. Run it
 * once at broker startup.
 *
 * A heal is a DEFECT SIGNAL, not a normal path. It logs at warn on purpose.
 * Returns the session ids that were healed.
 *
 * `ensureProject`, when given, registers each healed row's location with the
 * project catalog (startup reconciliation would pick it up anyway; this keeps a
 * heal self-contained). `internal` is passed through so the caller can no-op
 * for a broker-internal session (e.g. an rpc-worker) — its workspace is still
 * healed (hidden workspaces are a normal, supported shape), but it must never
 * gain a project.
 */
export function healSessionsWithoutWorkspace(
  db: Db,
  store: WorkspaceStore,
  ensureProject?: (w: { workdir: string; repo_root?: string; internal: boolean }) => void,
): string[] {
  const orphans = db.query(`
    SELECT s.id, s.name, s.workdir, s.repo_root, s.base_branch, s.session_branch, s.sort_order, s.internal
      FROM sessions s
     WHERE s.status IN ('active', 'suspended')
       AND (s.workspace_id IS NULL
            OR NOT EXISTS (SELECT 1 FROM workspaces w WHERE w.id = s.workspace_id))
  `).all() as OrphanRow[]

  const healed: string[] = []
  for (const s of orphans) {
    ensureProject?.({ workdir: s.workdir, repo_root: s.repo_root ?? undefined, internal: s.internal === 1 })
    const ws = store.create({
      name: s.name,
      workdir: s.workdir,
      repo_root: s.repo_root ?? undefined,
      base_branch: s.base_branch ?? undefined,
      branch: s.session_branch ?? undefined,
      primary_session_id: s.id,
      sort_order: s.sort_order,
    })
    store.addView(ws.id, { kind: "chat", state: { sessionId: s.id } })
    db.run("UPDATE sessions SET workspace_id = ? WHERE id = ?", [ws.id, s.id])
    healed.push(s.id)
    log.warn("workspace_self_heal", { session: s.id, workspace: ws.id, name: s.name })
  }
  return healed
}
