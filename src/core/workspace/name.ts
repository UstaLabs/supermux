import type { WorkspaceStore } from "./store"

/**
 * Spec §9.5 — the workspace name follows its primary session.
 *
 * Call this after a session rename, whoever caused it: the agent's own
 * rename_session shim tool or the user. Returns the workspace id when a rename
 * happened, so the caller knows to broadcast workspace_changed. Returns
 * undefined when nothing was written.
 *
 * A rename that changes nothing MUST NOT write. The rename route broadcasts on
 * every write, and a write-then-broadcast on an unchanged name is how a rename
 * loop starts.
 */
export function propagateSessionRename(
  store: WorkspaceStore,
  sessionId: string,
  newName: string,
): string | undefined {
  const ws = store.findByPrimarySession(sessionId)
  if (!ws) return undefined
  if (ws.name_locked) return undefined
  if (ws.name === newName) return undefined
  store.rename(ws.id, newName, { byUser: false })
  return ws.id
}

/**
 * Spec §9.5 rule 6 — when the primary session goes, point at the oldest chat
 * session that is left. The NAME does not change here; only the next rename of
 * the new primary session moves it.
 *
 * Returns the new primary session id, or undefined when no chat view remains.
 */
export function repointPrimarySession(
  store: WorkspaceStore,
  workspaceId: string,
): string | undefined {
  const ws = store.getById(workspaceId)
  if (!ws) return undefined
  // listViews orders by created_at, so the first chat view is the oldest one.
  const next = store.chatSessionIds(workspaceId).find((id) => id !== ws.primary_session_id)
  store.setPrimarySession(workspaceId, next ?? null)
  return next
}
