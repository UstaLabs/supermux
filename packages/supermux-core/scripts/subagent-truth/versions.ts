/**
 * CLI versions and the `--if-changed` state: the version of each agent CLI that last passed the
 * truth table, so a scheduled run only drives the CLIs that changed since.
 */
import { execFile } from "node:child_process"
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs"
import { homedir } from "node:os"
import { dirname, join } from "node:path"
import type { AgentId } from "./matrix.js"

export type VersionState = { version: 1; agents: Partial<Record<AgentId, { version: string; passedAt: string }>> }

export function defaultVersionsFile(env: NodeJS.ProcessEnv = process.env): string {
  const cache = env.XDG_CACHE_HOME || join(homedir(), ".cache")
  return join(cache, "supermux-core", "truth-versions.json")
}

export function readVersionState(path: string): VersionState {
  try {
    const parsed = JSON.parse(readFileSync(path, "utf8")) as Partial<VersionState>
    if (parsed && parsed.version === 1 && parsed.agents && typeof parsed.agents === "object") return { version: 1, agents: parsed.agents }
  } catch { /* missing or unreadable: nothing passed yet */ }
  return { version: 1, agents: {} }
}

export function writeVersionState(path: string, state: VersionState): void {
  mkdirSync(dirname(path), { recursive: true })
  const temp = `${path}.${process.pid}.tmp`
  writeFileSync(temp, JSON.stringify(state, null, 2) + "\n", "utf8")
  renameSync(temp, path)
}

/** Record a passing run of `agent` at `version`. */
export function recordPass(state: VersionState, agent: AgentId, version: string, at = new Date()): VersionState {
  return { version: 1, agents: { ...state.agents, [agent]: { version, passedAt: at.toISOString() } } }
}

/** Why an agent is skipped under `--if-changed`, or undefined when it must run. */
export function skipReason(state: VersionState, agent: AgentId, version: string | undefined): string | undefined {
  if (!version) return undefined // unknown version: run it
  const last = state.agents[agent]
  if (last && last.version === version) return `unchanged since its pass on ${last.passedAt} (${version})`
  return undefined
}

/** `<command> --version`, first non-empty line; undefined when the CLI is missing or fails. */
export function cliVersion(command: string, timeoutMs = 20_000): Promise<string | undefined> {
  return new Promise(resolve => {
    try {
      execFile(command, ["--version"], { timeout: timeoutMs, encoding: "utf8" }, (error, stdout) => {
        if (error) return resolve(undefined)
        resolve(stdout.split("\n").map(line => line.trim()).find(Boolean))
      })
    } catch { resolve(undefined) }
  })
}
