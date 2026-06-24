import type { GitLiteStatus } from "@/stores/gitStatus"

export interface GitBadge {
  text: string
  title: string
  tone: "muted" | "active"
  kind: "base" | "remote" | "unpublished" | "insync"
}

export function gitBadge(git: GitLiteStatus | undefined): GitBadge | null {
  if (!git) return null
  const ref = git.mode === "base" ? (git.compareRef || "base") : (git.compareRef.split("/")[0] || "origin")
  if (git.mode === "remote" && git.unpublished)
    return { text: "unpublished", title: "Not published", tone: "muted", kind: "unpublished" }

  const parts: string[] = []
  if (git.mode === "base") {
    if (git.ahead) parts.push(`+${git.ahead}`)
    if (git.behind) parts.push(`−${git.behind}`)
  } else {
    if (git.ahead) parts.push(`↑${git.ahead}`)
    if (git.behind) parts.push(`↓${git.behind}`)
  }
  if (git.dirty) parts.push(`·${git.dirty}`)
  if (parts.length === 0) return { text: "✓ in sync", title: `In sync with ${ref}`, tone: "muted", kind: "insync" }

  const ab: string[] = []
  if (git.ahead) ab.push(`${git.ahead} ahead`)
  if (git.behind) ab.push(`${git.behind} behind`)
  const titleBits: string[] = []
  if (ab.length) titleBits.push(`${ab.join(" / ")} ${ref}`)
  if (git.dirty) titleBits.push(`${git.dirty} uncommitted`)
  return {
    text: parts.join(" "),
    title: titleBits.join(" · "),
    tone: "active",
    kind: git.mode === "base" ? "base" : "remote",
  }
}
