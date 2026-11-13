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
  // Only the models are wanted: no MCP server is started for a listing (none is given, and the user's are not read).
  "--strict-mcp-config",
]

/** The probe's env on top of the launch's: no claude.ai connectors, no IDE link (as T3 Code's capability probe). */
const PROBE_ENV: Record<string, string | undefined> = {
  ENABLE_CLAUDEAI_MCP_SERVERS: "false",
  CLAUDE_CODE_AUTO_CONNECT_IDE: "0",
  CLAUDE_CODE_IDE_SKIP_AUTO_INSTALL: "1",
  FORCE_CODE_TERMINAL: undefined,
}

/**
 * With gateway model discovery on, Claude Code refreshes its model cache in the background after initialize: the
 * probe lives on that long (CLAUDE_CODE_GATEWAY_MODEL_DISCOVERY_TIMEOUT_MS, else 3 s) before it is stopped.
 */
export function gatewayDiscoveryGraceMs(env: Readonly<Record<string, string | undefined>>): number {
  const on = String(env.CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY ?? "").trim().toLowerCase()
  if (!on || on === "0" || on === "false") return 0
  if (!String(env.ANTHROPIC_BASE_URL ?? "").trim()) return 0
  const configured = Number(env.CLAUDE_CODE_GATEWAY_MODEL_DISCOVERY_TIMEOUT_MS)
  return Number.isFinite(configured) && configured > 0 ? configured : 3_000
}

/**
 * Claude's own model entries as the picker's models. Its "default" is the picker's own Default, so it is left out;
 * each model keeps its effort levels and fast mode.
 */
export function claudeModelsFromAgent(entries: readonly AgentModelEntry[]): ModelInfo[] {
  const seen = new Set<string>()
  const out: ModelInfo[] = []
  for (const e of entries) {
    // `default` is Claude Code's pointer at its own default model, not a model.
    if (!e.value || e.value === "default") continue
    // An alias and a pinned ID that are the same model are listed once, [1m] context suffixes aside.
    const ids = [e.value, e.resolved ?? ""].map(withoutContextSuffix).filter((id) => id.length > 0)
    if (ids.some((id) => seen.has(id))) continue
    for (const id of ids) seen.add(id)
    out.push({
      id: e.value,
      displayName: e.name.trim() || e.value,
      agent: Agent.Claude,
      ...(e.efforts?.length ? { reasoningLevels: e.efforts.map((id) => ({ id })) } : {}),
      ...(e.fast ? { supportsFast: true as const } : {}),
    })
  }
  return out
}

const withoutContextSuffix = (id: string) => id.replace(/\[[^\]]*\]$/, "").trim().toLowerCase()

/** The initialize reply's raw `models` (Claude Code 2.1.308's shape) as model entries. */
function entriesFromReply(models: unknown): AgentModelEntry[] {
  if (!Array.isArray(models)) return []
  const out: AgentModelEntry[] = []
  for (const row of models as Record<string, unknown>[]) {
    const value = typeof row?.value === "string" ? row.value : ""
    if (!value) continue
    const efforts = row.supportsEffort === true && Array.isArray(row.supportedEffortLevels)
      ? row.supportedEffortLevels.filter((e): e is string => typeof e === "string" && e !== "")
      : []
    out.push({
      value,
      name: typeof row.displayName === "string" ? row.displayName : value,
      ...(typeof row.resolvedModel === "string" && row.resolvedModel ? { resolved: row.resolvedModel } : {}),
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
  const env: Record<string, string | undefined> = { ...process.env, ...opts?.launch?.env, ...PROBE_ENV }
  for (const key of opts?.launch?.unset ?? []) delete env[key]
  for (const [key, value] of Object.entries(PROBE_ENV)) if (value === undefined) delete env[key]
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
    const grace = gatewayDiscoveryGraceMs(env)
    if (grace > 0) setTimeout(() => child.kill(), grace)
    else child.kill()
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
