// platform-ok: normalizes a path by ITS spelling (a Windows or a POSIX path), on any host
import { posix, win32 } from "path"
import { paths } from "@supermux/platform"

/** A Windows broker's absolute path: a drive and its root (`C:\`, `C:/`) or a share (`\\server\share`). */
const WINDOWS_ABSOLUTE = /^(?:[A-Za-z]:[\\/]|\\\\[^\\/]+\\[^\\/]+)/

/**
 * One normalization for registration AND lookup (spec "Resolution"): lexical only —
 * separators, dot segments, trailing slash. No realpath, no case folding: reads must
 * not touch the filesystem, and archived paths may no longer exist.
 *
 * A Windows path normalizes to `\` with an upper-case drive letter, the one case fold:
 * git prints `C:/x`, a shell may say `c:\x`, and both are the same folder.
 */
export function normalizeLocationPath(path: string): string | undefined {
  if (!path) return undefined
  if (WINDOWS_ABSOLUTE.test(path)) {
    const n = win32.normalize(path)
    const trimmed = n.length > win32.parse(n).root.length ? n.replace(/\\+$/, "") : n
    return trimmed.replace(/^[a-z]:/, (drive) => drive.toUpperCase())
  }
  if (!path.startsWith("/")) return undefined // platform-ok: a POSIX path, by its own spelling (a Windows one was handled above)
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
  if (!managedWorktreesRoot) return false
  // The root's own kind of path decides (a registry may hold a Windows broker's paths): relative() of that flavour,
  // which handles a root ("/", "C:\") and a Windows folder's letter case.
  return paths.isInside(p, managedWorktreesRoot, WINDOWS_ABSOLUTE.test(managedWorktreesRoot) ? win32 : posix)
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

/**
 * The default project name: the path's last segment ("/home/u/projects/app" → "app",
 * "D:\Projects\app" → "app"). The home dir itself is "~"; a root ("/", "D:\") stays itself.
 */
export function pathLabel(path: string, home: string): string {
  if (home && path === home) return "~"
  if (WINDOWS_ABSOLUTE.test(path)) return win32.basename(path) || path
  const segments = path.split("/").filter(Boolean) // platform-ok: a POSIX path, by its own spelling (a Windows one was handled above)
  return segments[segments.length - 1] ?? path
}
