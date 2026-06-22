import { execSync } from "child_process"

export interface PreflightResult {
  fatal: string[]
  warnings: string[]
}

const AGENT_CLIS = ["claude", "codex", "cursor-agent"] as const

/** Pure: given a "is this binary on PATH?" probe, decide fatals/warnings. */
export function checkPreflight(has: (bin: string) => boolean): PreflightResult {
  const fatal: string[] = []
  const warnings: string[] = []

  if (!has("tmux")) {
    fatal.push("tmux not found on PATH — supermux runs every session inside tmux. Install tmux and retry.")
  }

  const present = AGENT_CLIS.filter(has)
  if (present.length === 0) {
    fatal.push(`No agent CLI found on PATH — install at least one of: ${AGENT_CLIS.join(", ")}.`)
  } else {
    for (const cli of AGENT_CLIS) {
      if (!has(cli)) warnings.push(`Optional agent CLI '${cli}' not found on PATH — sessions using it will fail to spawn.`)
    }
  }

  return { fatal, warnings }
}

/** Real PATH probe used at boot. */
export function hasBinary(bin: string): boolean {
  try {
    // Pass env explicitly: Bun's execSync does NOT honor an in-process
    // mutation to process.env.PATH unless env is passed, and the broker
    // prepends agent-install dirs to PATH at startup (see agents/bin-dirs).
    execSync(`command -v ${bin}`, { stdio: "ignore", shell: "/bin/sh", env: process.env })
    return true
  } catch {
    return false
  }
}
