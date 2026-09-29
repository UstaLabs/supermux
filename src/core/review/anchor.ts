// src/core/review/anchor.ts
import { existsSync } from "fs"
import { readFile } from "fs/promises"
import { join } from "path"
import { gitAsync } from "../git/exec"

const WINDOW = 20

/** Re-locate a comment on the CURRENT file. `repoAbsDir` is the absolute repo
 *  root the comment's `path` is relative to. */
export async function reanchor(
  repoAbsDir: string,
  c: { path: string; anchorLine: number; anchorContext: string; headBlobSha?: string },
): Promise<{ currentLine: number | null; outdated: boolean }> {
  const filePath = join(repoAbsDir, c.path)
  if (!existsSync(filePath)) return { currentLine: null, outdated: true }

  // Blob short-circuit: file byte-identical to when the comment was made.
  if (c.headBlobSha) {
    try {
      const sha = await gitAsync(repoAbsDir, ["hash-object", "--", c.path])
      if (sha === c.headBlobSha) return { currentLine: c.anchorLine, outdated: false }
    } catch { /* not in git / unreadable → fall through to text search */ }
  }

  // Text search: exact match of the stored line, nearest to the original position first.
  let lines: string[]
  try { lines = (await readFile(filePath, "utf-8")).split("\n") } catch { return { currentLine: null, outdated: true } }
  const origIdx = c.anchorLine - 1
  const lo = Math.max(0, origIdx - WINDOW)
  const hi = Math.min(lines.length - 1, origIdx + WINDOW)
  let best: number | null = null
  let bestDist = Infinity
  for (let i = lo; i <= hi; i++) {
    if (lines[i] === c.anchorContext) {
      const d = Math.abs(i - origIdx)
      if (d < bestDist) { best = i + 1; bestDist = d }
    }
  }
  return best !== null ? { currentLine: best, outdated: false } : { currentLine: null, outdated: true }
}
