export type DiffLine = {
  type: "add" | "del" | "ctx" | "hunk" | "meta"
  content: string
}

/** Parse unified-diff (or synthetic +/- lines) for High tool diff panes. */
export function parseDiffLines(diff: string): DiffLine[] {
  if (!diff) return []
  const out: DiffLine[] = []
  let inHunk = false
  for (const line of diff.split("\n")) {
    if (line.startsWith("@@")) {
      inHunk = true
      out.push({ type: "hunk", content: line })
      continue
    }
    if (line.startsWith("---") || line.startsWith("+++")) {
      out.push({ type: "meta", content: line })
      continue
    }
    // After a hunk header, honor diff prefixes. Before any hunk, still color +/- if present
    // (codex multi-file blocks and synthesizeUnifiedDiff both include @@).
    if (line.startsWith("+") && !line.startsWith("+++")) {
      out.push({ type: "add", content: line.slice(1) })
      inHunk = true
      continue
    }
    if (line.startsWith("-") && !line.startsWith("---")) {
      out.push({ type: "del", content: line.slice(1) })
      inHunk = true
      continue
    }
    if (line.startsWith(" ") && inHunk) {
      out.push({ type: "ctx", content: line.slice(1) })
      continue
    }
    // Non-diff header lines (e.g. "update /path") as meta.
    if (!inHunk && line.trim()) {
      out.push({ type: "meta", content: line })
      continue
    }
    if (inHunk) out.push({ type: "ctx", content: line })
  }
  return out
}

export function diffStats(diff: string): { added: number; deleted: number } {
  let added = 0
  let deleted = 0
  for (const line of diff.split("\n")) {
    if (line.startsWith("+") && !line.startsWith("+++")) added++
    else if (line.startsWith("-") && !line.startsWith("---")) deleted++
  }
  return { added, deleted }
}
