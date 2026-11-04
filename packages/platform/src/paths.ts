// The host's own paths: what the runtime's node:path does not answer in one call.
import * as nodePath from "node:path"

/** A node:path flavour: this host's by default; `posix` or `win32` to decide for another (tests). */
export type PathFlavor = Pick<typeof nodePath, "relative" | "isAbsolute" | "sep">

/**
 * Whether [path] is [root] or inside it, on the host's rules: relative() knows the separators, the letter case
 * (Windows ignores it) and the drives. A path on another drive is not inside: relative() hands it back absolute,
 * which a `startsWith(root + "/")` copy, or a check for `..` alone, took for "under the root".
 */
export function isInside(path: string, root: string, flavor: PathFlavor = nodePath): boolean {
  const rel = flavor.relative(root, path)
  if (rel === "") return true
  if (flavor.isAbsolute(rel)) return false
  return rel !== ".." && !rel.startsWith(`..${flavor.sep}`) && !rel.startsWith("../")
}
