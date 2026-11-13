// #129: Claude's model list, from Claude Code itself. Its initialize reply lists the account's models (what the Agent
// SDK's supportedModels() reads), read with Claude's own login: on a Mac its own Keychain item, so the broker needs no
// credential of its own and never raises a prompt. A live session's reply is learned as it comes; otherwise a
// control-only `claude` is asked: one initialize, no turn, nothing spent, hooks off. The API is the fallback where a
// token is readable.
import { AgentKind as Agent } from "../../shared/agents"
import { resolveCommand, spawnCommand } from "../process/launcher"
import { host } from "@supermux/platform"
import type { AgentModelEntry } from "../../../packages/supermux-core/src/events/normalized.js"
import type { DiscoveryLaunch, ModelInfo } from "./discovery"
import type { LearnedModels } from "./learned"

/** How long a list a live session (or the last ask) reported stands before the control-only claude is asked again. */
export const CLAUDE_MODELS_FRESH_MS = 6 * 3_600_000

/**
 * The control-only claude: stream-json both ways, waiting for our initialize, with every hook off so the user's own
 * SessionStart scripts never run for a listing.
 */
export const CLAUDE_INIT_ARGS = [
  "--print", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--await-initialize",
  "--settings", JSON.stringify({ disableAllHooks: true }),
]

/**
 * Claude's own model entries as the picker's models. Its "default" is the picker's own Default, so it is left out;
 * each model keeps its effort levels and fast mode.
 */
export function claudeModelsFromAgent(entries: readonly AgentModelEntry[]): ModelInfo[] {
  return entries
    .filter((e) => e.value !== "default")
    .map((e) => ({
      id: e.value,
      displayName: e.name,
      agent: Agent.Claude,
      ...(e.efforts?.length ? { reasoningLevels: e.efforts.map((id) => ({ id })) } : {}),
      ...(e.fast ? { supportsFast: true as const } : {}),
    }))
}

/** The initialize reply's raw `models` (Claude Code 2.1.308's shape) as model entries. */
function entriesFromReply(models: unknown): AgentModelEntry[] {
  if (!Array.isArray(models)) return []
  const out: AgentModelEntry[] = []
  for (const row of models as Record<string, unknown>[]) {
    const value = typeof row?.value === "string" ? row.value : ""
    if (!value) continue
    const efforts = Array.isArray(row.supportedEffortLevels) ? row.supportedEffortLevels.filter((e): e is string => typeof e === "string" && e !== "") : []
    out.push({
      value,
      name: typeof row.displayName === "string" ? row.displayName : value,
      ...(efforts.length ? { efforts } : {}),
      ...(row.supportsFastMode === true ? { fast: true as const } : {}),
    })
  }
  return out
}

/** One control-only claude process, as discovery drives it. */
export interface ClaudeControlProcess {
  write(line: string): void
  onLine(fn: (line: string) => void): void
  onExit(fn: () => void): void
  kill(): void
}

/** Starts `claude` with [args] and [env]; null when it is not installed. */
export type ClaudeControlSpawn = (args: string[], env: Record<string, string | undefined>) => ClaudeControlProcess | null

const realSpawn: ClaudeControlSpawn = (args, env) => {
  const command = resolveCommand(["claude"], env, host.os())
  if (!command) return null
  const child = spawnCommand(command, args, { env, stdio: ["pipe", "pipe", "ignore"] })
  let buffer = ""
  const lineListeners: ((line: string) => void)[] = []
  child.stdout?.on("data", (chunk) => {
    buffer += chunk.toString("utf8")
    let i: number
    while ((i = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, i)
      buffer = buffer.slice(i + 1)
      for (const fn of lineListeners) fn(line)
    }
  })
  return {
    write: (line) => { child.stdin?.write(`${line}\n`) },
    onLine: (fn) => { lineListeners.push(fn) },
    onExit: (fn) => { child.once("exit", fn); child.once("error", fn) },
    kill: () => { try { child.stdin?.end(); child.kill() } catch {} },
  }
}

/**
 * Asks a control-only `claude` for the account's models: one initialize request, nothing else (no turn is started, no
 * token spent), then the process is stopped. [launch] is an added account's env. Empty when claude is missing, exits,
 * or does not answer within [timeoutMs].
 */
export async function discoverClaudeModelsViaCli(opts?: { launch?: DiscoveryLaunch; spawn?: ClaudeControlSpawn; timeoutMs?: number }): Promise<ModelInfo[]> {
  const env: Record<string, string | undefined> = { ...process.env, ...opts?.launch?.env }
  for (const key of opts?.launch?.unset ?? []) delete env[key]
  const child = (opts?.spawn ?? realSpawn)(CLAUDE_INIT_ARGS, env)
  if (!child) return []
  const requestId = `supermux-models-${Date.now()}`
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    const entries = await new Promise<AgentModelEntry[]>((resolve) => {
      timer = setTimeout(() => resolve([]), opts?.timeoutMs ?? 20_000)
      child.onExit(() => resolve([]))
      child.onLine((line) => {
        let frame: { type?: string; response?: { request_id?: string; response?: { models?: unknown } } }
        try { frame = JSON.parse(line) } catch { return }
        if (frame.type === "control_response" && frame.response?.request_id === requestId) resolve(entriesFromReply(frame.response.response?.models))
      })
      child.write(JSON.stringify({ type: "control_request", request_id: requestId, request: { subtype: "initialize" } }))
    })
    return claudeModelsFromAgent(entries)
  } finally {
    if (timer) clearTimeout(timer)
    child.kill()
  }
}

/**
 * Claude's list for the picker: a fresh one a live session reported (or the last ask found); else the control-only
 * claude with the system login, then the API where a token is readable; then each added account the same way. A
 * system list claude gave is learned, so the next refresh needs no process.
 */
export async function discoverClaude(deps: {
  learned: LearnedModels
  now?: () => number
  cli: (launch?: DiscoveryLaunch) => Promise<ModelInfo[]>
  api: (launch?: DiscoveryLaunch) => Promise<ModelInfo[]>
  accounts: () => Promise<(() => Promise<DiscoveryLaunch>)[]>
}): Promise<ModelInfo[]> {
  const now = deps.now ?? Date.now
  const learned = deps.learned.get(Agent.Claude)
  const at = deps.learned.learnedAt(Agent.Claude)
  if (learned?.length && at !== undefined && now() - at < CLAUDE_MODELS_FRESH_MS) return learned
  const fromCli = await deps.cli().catch(() => [] as ModelInfo[])
  if (fromCli.length) {
    deps.learned.learn(Agent.Claude, fromCli, now())
    return fromCli
  }
  const fromApi = await deps.api().catch(() => [] as ModelInfo[])
  if (fromApi.length) return fromApi
  for (const materialize of await deps.accounts().catch(() => [])) {
    try {
      const launch = await materialize()
      const viaCli = await deps.cli(launch).catch(() => [] as ModelInfo[])
      if (viaCli.length) return viaCli
      const viaApi = await deps.api(launch).catch(() => [] as ModelInfo[])
      if (viaApi.length) return viaApi
    } catch {
      // The next account.
    }
  }
  return learned ?? []
}
