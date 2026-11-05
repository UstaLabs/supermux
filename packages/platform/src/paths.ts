// The host's own paths: what the runtime's node:path does not answer in one call.
import * as nodePath from "node:path"

/** A node:path flavour: this host's by default; `posix` or `win32` to decide for another (tests). */
export type PathFlavor = Pick<typeof nodePath, "relative" | "isAbsolute" | "sep">

/** The node:path flavour of [platform]: win32's rules for Windows, posix's for every other OS. */
export function flavor(platform: NodeJS.Platform): typeof nodePath.posix {
  return platform === "win32" ? nodePath.win32 : nodePath.posix
}

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

/** [isInside], but never [root] itself: under it, at least one name down. */
export function isStrictlyInside(path: string, root: string, flavor: PathFlavor = nodePath): boolean {
  return isInside(path, root, flavor) && flavor.relative(root, path) !== ""
}

// Windows: characters a name may not hold, and the device names no file may have (with or without an extension).
const WINDOWS_RESERVED = /[<>:"|?*\u0000-\u001f]/g
const WINDOWS_DEVICE = /^(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³])(\..*)?$/i

/**
 * [name] (a session's or an Agent's, free text) as one folder name on [platform]'s file system: `/` and `\` never nest
 * it, and "." / ".." never climb out. On Windows also no reserved character, no trailing dot or space (Windows would drop
 * them) and no device name (CON, NUL, COM1...). Elsewhere a name maps to itself otherwise, so no existing folder moves.
 */
/**
 * Whether [platform]'s file system ignores letter case, so "Test" and "test" name one folder: macOS (APFS's default)
 * and Windows (NTFS). Linux's file systems keep it.
 */
export function foldsCase(platform: NodeJS.Platform = process.platform): boolean {
  return platform === "darwin" || platform === "win32"
}

export function safeFolderName(name: string, platform: NodeJS.Platform = process.platform): string {
  const flat = name.replace(/[/\\]/g, "-")
  if (platform !== "win32") return flat === "." || flat === ".." ? `_${flat}` : flat
  const clean = flat.replace(WINDOWS_RESERVED, "-").replace(/[. ]+$/, "")
  if (clean === "") return "_"
  return WINDOWS_DEVICE.test(clean) ? `_${clean}` : clean
}
