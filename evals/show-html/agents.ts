// How each agent CLI runs one eval prompt, non-interactively, in a throwaway folder, with only the stub server attached.
import { writeFileSync } from "node:fs"
import { join } from "node:path"

export interface Usage { inputTokens: number; outputTokens: number; cachedTokens: number; costUsd?: number; turns?: number }
export interface Invocation { cmd: string[]; env: Record<string, string>; parse(stdout: string): { usage: Usage; finalText: string; refusedTools: string[] } }
export interface RunSpec { dir: string; prompt: string; model: string; instructions: string; serverEnv: Record<string, string>; home?: string }

export const SERVER = join(import.meta.dir, "server.ts")
const BUN = process.execPath

export type AgentKind = "claude" | "codex"

export function invocation(kind: AgentKind, s: RunSpec): Invocation {
  return kind === "claude" ? claude(s) : codex(s)
}

const zero = (): Usage => ({ inputTokens: 0, outputTokens: 0, cachedTokens: 0 })

function claude(s: RunSpec): Invocation {
  writeFileSync(join(s.dir, ".mcp-eval.json"), JSON.stringify({
    mcpServers: { supermux: { command: BUN, args: [SERVER], env: s.serverEnv } },
  }))
  const tools = ["mcp__supermux__show_html", "mcp__supermux__preview_html", "mcp__supermux__attach", "Read", "Write", "Edit", "Glob", "Grep"]
  return {
    cmd: ["claude", "-p", s.prompt, "--output-format", "stream-json", "--verbose", "--model", s.model,
      "--mcp-config", join(s.dir, ".mcp-eval.json"), "--strict-mcp-config", "--setting-sources", "project",
      "--append-system-prompt", s.instructions, "--no-session-persistence", "--permission-mode", "dontAsk",
      "--allowedTools", tools.join(" "), "--max-turns", "8"],
    env: s.home ? { CLAUDE_CONFIG_DIR: s.home } : {},
    parse(stdout) {
      const usage = zero()
      let finalText = ""
      const refusedTools: string[] = []
      for (const line of stdout.split("\n")) {
        if (!line.startsWith("{")) continue
        let ev: any
        try { ev = JSON.parse(line) } catch { continue }
        if (ev.type === "result") {
          finalText = typeof ev.result === "string" ? ev.result : ""
          usage.inputTokens = (ev.usage?.input_tokens ?? 0) + (ev.usage?.cache_creation_input_tokens ?? 0)
          usage.cachedTokens = ev.usage?.cache_read_input_tokens ?? 0
          usage.outputTokens = ev.usage?.output_tokens ?? 0
          usage.costUsd = ev.total_cost_usd
          usage.turns = ev.num_turns
          for (const d of ev.permission_denials ?? []) refusedTools.push(String(d.tool_name))
        }
      }
      return { usage, finalText, refusedTools }
    },
  }
}

function codex(s: RunSpec): Invocation {
  writeFileSync(join(s.dir, "AGENTS.md"), s.instructions)
  const env = Object.entries(s.serverEnv).map(([k, v]) => `${k}=${JSON.stringify(v)}`).join(",")
  return {
    cmd: ["codex", "exec", "--json", "-m", s.model, "-s", "workspace-write", "--skip-git-repo-check", "--ephemeral", "-C", s.dir,
      "-c", `mcp_servers.supermux.command=${JSON.stringify(BUN)}`, "-c", `mcp_servers.supermux.args=${JSON.stringify([SERVER])}`,
      "-c", `mcp_servers.supermux.env={${env}}`, "-c", "approval_policy=\"never\"", s.prompt],
    env: s.home ? { CODEX_HOME: s.home } : {},
    parse(stdout) {
      const usage = zero()
      let finalText = ""
      let turns = 0
      for (const line of stdout.split("\n")) {
        if (!line.startsWith("{")) continue
        let ev: any
        try { ev = JSON.parse(line) } catch { continue }
        if (ev.type === "turn.completed" && ev.usage) {
          turns++
          usage.inputTokens += ev.usage.input_tokens ?? 0
          usage.cachedTokens += ev.usage.cached_input_tokens ?? 0
          usage.outputTokens += ev.usage.output_tokens ?? 0
        }
        if (ev.type === "item.completed" && ev.item?.type === "agent_message" && typeof ev.item.text === "string") finalText = ev.item.text
      }
      usage.turns = turns
      return { usage, finalText, refusedTools: [] }
    },
  }
}
