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

/**
 * Expands a literal leading "~" or "~/" against `home`, lexically — no fs, no
 * touching any other spelling (e.g. "/home/u/~/x" is left alone since the tilde
 * isn't at position 0). Legacy records can carry an unexpanded "~" workdir/repo_root.
 */
function expandHome(path: string, home?: string): string {
  if (!home) return path
  const h = home.replace(/\/+$/, "") || "/"
  if (path === "~") return h
  if (path.startsWith("~/")) return h + path.slice(1)
  return path
}

function isUnderManagedRoot(p: string, managedWorktreesRoot?: string): boolean {
  return !!managedWorktreesRoot && (
    managedWorktreesRoot === "/" || p === managedWorktreesRoot || p.startsWith(managedWorktreesRoot + "/")
  )
}

/**
 * repo_root ?? workdir, normalized. A managed worktree with no recorded repo_root
 * stays unresolved — and so does a recorded repo_root that is ITSELF under the
 * managed worktrees root (a session spawned inside a managed worktree can record
 * that same worktree dir as repo_root; without this it would register a junk
 * project named after the worktree). `home`, when given, expands a legacy literal
 * "~" / "~/" path before normalization — callers must pass the SAME home to every
 * call (register and lookup alike) or resolution will disagree.
 */
export function effectiveLocation(
  w: { workdir: string; repo_root?: string | null },
  managedWorktreesRoot?: string,
  home?: string,
): string | undefined {
  if (w.repo_root) {
    const p = normalizeLocationPath(expandHome(w.repo_root, home))
    if (!p) return undefined
    return isUnderManagedRoot(p, managedWorktreesRoot) ? undefined : p
  }
  const p = normalizeLocationPath(expandHome(w.workdir, home))
  if (!p) return undefined
  return isUnderManagedRoot(p, managedWorktreesRoot) ? undefined : p
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
