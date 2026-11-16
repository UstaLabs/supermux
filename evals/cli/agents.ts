// How each agent CLI runs one task (#156): non-interactive, in the run's workdir, WITH a shell (both arms), the real
// mux-shim as its only MCP server (pointed at the throwaway broker), and the session instructions as its system rules.
// Adapted from evals/show-html/agents.ts, which runs with no shell.
import { mkdirSync, writeFileSync } from "node:fs"
import { join } from "node:path"

export interface Usage { inputTokens: number; outputTokens: number; cachedTokens: number; costUsd?: number; turns?: number; toolCalls?: number; firstInput?: number }
export interface Invocation { cmd: string[]; env: Record<string, string>; parse(stdout: string): { usage: Usage; finalText: string } }
export interface RunSpec { dir: string; prompt: string; model: string; instructions: string; shimEnv: Record<string, string>; home?: string }
export type AgentKind = "claude" | "opencode" | "grok" | "codex" | "cursor" | "antigravity"

/** The shim agents use live; its tool list comes from the broker's register reply, so the eval broker decides the arm. */
export const SHIM = "/home/ahmet/projects/supermux/src/shim/index.ts"
const BUN = process.execPath
const zero = (): Usage => ({ inputTokens: 0, outputTokens: 0, cachedTokens: 0, toolCalls: 0 })
const events = (stdout: string): any[] => stdout.split("\n").filter((l) => l.startsWith("{")).flatMap((l) => { try { return [JSON.parse(l)] } catch { return [] } })

export function invocation(kind: AgentKind, s: RunSpec): Invocation {
  if (kind === "claude") return claude(s)
  if (kind === "grok") return grok(s)
  if (kind === "opencode") return opencode(s)
  throw new Error(`agent ${kind} is not wired for this eval yet`)
}

function claude(s: RunSpec): Invocation {
  writeFileSync(join(s.dir, "..", "mcp.json"), JSON.stringify({ mcpServers: { "mux-shim": { command: BUN, args: [SHIM], env: s.shimEnv } } }))
  const tools = ["Bash", "Read", "Write", "Edit", "Glob", "Grep", "ToolSearch", "mcp__mux-shim"]
  return {
    cmd: ["claude", "-p", s.prompt, "--output-format", "stream-json", "--verbose", "--model", s.model,
      "--mcp-config", join(s.dir, "..", "mcp.json"), "--strict-mcp-config", "--setting-sources", "project",
      "--append-system-prompt", s.instructions, "--no-session-persistence", "--permission-mode", "dontAsk",
      "--allowedTools", tools.join(" "), "--max-turns", "25"],
    env: s.home ? { CLAUDE_CONFIG_DIR: s.home } : {},
    parse: messagesStream,
  }
}

/** Claude's stream-json; Grok's streaming-messages-json has the same shape (assistant messages with usage, then a result). */
function messagesStream(stdout: string): { usage: Usage; finalText: string } {
  const usage = zero()
  let finalText = ""
  let first = true
  for (const ev of events(stdout)) {
    if (ev.type === "assistant") {
      const u = ev.message?.usage
      if (first && u) { usage.firstInput = (u.input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0) + (u.cache_read_input_tokens ?? 0); first = false }
      for (const c of ev.message?.content ?? []) if (c.type === "tool_use") usage.toolCalls!++
    }
    if (ev.type === "result") {
      finalText = typeof ev.result === "string" ? ev.result : ""
      usage.inputTokens = (ev.usage?.input_tokens ?? 0) + (ev.usage?.cache_creation_input_tokens ?? 0)
      usage.cachedTokens = ev.usage?.cache_read_input_tokens ?? 0
      usage.outputTokens = ev.usage?.output_tokens ?? 0
      usage.costUsd = ev.total_cost_usd
      usage.turns = ev.num_turns
    }
  }
  return { usage, finalText }
}

