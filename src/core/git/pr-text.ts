// src/core/git/pr-text.ts
// A pull request's title and description (#72), written by the agent from the branch's commits and files, for the
// "Create Pull Request" form to start from. Without an agent, or when it fails, the commits' own subjects are used.

import { gitAsync } from "./exec"

export interface PrInput {
  base: string
  branch: string
  commits: Array<{ subject: string; body: string }>
  files: Array<{ path: string; added: number; removed: number }>
  /** The change itself (the base...branch diff), cut at MAX_DIFF: the description is read from it. */
  diff: string
}
export interface PrText { title: string; body: string; by: "agent" | "commits" }
/** Asks the agent (agent-rpc "pr_text"); answers { title, body }. */
export type PrTextWriter = (input: PrInput) => Promise<unknown>

/** At most this many commits and files go to the agent: enough to describe a branch, small enough to be quick. */
const MAX_COMMITS = 60, MAX_FILES = 200, MAX_BODY = 2_000, MAX_DIFF = 40_000

/** The branch's commits not on the base, oldest first, and the files they change. */
export async function prInput(dir: string, base: string, branch: string): Promise<PrInput> {
  const range = `${base}..${branch}`
  const log = await gitAsync(dir, ["log", "--reverse", "--format=%s%x1f%b%x1e", `--max-count=${MAX_COMMITS}`, range], { trim: false }).catch(() => "")
  const commits = log.split("\x1e").map((r) => r.replace(/^\n/, "")).filter((r) => r.trim()).map((r) => {
    const [subject, body] = r.split("\x1f")
    return { subject: (subject ?? "").trim(), body: (body ?? "").trim().slice(0, MAX_BODY) }
  })
  const stat = await gitAsync(dir, ["diff", "--numstat", "--no-renames", `${base}...${branch}`]).catch(() => "")
  const files = stat.split("\n").filter(Boolean).slice(0, MAX_FILES).map((l) => {
    const [a, r, ...p] = l.split("\t")
    return { path: p.join("\t"), added: Number(a) || 0, removed: Number(r) || 0 }
  })
  const patch = await gitAsync(dir, ["diff", "--no-color", "--no-renames", "-U2", `${base}...${branch}`], { trim: false }).catch(() => "")
  const diff = patch.length > MAX_DIFF ? patch.slice(0, MAX_DIFF) + "\n… (diff cut here)" : patch
  return { base, branch, commits, files, diff }
}

/** What an agent sometimes appends on its own: "🤖 Generated with …", "Co-Authored-By: …" lines. Not the user's PR. */
const SIGNATURE = /^\s*(?:🤖\s*)?(?:Generated (?:with|by)\b.*|Co-Authored-By:.*)$/gim
function clean(body: string): string {
  return body.replace(SIGNATURE, "").replace(/\n{3,}/g, "\n\n").trim()
}

function fromCommits(i: PrInput): PrText {
  const subjects = i.commits.map((c) => c.subject).filter(Boolean)
  return { title: subjects[0] || i.branch, body: subjects.map((s) => `- ${s}`).join("\n"), by: "commits" }
}

/** The agent's title and body when it gives a usable one; the commits' otherwise. Never throws. */
export async function writePrText(input: PrInput, write: PrTextWriter | undefined): Promise<PrText> {
  if (!write || !input.commits.length) return fromCommits(input)
  try {
    const r = (await write(input)) as { title?: unknown; body?: unknown } | null
    const title = typeof r?.title === "string" ? r.title.replace(/\s+/g, " ").trim().slice(0, 120) : ""
    if (!title) return fromCommits(input)
    return { title, body: typeof r?.body === "string" ? clean(r.body) : "", by: "agent" }
  } catch {
    return fromCommits(input)
  }
}
