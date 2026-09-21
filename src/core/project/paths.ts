import { posix } from "path"

/**
 * One normalization for registration AND lookup (spec "Resolution"): lexical only —
 * separators, dot segments, trailing slash. No realpath, no case folding: reads must
 * not touch the filesystem, and archived paths may no longer exist.
 */
export function normalizeLocationPath(path: string): string | undefined {
  if (!path || !path.startsWith("/")) return undefined
  const n = posix.normalize(path)
  return n.length > 1 ? n.replace(/\/+$/, "") : n
}

/** repo_root ?? workdir, normalized. A managed worktree with no recorded repo_root stays unresolved. */
export function effectiveLocation(
  w: { workdir: string; repo_root?: string | null },
  managedWorktreesRoot?: string,
): string | undefined {
  if (w.repo_root) return normalizeLocationPath(w.repo_root)
  const p = normalizeLocationPath(w.workdir)
  if (!p) return undefined
  if (managedWorktreesRoot && (
    managedWorktreesRoot === "/" || p === managedWorktreesRoot || p.startsWith(managedWorktreesRoot + "/")
  )) return undefined
  return p
}

/** TS port of Kotlin `formatWorkdir` (apps/shared/.../session/SessionGrouping.kt) — the default project name. */
export function pathLabel(path: string, home: string): string {
  if (home && path === home) return "~"
  const segments = path.split("/").filter(Boolean)
  if (segments.length <= 1) return path
  const leaf = segments[segments.length - 1]!
  const parent = segments[segments.length - 2]!
  const parentPath = "/" + segments.slice(0, -1).join("/")
  if (home && parentPath === home) return `~/${leaf}`
  const base = `${parent}/${leaf}`
  return segments.length > 2 ? `…/${base}` : base
}
