// src/core/forge/cli-import.ts
import { runAsync } from "../git/exec"
import { runSyncOrThrow } from "../git/exec-sync"
import type { ForgeKind } from "./types"

export type Runner = (cmd: string, args: string[]) => string
export type StatusRunner = (cmd: string, args: string[]) => string | Promise<string>

const defaultRunner: Runner = (cmd, args) => runSyncOrThrow(cmd, args, { timeoutMs: 30_000 })
// What the CLI said, stdout and stderr: older gh and glab print their auth status on stderr.
const defaultStatusRunner: StatusRunner = async (cmd, args) => {
  const r = await runAsync(cmd, args, { cwd: process.cwd(), timeoutMs: 2_000 })
  if (r.code !== 0) throw new Error(r.output.trim() || `${cmd} exited with ${r.code}`)
  return r.output.trim()
}

const CLI: Record<ForgeKind, string> = { github: "gh", gitlab: "glab" }

export interface CliStatus { available: boolean; login?: string }

async function statusFor(kind: ForgeKind, run: StatusRunner): Promise<CliStatus> {
  try {
    const out = await (kind === "github" ? run("gh", ["auth", "status"]) : run("glab", ["auth", "status"]))
    const m = out.match(/account\s+(\S+)/i) ?? out.match(/Logged in.*?as\s+(\S+)/i)
    return { available: true, login: m?.[1] }
  } catch { return { available: false } }
}

export async function detectForgeClis(run: StatusRunner = defaultStatusRunner): Promise<{ github: CliStatus; gitlab: CliStatus }> {
  const [github, gitlab] = await Promise.all([
    statusFor("github", run),
    statusFor("gitlab", run),
  ])
  return { github, gitlab }
}

/** Read the live token from an authenticated CLI. Throws if unavailable. */
export function importCliToken(kind: ForgeKind, run: Runner = defaultRunner): string {
  const tok = kind === "github" ? run("gh", ["auth", "token"]) : run("glab", ["auth", "token"])
  if (!tok) throw new Error(`${CLI[kind]} returned no token`)
  return tok.trim()
}
