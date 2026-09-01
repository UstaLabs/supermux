import { existsSync, readFileSync } from "fs"
import { join } from "path"
import type { DiffEntry } from "../editor/fs-service"
import type { RepoDiff } from "../editor/workdir-diff"
import type { AnchorStatus, NewWalkthroughStep } from "./store"

export interface ToolStepInput {
  title: string
  body: string
  file?: string
  repo?: string
  lines?: string
}

export function parseLines(spec?: string): { rangeStart?: number; rangeEnd?: number; anchorLine?: number } {
  if (!spec || !spec.trim()) return {}
  const m = spec.trim().match(/^(\d+)(?:-(\d+))?$/)
  if (!m) throw new Error(`invalid lines "${spec}": use "42" or "12-40"`)
  const a = Number(m[1])
  const b = m[2] != null ? Number(m[2]) : a
  const rangeStart = Math.min(a, b)
  const rangeEnd = Math.max(a, b)
  return { rangeStart, rangeEnd, anchorLine: rangeStart }
}

/** New-side line numbers covered by a unified diff (context + added). */
export function newSideLines(diff: string): Set<number> {
  const out = new Set<number>()
  let newLine = 0
  let inHunk = false
  for (const raw of diff.split("\n")) {
    const hunk = raw.match(/^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/)
    if (hunk) {
      newLine = Number(hunk[1])
      inHunk = true
      continue
    }
    if (!inHunk) continue
    if (raw.startsWith("\\") || raw.startsWith("diff ") || raw.startsWith("index ") || raw.startsWith("---") || raw.startsWith("+++")) {
      continue
    }
    if (raw.startsWith("-")) continue
    if (raw.startsWith("+") || raw.startsWith(" ") || raw === "") {
      out.add(newLine)
      newLine++
    }
  }
  return out
}

function findFile(repos: RepoDiff[], path: string, repo?: string): { repo: string; file: DiffEntry } | "ambiguous" | undefined {
  const matches: Array<{ repo: string; file: DiffEntry }> = []
  for (const r of repos) {
    if (repo != null && repo !== "" && r.repo !== repo) continue
    if (repo === "" && r.repo !== "") continue
    const file = r.files.find((f) => f.path === path)
    if (file) matches.push({ repo: r.repo, file })
  }
  if (matches.length === 0 && repo == null) {
    for (const r of repos) {
      const file = r.files.find((f) => f.path === path)
      if (file) matches.push({ repo: r.repo, file })
    }
  }
  if (matches.length === 0) return undefined
  if (matches.length > 1 && repo == null) return "ambiguous"
  return matches[0]
}

function rangeInDiff(lines: Set<number>, start?: number, end?: number): boolean {
  if (start == null) return lines.size > 0
  const hi = end ?? start
  for (let i = start; i <= hi; i++) if (lines.has(i)) return true
  return false
}

function readAnchorContext(workdir: string, repo: string | undefined, path: string, line: number): string | undefined {
  const abs = repo ? join(workdir, repo, path) : join(workdir, path)
  if (!existsSync(abs)) return undefined
  try {
    const rows = readFileSync(abs, "utf-8").split("\n")
    return rows[line - 1]
  } catch {
    return undefined
  }
}

