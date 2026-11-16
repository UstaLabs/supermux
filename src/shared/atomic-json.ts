import { readFileSync, writeFileSync, renameSync, chmodSync } from "fs"
import { files } from "@supermux/platform"

export function readJsonOr<T>(path: string, fallback: T): T {
  try {
    return JSON.parse(readFileSync(path, "utf8")) as T
  } catch {
    return fallback
  }
}

/**
 * Writes [value] to [path] through a private temp file beside it, renamed into place. A missing path throws before
 * anything is written: an untyped caller (a script, a `bun -e`) once passed undefined, and the temp file landed in the
 * working directory as `undefined.tmp.<pid>` (#157) while the rename failed.
 */
export function writeJsonAtomic(path: string, value: unknown): void {
  requirePath(path, "writeJsonAtomic")
  const tmp = `${path}.tmp.${process.pid}`
  writeFileSync(tmp, JSON.stringify(value, null, 2))
  files.makePrivate(tmp)
  renameSync(tmp, path)
}

/** Throws unless [path] is a non-empty string: what a file store needs before it reads or writes anything. */
export function requirePath(path: unknown, who: string): asserts path is string {
  if (typeof path !== "string" || path.length === 0) {
    throw new TypeError(`${who}: a file path is required, got ${path === "" ? "an empty string" : String(path)}`)
  }
}
