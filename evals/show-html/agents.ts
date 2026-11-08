// How each agent CLI runs one eval prompt, non-interactively, in a throwaway folder, with only the stub server attached.
import { mkdirSync, writeFileSync } from "node:fs"
import { join } from "node:path"

export interface Usage { inputTokens: number; outputTokens: number; cachedTokens: number; costUsd?: number; turns?: number }
export interface Invocation { cmd: string[]; env: Record<string, string>; parse(stdout: string): { usage: Usage; finalText: string; refusedTools: string[] } }
export interface RunSpec { dir: string; prompt: string; model: string; instructions: string; serverEnv: Record<string, string>; home?: string }

export const SERVER = join(import.meta.dir, "server.ts")
const BUN = process.execPath

export type AgentKind = "claude" | "codex" | "opencode" | "cursor" | "grok" | "antigravity"

export function invocation(kind: AgentKind, s: RunSpec): Invocation {
  switch (kind) {
    case "claude": return claude(s)
    case "codex": return codex(s)
    case "opencode": return opencode(s)
    case "cursor": return cursor(s)
    case "grok": return grok(s)
    case "antigravity": return antigravity(s)
  }
}

/** The JSON events a CLI prints, one per line; whatever isn't JSON is skipped. */
function events(stdout: string): any[] {
  return stdout.split("\n").filter((l) => l.startsWith("{")).flatMap((l) => { try { return [JSON.parse(l)] } catch { return [] } })
}

/** Token counts wherever a CLI reports them: summed over every object that looks like a usage record. */
function sumUsage(evs: any[]): Usage {
  const u = zero()
  const visit = (o: any) => {
    if (!o || typeof o !== "object") return
    const input = o.input_tokens ?? o.inputTokens ?? o.prompt_tokens ?? (typeof o.input === "number" ? o.input : undefined)
    const output = o.output_tokens ?? o.outputTokens ?? o.completion_tokens ?? (typeof o.output === "number" ? o.output : undefined)
    if (typeof input === "number" && typeof output === "number") {
      u.inputTokens += input
      u.outputTokens += output
      u.cachedTokens += o.cache_read_input_tokens ?? o.cached_input_tokens ?? o.cacheReadTokens ?? o.cache?.read ?? 0
      return
    }
    for (const v of Object.values(o)) visit(v)
  }
  evs.forEach(visit)
  return u
}

const lastText = (evs: any[]): string => {
  for (let i = evs.length - 1; i >= 0; i--) {
    const e = evs[i]
    const t = typeof e?.result === "string" ? e.result : typeof e?.text === "string" ? e.text : typeof e?.part?.text === "string" ? e.part.text
      : typeof e?.message?.content === "string" ? e.message.content : undefined
    if (t) return t
  }
  return ""
}

function opencode(s: RunSpec): Invocation {
  writeFileSync(join(s.dir, "AGENTS.md"), s.instructions)
  writeFileSync(join(s.dir, "opencode.json"), JSON.stringify({
    $schema: "https://opencode.ai/config.json",
    mcp: { supermux: { type: "local", command: [BUN, SERVER], environment: s.serverEnv, enabled: true } },
    permission: { edit: "allow", bash: "deny", webfetch: "deny" },
  }))
  return {
    cmd: ["opencode", "run", "--model", s.model, "--format", "json", "--dir", s.dir, s.prompt],
    env: {},
    parse(stdout) {
      const evs = events(stdout)
      const usage = sumUsage(evs.filter((e) => /step.?finish/i.test(String(e.type ?? e.part?.type))))
      usage.costUsd = evs.reduce((n, e) => n + (Number(e.part?.cost ?? e.cost) || 0), 0)
      return { usage, finalText: lastText(evs.filter((e) => e.part?.type === "text" || e.type === "text")), refusedTools: [] }
    },
  }
}

function cursor(s: RunSpec): Invocation {
  writeFileSync(join(s.dir, "AGENTS.md"), s.instructions)
  mkdirSync(join(s.dir, ".cursor"), { recursive: true })
  writeFileSync(join(s.dir, ".cursor", "mcp.json"), JSON.stringify({ mcpServers: { supermux: { command: BUN, args: [SERVER], env: s.serverEnv } } }))
  return {
    cmd: ["cursor-agent", "-p", "--output-format", "json", "--model", s.model, "--force", "--approve-mcps", "--workspace", s.dir, s.prompt],
    env: {},
    parse(stdout) {
      const evs = events(stdout)
      return { usage: sumUsage(evs), finalText: lastText(evs), refusedTools: [] }
    },
  }
}

function grok(s: RunSpec): Invocation {
  writeFileSync(join(s.dir, "AGENTS.md"), s.instructions)
  mkdirSync(join(s.dir, ".grok"), { recursive: true })
  const env = Object.entries(s.serverEnv).map(([k, v]) => `${k} = ${JSON.stringify(v)}`).join(", ")
  writeFileSync(join(s.dir, ".grok", "config.toml"),
    `[mcp_servers.supermux]\ncommand = ${JSON.stringify(BUN)}\nargs = [${JSON.stringify(SERVER)}]\nenv = { ${env} }\n`)
  return {
    cmd: ["grok", "-p", s.prompt, "--cwd", s.dir, "-m", s.model, "--always-approve", "--deny", "Bash", "--output-format", "json"],
    env: {},
    parse(stdout) {
      const evs = events(stdout)
      return { usage: sumUsage(evs), finalText: lastText(evs), refusedTools: [] }
    },
  }
}

function antigravity(s: RunSpec): Invocation {
  writeFileSync(join(s.dir, "AGENTS.md"), s.instructions)
  writeFileSync(join(s.dir, "GEMINI.md"), s.instructions)
  const settings = { mcpServers: { supermux: { command: BUN, args: [SERVER], env: s.serverEnv } } }
  return {
    cmd: ["agy", "-p", s.prompt, "--model", s.model, "--output-format", "json", "--dangerously-skip-permissions",
      "--settings", JSON.stringify(settings)],
    env: {},
    parse(stdout) {
      const evs = events(stdout)
      return { usage: sumUsage(evs), finalText: lastText(evs), refusedTools: [] }
    },
  }
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
