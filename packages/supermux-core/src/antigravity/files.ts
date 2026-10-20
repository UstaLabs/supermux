import { randomUUID } from "node:crypto"
import { realpathSync } from "node:fs"
import { rm } from "node:fs/promises"
import { basename, dirname, join } from "node:path"
import { CoreError } from "../errors.js"
import { assertRealDirectory, present, readPrivateFile, realDirectoryIn, realStateDirectory, writePrivateFile } from "../private-files.js"
import type { ContentBlock } from "../types.js"

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

const IMAGE_EXTENSIONS: Record<string, string> = { "image/png": "png", "image/jpeg": "jpg", "image/gif": "gif", "image/webp": "webp" }

/** `dir`, made (0700) when missing in its parent, which must be a real directory of this user's (no link on the way). */
function attachmentsFolder(dir: string): string {
  assertRealDirectory(dirname(dir))
  return realDirectoryIn(dirname(dir), basename(dir))
}

/**
 * The prompt's blocks as agy takes them (the driver's, not part of `@supermux/core/antigravity`). Text blocks joined by a
 * blank line; each image written to the attachments folder (a new 0600 file) and referenced as `[Attached image: <abs path>]`;
 * any other block → CoreError invalid_input. `attachments` is that folder, made (0700) when missing in its parent, which must
 * be a real directory of this user's; or a function that returns it, called at the first image. Nothing is written through a
 * symbolic link (`state_unsafe`).
 */
export function agyPromptBlocks(content: ContentBlock[], attachments: string | (() => string)): Array<{ type: "text"; text: string }> {
  for (const block of content) {
    if (block.type !== "text" && block.type !== "image") throw new CoreError("invalid_input", `Antigravity takes text and images, not ${block.type}`)
  }
  const parts: string[] = []
  let dir: string | undefined
  for (const block of content) {
    if (block.type === "text") { parts.push(block.text); continue }
    if (block.type !== "image") continue
    dir ??= typeof attachments === "function" ? attachments() : attachmentsFolder(attachments)
    const folder = dir
    const path = join(folder, `${randomUUID()}.${IMAGE_EXTENSIONS[block.mimeType] ?? "bin"}`)
    writePrivateFile(path, Buffer.from(block.data, "base64"), () => assertRealDirectory(folder))
    parts.push(`[Attached image: ${path}]`)
  }
  const text = parts.join("\n\n")
  if (!text.trim()) throw new CoreError("invalid_input", "Antigravity prompt is empty")
  return [{ type: "text", text }]
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
