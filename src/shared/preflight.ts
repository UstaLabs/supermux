import { resolveCommand } from "../core/process/launcher"

export interface PreflightResult {
  fatal: string[]
  warnings: string[]
}

const AGENT_CLIS = [
  { label: "claude", names: ["claude"] },
  { label: "codex", names: ["codex"] },
  { label: "cursor-agent", names: ["cursor-agent", "agent"] },
  { label: "opencode", names: ["opencode"] },
  { label: "grok", names: ["grok"] },
] as const

/** Pure: given a "is this binary on PATH?" probe, decide fatals/warnings. */
export function checkPreflight(has: (bin: string) => boolean, platform: NodeJS.Platform = process.platform): PreflightResult {
  const fatal: string[] = []
  const warnings: string[] = []

  if (platform !== "win32" && !has("tmux")) {
    warnings.push("tmux not found on PATH — Claude sessions and persistent terminals are disabled on this host; codex/cursor/opencode still work. Install tmux to enable them.")
  }

  const present = AGENT_CLIS.filter((cli) => cli.names.some(has))
  if (present.length === 0) {
    fatal.push(`No agent CLI found on PATH — install at least one of: ${AGENT_CLIS.map((cli) => cli.label).join(", ")}.`)
  } else {
    for (const cli of AGENT_CLIS) {
      if (!cli.names.some(has)) warnings.push(`Optional agent CLI '${cli.label}' not found on PATH — sessions using it will fail to spawn.`)
    }
  }

  return { fatal, warnings }
}

/** Real PATH probe used at boot. */
export function hasBinary(bin: string): boolean {
  return resolveCommand([bin], process.env, process.platform) !== null
}
