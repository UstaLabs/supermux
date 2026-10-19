import { chmodSync, lstatSync, mkdirSync } from "node:fs"
import { CoreError } from "./errors.js"

/** Longest usable sun_path (bytes, without the NUL): 108 on Linux, 104 on macOS/BSD. */
export const SUN_PATH_MAX = process.platform === "linux" ? 107 : 103

/** A 0700 directory owned by this user (created, or checked and tightened). Never follows a symlink. */
export function privateDirectory(path: string): void {
  mkdirSync(path, { recursive: true, mode: 0o700 })
  const info = lstatSync(path)
  if (!info.isDirectory()) throw new CoreError("invalid_options", `${path} must be a directory`)
  if (typeof process.getuid === "function" && info.uid !== process.getuid()) throw new CoreError("invalid_options", `${path} is owned by another user`)
  if ((info.mode & 0o777) !== 0o700) chmodSync(path, 0o700)
}