/** Grok reads rules from AGENTS.md and MCP servers from the project's .grok/config.toml. */
function grok(s: RunSpec): Invocation {
  writeFileSync(join(s.dir, "AGENTS.md"), s.instructions)
  mkdirSync(join(s.dir, ".grok"), { recursive: true })
  const env = Object.entries(s.shimEnv).map(([k, v]) => `${k} = ${JSON.stringify(v)}`).join(", ")
  writeFileSync(join(s.dir, ".grok", "config.toml"), `[mcp_servers.mux-shim]\ncommand = ${JSON.stringify(BUN)}\nargs = [${JSON.stringify(SHIM)}]\nenv = { ${env} }\n`)
  return {
    cmd: ["grok", "-p", s.prompt, "--cwd", s.dir, "-m", s.model, "--always-approve", "--output-format", "streaming-messages-json"],
    env: {},
    parse: messagesStream,
  }
}

function opencode(s: RunSpec): Invocation {
  writeFileSync(join(s.dir, "AGENTS.md"), s.instructions)
  writeFileSync(join(s.dir, "opencode.json"), JSON.stringify({
    $schema: "https://opencode.ai/config.json",
    mcp: { "mux-shim": { type: "local", command: [BUN, SHIM], environment: s.shimEnv, enabled: true } },
    permission: { edit: "allow", bash: "allow", webfetch: "deny", external_directory: "allow" },
  }))
  return {
    cmd: ["opencode", "run", "--model", s.model, "--format", "json", "--dir", s.dir, s.prompt],
    env: {},
    parse(stdout) {
      const evs = events(stdout)
      const usage = zero()
      let first = true
      for (const e of evs) {
        const t = String(e.type ?? e.part?.type ?? "")
        if (/step.?finish/i.test(t)) {
          const tk = e.part?.tokens ?? e.tokens ?? {}
          const input = (tk.input ?? 0) + (tk.cache?.write ?? 0)
          if (first) { usage.firstInput = input + (tk.cache?.read ?? 0); first = false }
          usage.inputTokens += input; usage.outputTokens += (tk.output ?? 0) + (tk.reasoning ?? 0); usage.cachedTokens += tk.cache?.read ?? 0
          usage.costUsd = (usage.costUsd ?? 0) + (Number(e.part?.cost ?? e.cost) || 0)
          usage.turns = (usage.turns ?? 0) + 1
        }
        if (t === "tool_use" || e.part?.type === "tool") usage.toolCalls!++
      }
      const texts = evs.filter((e) => e.part?.type === "text" && typeof e.part.text === "string").map((e) => e.part.text)
      return { usage, finalText: texts.join("\n") }
    },
  }
}

/** Token counts wherever a CLI reports them (summed usage-looking objects), the last text, and tool-call-looking events. */
function generic(stdout: string): { usage: Usage; finalText: string } {
  const evs = events(stdout)
  const usage = zero()
  const visit = (o: any): void => {
    if (!o || typeof o !== "object") return
    const input = o.input_tokens ?? o.inputTokens ?? o.prompt_tokens
    const output = o.output_tokens ?? o.outputTokens ?? o.completion_tokens
    if (typeof input === "number" && typeof output === "number") {
      if (usage.firstInput === undefined) usage.firstInput = input + (o.cache_read_input_tokens ?? o.cached_input_tokens ?? o.cacheReadTokens ?? 0)
      usage.inputTokens += input; usage.outputTokens += output
      usage.cachedTokens += o.cache_read_input_tokens ?? o.cached_input_tokens ?? o.cacheReadTokens ?? 0
      return
    }
    for (const v of Object.values(o)) visit(v)
  }
  evs.forEach(visit)
  usage.toolCalls = evs.filter((e) => /tool.?(call|use)/i.test(String(e.type ?? ""))).length
  let finalText = ""
  for (const e of evs) {
    const t = typeof e?.result === "string" ? e.result : typeof e?.text === "string" ? e.text : typeof e?.content === "string" && e.role === "assistant" ? e.content : typeof e?.message?.content === "string" ? e.message.content : undefined
    if (t) finalText = t
  }
  return { usage, finalText }
}
