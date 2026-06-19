// src/core/git/pr.ts
import { execFileSync } from "child_process"

function run(cmd: string, args: string[], cwd?: string, timeout = 60_000): { ok: boolean; out: string } {
  try { return { ok: true, out: execFileSync(cmd, args, { cwd, encoding: "utf-8", timeout, stdio: ["pipe","pipe","pipe"], env: process.env }).trim() } }
  catch (e: any) { return { ok: false, out: `${e?.stdout?.toString?.() ?? ""}${e?.stderr?.toString?.() ?? ""}`.trim() } }
}

/** `gh` on PATH AND authenticated. */
export function ghAvailable(cwd: string): boolean {
  if (!run("gh", ["--version"]).ok) return false
  return run("gh", ["auth", "status"], cwd, 10_000).ok
}

function originUrl(cwd: string): string | null {
  const r = run("git", ["-C", cwd, "remote", "get-url", "origin"])
  return r.ok && r.out ? r.out : null
}

/** Parse host/owner/repo from ssh or https origin; null if not parseable. */
function parseOrigin(url: string): { host: string; owner: string; repo: string } | null {
  let m = url.match(/^git@([^:]+):(.+?)(?:\.git)?$/)
  if (!m) m = url.match(/^https?:\/\/(?:[^@]+@)?([^/]+)\/(.+?)(?:\.git)?$/)
  if (!m) return null
  const host = m[1] ?? ""; const path = m[2] ?? ""
  const slash = path.lastIndexOf("/")
  if (slash < 0) return null
  return { host, owner: path.slice(0, slash), repo: path.slice(slash + 1) }
}

/** Browser compare/PR URL, or null if origin missing/unparseable. */
export function compareUrl(cwd: string, base: string, branch: string): string | null {
  const url = originUrl(cwd); if (!url) return null
  const p = parseOrigin(url); if (!p) return null
  if (p.host.includes("gitlab")) {
    return `https://${p.host}/${p.owner}/${p.repo}/-/merge_requests/new?merge_request%5Bsource_branch%5D=${encodeURIComponent(branch)}`
  }
  return `https://${p.host}/${p.owner}/${p.repo}/compare/${base}...${branch}?expand=1`
}

export type OpenPrResult =
  | { status: "opened"; url: string; draft: boolean }
  | { status: "gh_unavailable" }
  | { status: "error"; message: string }

/** Open a PR via gh. Branch must already be pushed with an upstream. */
export function openPullRequest(cwd: string, opts: { title: string; body: string; base: string; draft: boolean }): OpenPrResult {
  if (!ghAvailable(cwd)) return { status: "gh_unavailable" }
  const args = ["pr", "create", "--base", opts.base, "--title", opts.title, "--body", opts.body, ...(opts.draft ? ["--draft"] : [])]
  const r = run("gh", args, cwd, 60_000)
  if (r.ok) {
    const url = (r.out.match(/https?:\/\/\S+/) ?? [])[0] ?? ""
    return { status: "opened", url, draft: opts.draft }
  }
  return { status: "error", message: r.out }
}