export function authorSteps(
  workdir: string,
  repos: RepoDiff[],
  inputs: ToolStepInput[],
): { steps: NewWalkthroughStep[]; results: Array<{ index: number; title: string; status: AnchorStatus; error?: string }> } {
  const steps: NewWalkthroughStep[] = []
  const results: Array<{ index: number; title: string; status: AnchorStatus; error?: string }> = []
  for (let i = 0; i < inputs.length; i++) {
    const input = inputs[i]!
    const title = input.title
    const bodyMd = input.body
    if (!input.file) {
      steps.push({ title, bodyMd, anchorStatus: "ok" })
      results.push({ index: i, title, status: "ok" })
      continue
    }
    const loc = findFile(repos, input.file, input.repo)
    if (loc === "ambiguous") {
      throw new Error(`file "${input.file}" is ambiguous across repos; pass repo`)
    }
    const parsed = parseLines(input.lines)
    let status: AnchorStatus = "ok"
    let error: string | undefined
    if (!loc) {
      status = "not_in_diff"
      error = `file "${input.file}" is not in the diff`
    } else if (parsed.anchorLine != null && !rangeInDiff(newSideLines(loc.file.diff), parsed.rangeStart, parsed.rangeEnd)) {
      status = "not_in_diff"
      error = `lines ${input.lines} are not in the diff for ${input.file}`
    }
    const repo = loc?.repo ?? input.repo ?? ""
    const anchorLine = parsed.anchorLine
    const anchorContext = anchorLine != null ? readAnchorContext(workdir, repo || undefined, input.file, anchorLine) : undefined
    steps.push({
      title,
      bodyMd,
      repo: repo || undefined,
      path: input.file,
      side: "RIGHT",
      anchorLine,
      rangeStart: parsed.rangeStart,
      rangeEnd: parsed.rangeEnd,
      anchorContext,
      anchorStatus: status,
    })
    results.push({ index: i, title, status, error })
  }
  return { steps, results }
}

export function formatInstantComment(opts: {
  id: string
  repo?: string
  path: string
  line: number
  body: string
  stepN?: number
  stepTitle?: string
}): string {
  const loc = `${opts.repo ? opts.repo + "/" : ""}${opts.path}:${opts.line}`
  const step = opts.stepN != null && opts.stepTitle != null
    ? ` (step ${opts.stepN} "${opts.stepTitle}")`
    : ""
  return (
    `💬 Walkthrough comment ${opts.id} on ${loc}${step}:\n` +
    `"${opts.body}"\n` +
    `Reply in-thread with the reply_comment tool (comment_id=${opts.id}), or edit the code — the walkthrough re-anchors automatically. resolve:true marks it resolved.`
  )
}

export function matchingStep(
  steps: Array<{ path?: string | null; repo?: string | null; title: string; ord: number; rangeStart?: number | null; rangeEnd?: number | null; anchorLine?: number | null }>,
  path: string,
  line: number,
  repo?: string,
): { n: number; title: string } | undefined {
  for (const s of steps) {
    if (!s.path || s.path !== path) continue
    if (repo && s.repo && s.repo !== repo) continue
    const lo = s.rangeStart ?? s.anchorLine
    const hi = s.rangeEnd ?? s.anchorLine
    if (lo == null || hi == null || (line >= lo && line <= hi)) {
      return { n: s.ord + 1, title: s.title }
    }
  }
  return undefined
}

export function toWalkthroughDto(wt: {
  id: string; title: string; baseSpec: string; revision: number; createdAt: string
  steps: Array<{
    id: string; ord: number; title: string; bodyMd: string
    repo?: string | null; path?: string | null; side: string
    anchorLine?: number | null; rangeStart?: number | null; rangeEnd?: number | null
    anchorContext?: string | null; anchorStatus: string
    currentLine?: number | null; outdated?: boolean
  }>
}) {
  return {
    id: wt.id,
    title: wt.title,
    baseSpec: wt.baseSpec,
    revision: wt.revision,
    createdAt: wt.createdAt,
    steps: wt.steps.map((s) => ({
      id: s.id,
      ord: s.ord,
      title: s.title,
      bodyMd: s.bodyMd,
      repo: s.repo ?? null,
      path: s.path ?? null,
      side: s.side,
      anchorLine: s.anchorLine ?? null,
      rangeStart: s.rangeStart ?? null,
      rangeEnd: s.rangeEnd ?? null,
      anchorContext: s.anchorContext ?? null,
      anchorStatus: s.anchorStatus,
      currentLine: s.currentLine ?? null,
      outdated: s.outdated ?? false,
    })),
  }
}
