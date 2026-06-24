import type { GitLiteStatus } from "@/stores/gitStatus"

export interface GitBadge { text: string; title: string; tone: "muted" | "active" }

export function gitBadge(git: GitLiteStatus | undefined): GitBadge | null {
  if (!git) return null
  const ref = git.mode === "base" ? (git.compareRef || "base") : "origin"
  if (git.unpublished) return { text: "unpublished", title: "Not published", tone: "muted" }

  const parts: string[] = []
  if (git.ahead) parts.push(`↑${git.ahead}`)
  if (git.behind) parts.push(`↓${git.behind}`)
  if (git.dirty) parts.push(`·${git.dirty}`)
  if (parts.length === 0) return { text: "✓ in sync", title: `In sync with ${ref}`, tone: "muted" }

  const ab: string[] = []
  if (git.ahead) ab.push(`${git.ahead} ahead`)
  if (git.behind) ab.push(`${git.behind} behind`)
  const titleBits: string[] = []
  if (ab.length) titleBits.push(`${ab.join(" / ")} ${ref}`)
  if (git.dirty) titleBits.push(`${git.dirty} uncommitted`)
  return { text: parts.join(" "), title: titleBits.join(" · "), tone: "active" }
}
