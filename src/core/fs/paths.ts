import { isAbsolute, resolve } from "path"
import { realpath } from "fs/promises"
import { FsError, toFsError } from "./errors"

/** Absolute, normalised path: no `.`/`..`, no trailing slash (except "/"). Throws FsError EINVAL. */
export function normalizeAbsPath(p: string): string {
  if (typeof p !== "string" || p.length === 0) throw new FsError("EINVAL", "empty path")
  if (p.includes("\0")) throw new FsError("EINVAL", "path contains a NUL byte")
  if (!isAbsolute(p)) throw new FsError("EINVAL", `path must be absolute: ${p}`)
  return resolve(p)
}

/** realpath(), with errors mapped to FsError. The sharing key for watchers and caches. */
export async function realKey(p: string): Promise<string> {
  try {
    return await realpath(p)
  } catch (e) {
    throw toFsError(e)
  }
}
