// src/core/forge/cli-import.ts
import { execFileSync } from "child_process"
import type { ForgeKind } from "./types"

export type Runner = (cmd: string, args: string[]) => string
const defaultRunner: Runner = (cmd, args) => execFileSync(cmd, args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim()

const CLI: Record<ForgeKind, string> = { github: "gh", gitlab: "glab" }

export interface CliStatus { available: boolean; login?: string }

function statusFor(kind: ForgeKind, run: Runner): CliStatus {
  try {
    const out = kind === "github" ? run("gh", ["auth", "status"]) : run("glab", ["auth", "status"])
    const m = out.match(/account\s+(\S+)/i) ?? out.match(/Logged in.*?as\s+(\S+)/i)
    return { available: true, login: m?.[1] }
  } catch { return { available: false } }
}

export function detectForgeClis(run: Runner = defaultRunner): { github: CliStatus; gitlab: CliStatus } {
  return { github: statusFor("github", run), gitlab: statusFor("gitlab", run) }
}

/** Read the live token from an authenticated CLI. Throws if unavailable. */
export function importCliToken(kind: ForgeKind, run: Runner = defaultRunner): string {
  const tok = kind === "github" ? run("gh", ["auth", "token"]) : run("glab", ["auth", "token"])
  if (!tok) throw new Error(`${CLI[kind]} returned no token`)
  return tok.trim()
}
