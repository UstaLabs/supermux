// src/core/forge/cli-import.ts
import { execFile, execFileSync } from "child_process"
import type { ForgeKind } from "./types"

export type Runner = (cmd: string, args: string[]) => string
export type StatusRunner = (cmd: string, args: string[]) => string | Promise<string>

const defaultRunner: Runner = (cmd, args) => execFileSync(cmd, args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim()
const defaultStatusRunner: StatusRunner = (cmd, args) => new Promise((resolve, reject) => {
  execFile(cmd, args, {
    encoding: "utf8",
    timeout: 2_000,
    windowsHide: true,
  }, (error, stdout) => {
    if (error) reject(error)
    else resolve(stdout.trim())
  })
})

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
