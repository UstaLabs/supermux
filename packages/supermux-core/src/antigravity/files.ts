import { realpathSync } from "node:fs"
import { rm } from "node:fs/promises"
import { join } from "node:path"
import { assertRealDirectory, present, readPrivateFile, realDirectoryIn, realStateDirectory, writePrivateFile } from "../private-files.js"

/*
 * Antigravity's own files of a session, beside its keeper: the conversation state `<state>/antigravity/<id>.json`, and a
 * directory `<state>/antigravity/<id>` (its attachments). Never reached through a symbolic link (see private-files.ts):
 * every folder on the way is a directory of this user's that no link leads to, made (0700) one level at a time, and the
 * state file is written new and renamed into place.
 */

/** A session id that names one file and one folder under `antigravity/`. */
function requireSessionId(sessionId: string): string {
  if (!sessionId || sessionId === "." || sessionId === ".." || /[\\/]/.test(sessionId)) throw new TypeError("invalid sessionId")
  return sessionId
}

/** `<real state directory>/antigravity`, made when missing. */
function antigravityFolder(stateDirectory: string): string {
  return realDirectoryIn(realStateDirectory(stateDirectory), "antigravity")
}

/** `<real state directory>/antigravity` when it is there (checked), else undefined. */
function existingFolder(stateDirectory: string): string | undefined {
  if (!present(stateDirectory)) return undefined
  const folder = join(realpathSync(stateDirectory), "antigravity")
  if (!present(folder)) return undefined
  assertRealDirectory(folder)
  return folder
}

/** Saves the conversation state of session `sessionId`. */
export function saveAntigravityState(stateDirectory: string, sessionId: string, body: string): void {
  const folder = antigravityFolder(stateDirectory)
  writePrivateFile(join(folder, `${requireSessionId(sessionId)}.json`), body, () => assertRealDirectory(folder))
}

/** The saved conversation state of session `sessionId`; undefined when there is none (a link there is refused, never read through). */
export function loadAntigravityState(stateDirectory: string, sessionId: string): string | undefined {
  requireSessionId(sessionId)
  const folder = existingFolder(stateDirectory)
  return folder === undefined ? undefined : readPrivateFile(join(folder, `${sessionId}.json`))
}

/** The attachments folder of session `sessionId`, `<state>/antigravity/<sessionId>/attachments`, each level made when missing. */
export function antigravityAttachments(stateDirectory: string, sessionId: string): string {
  return realDirectoryIn(realDirectoryIn(antigravityFolder(stateDirectory), requireSessionId(sessionId)), "attachments")
}

/** Deletes them: what `forget` leaves of a session once its keeper's directory is gone. */
export async function removeAntigravitySession(stateDirectory: string, sessionId: string): Promise<void> {
  requireSessionId(sessionId)
  // Checked first: through a folder swapped for a link, these would delete elsewhere.
  const folder = existingFolder(stateDirectory)
  if (folder === undefined) return
  await rm(join(folder, `${sessionId}.json`), { force: true })
  await rm(join(folder, sessionId), { recursive: true, force: true })
}
