import { isAbsolute, relative, sep } from "node:path"

/**
 * If `p` is an absolute path that lives under `workdir`, return it as a
 * workdir-relative path with forward slashes. Otherwise return `p` unchanged.
 *
 * - Empty / non-absolute / missing workdir are no-ops.
 * - Symlinks are NOT resolved — the comparison is purely lexical, matching
 *   what the agent already sees in its own CWD.
 * - The result is forward-slash normalized so the same string displays
 *   identically on web, iOS, and Android.
 */
export function relativizePath(
  p: string,
  workdir: string | undefined | null,
): string {
  if (!workdir || !p) return p
  if (!isAbsolute(p)) return p
  const rel = relative(workdir, p)
  if (!rel || rel === ".." || rel.startsWith(".." + sep)) return p
  return rel.split(sep).join("/")
}
