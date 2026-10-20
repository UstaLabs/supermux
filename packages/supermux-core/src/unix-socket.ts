import { chmodSync, existsSync, lstatSync, mkdirSync } from "node:fs"
import { tmpdir } from "node:os"
import { dirname, isAbsolute, join } from "node:path"
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

/**
 * Where a socket whose own place is `inside` listens, best first (the keepers' sockets and the MCP host's): `inside`
 * when it fits sun_path (Node can neither bind nor connect a longer one); otherwise `<base>/<prefix>-<uid>/<name>` for
 * each base that keeps it within sun_path: the OS temp dir, `/tmp`, then `$XDG_RUNTIME_DIR` (when set, absolute and
 * present) last, since it is deleted at logout without lingering: a detached process would lose the socket it is
 * reached on. The per-user directory is made private before use (privateSocketPath), which keeps other users out of
 * the shared temp dir. Empty when no candidate fits.
 */
export function socketCandidates(inside: string, prefix: string, name: string, env: NodeJS.ProcessEnv = process.env, tmp: string = tmpdir()): string[] {
  if (Buffer.byteLength(inside) <= SUN_PATH_MAX) return [inside]
  const user = typeof process.getuid === "function" ? `-${process.getuid()}` : ""
  const runtime = env.XDG_RUNTIME_DIR
  const bases = [tmp, "/tmp", ...(runtime && isAbsolute(runtime) && existsSync(runtime) ? [runtime] : [])].filter(base => isAbsolute(base))
  return [...new Set(bases.map(base => join(base, `${prefix}${user}`, name)))]
    .filter(path => Buffer.byteLength(path) <= SUN_PATH_MAX)
}

/**
 * The socket a new listener takes among `candidates` (socketCandidates): `inside` as it is (its directory is the
 * caller's to check), else the first whose directory this user can make private. Throws the first failure when none.
 */
export function privateSocketPath(candidates: string[], inside: string): string {
  let failure: unknown
  for (const path of candidates) {
    if (path === inside) return path
    try { privateDirectory(dirname(path)); return path } catch (error) { failure ??= error }
  }
  throw failure
}
