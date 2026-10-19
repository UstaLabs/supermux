import { rm } from "node:fs/promises"
import { join } from "node:path"

/** Antigravity's own files of a session, beside its keeper: the conversation state, and a directory (its attachments). */
export function antigravitySessionFiles(stateDirectory: string, sessionId: string): { state: string; directory: string } {
  return { state: join(stateDirectory, "antigravity", `${sessionId}.json`), directory: join(stateDirectory, "antigravity", sessionId) }
}

/** Deletes them: what `forget` leaves of a session once its keeper's directory is gone. */
export async function removeAntigravitySession(stateDirectory: string, sessionId: string): Promise<void> {
  if (!sessionId || sessionId === "." || sessionId === ".." || /[\\/]/.test(sessionId)) throw new TypeError("invalid sessionId")
  const files = antigravitySessionFiles(stateDirectory, sessionId)
  await rm(files.state, { force: true })
  await rm(files.directory, { recursive: true, force: true })
}
