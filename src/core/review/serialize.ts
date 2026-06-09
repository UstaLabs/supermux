// src/core/review/serialize.ts
import type { Comment } from "./store"

function esc(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;")
}

/** Serialize open review comments into ONE agent turn, grouped by file. */
export function serializeReview(comments: Comment[]): string {
  if (!comments.length) return ""
  const byFile = new Map<string, Comment[]>()
  for (const c of comments) {
    const key = c.repo ? `${c.repo}/${c.path}` : c.path
    ;(byFile.get(key) ?? byFile.set(key, []).get(key)!).push(c)
  }
  const blocks: string[] = []
  for (const [path, cs] of byFile) {
    const items = cs.map((c) => {
      const where = c.rangeStart && c.rangeEnd ? `lines="${c.rangeStart}-${c.rangeEnd}"` : `line="${c.anchorLine}"`
      return `  <comment id="${c.id}" side="${c.side}" ${where}>\n    <code>${esc(c.anchorContext)}</code>\n    ${esc(c.body)}\n  </comment>`
    }).join("\n")
    blocks.push(`<file path="${esc(path)}">\n${items}\n</file>`)
  }
  return `<code-review>\nAddress the following review comments. Make minimal, additive commits. After fixing each, restate its id and what you changed.\n\n${blocks.join("\n")}\n</code-review>`
}
