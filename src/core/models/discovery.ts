import type { AgentKind } from "../agents/types"
import { AgentKind as Agent } from "../../shared/agents"
import { readFileSync } from "fs"
import { resolveCommand, spawnCommand } from "../process/launcher"
import { home } from "../../shared/home"
import { join } from "path"
import { claudeModelSupportsFast, CODEX_FAST_SERVICE_TIER } from "./fast-mode"

/**
 * An added account's launch env (the registry's `materialize`): discovery runs with it in place of
 * the system login, so the picker still lists models when the system login is gone or refused.
 */
export type DiscoveryLaunch = { env: Record<string, string>; unset?: string[]; args?: string[] }

export type ModelInfo = {
  id: string
  displayName: string
  agent: AgentKind
  reasoningLevels?: { id: string; description?: string }[]
  /** Fast mode is offered on this model (set only when true). */
  supportsFast?: true
}

const CREDENTIALS_PATH = `${home()}/.claude/.credentials.json`

function oauthTokenFromCredential(raw: string): string | undefined {
  try {
    const parsed = JSON.parse(raw)
    return parsed?.claudeAiOauth?.accessToken
  } catch {
    return undefined
  }
}

function readFileOrUndefined(path: string): string | undefined {
  try {
    return readFileSync(path, "utf8")
  } catch {
    return undefined
  }
}

export async function discoverClaudeModels(opts?: {
  fetch?: typeof globalThis.fetch
  env?: Record<string, string | undefined>
  readCredentialFile?: () => string | undefined
  launch?: DiscoveryLaunch
}): Promise<ModelInfo[]> {
  const fetchFn = opts?.fetch ?? globalThis.fetch
  // An account's launch env stands alone: a subscription home's credentials, its token, or its key.
  const launch = opts?.launch
  const env = launch ? launch.env : opts?.env ?? process.env
  const credentialsPath = launch
    ? launch.env.CLAUDE_CONFIG_DIR ? join(launch.env.CLAUDE_CONFIG_DIR, ".credentials.json") : undefined
    : CREDENTIALS_PATH
  const readCredentialFile = opts?.readCredentialFile
    ?? (() => credentialsPath ? readFileOrUndefined(credentialsPath) : undefined)

  const oauthTokens = [
    env.CLAUDE_CODE_OAUTH_TOKEN,
    oauthTokenFromCredential(readCredentialFile() ?? ""),
  ].filter((token, i, all): token is string => Boolean(token) && all.indexOf(token) === i)

  const authHeaders: Record<string, string>[] = [
    ...oauthTokens.map((token) => ({ Authorization: `Bearer ${token}` })),
    ...(env.ANTHROPIC_API_KEY ? [{ "x-api-key": env.ANTHROPIC_API_KEY }] : []),
  ]

  for (const auth of authHeaders) {
    try {
      const res = await fetchFn("https://api.anthropic.com/v1/models?limit=100", {
        headers: {
          ...auth,
          "anthropic-version": "2023-06-01",
        },
      })
      if (!res.ok) continue
      const body = await res.json() as { data?: { id: string; display_name: string }[] }
      return (body.data ?? []).map((m) => ({
        id: m.id,
        displayName: m.display_name,
        agent: Agent.Claude,
        ...(claudeModelSupportsFast(m.id) ? { supportsFast: true as const } : {}),
      }))
    } catch {
      // Try the next configured auth source.
    }
  }
  return []
}

// Non-blocking so the periodic model refresh never stalls the event loop.
export async function runCli(names: readonly string[], args: string[], timeoutMs: number, launch?: DiscoveryLaunch): Promise<string> {
  const env: Record<string, string | undefined> = { ...process.env, ...launch?.env }
  for (const key of launch?.unset ?? []) delete env[key]
  // An account's args are global flags (Codex's `-c` provider config), so they go before the subcommand.
  args = [...(launch?.args ?? []), ...args]
  const command = resolveCommand(names, env, process.platform)
  if (!command) throw new Error(`${names.join(" or ")} not found`)
  return await new Promise<string>((resolve, reject) => {
    const child = spawnCommand(command, args, { env, stdio: ["ignore", "pipe", "pipe"] })
    let stdout = ""
    let stderr = ""
    const timer = setTimeout(() => {
      try { child.kill("SIGTERM") } catch {}
      reject(new Error(`${names[0]} timed out`))
    }, timeoutMs)
    child.stdout?.on("data", (chunk) => { stdout += chunk.toString("utf8") })
    child.stderr?.on("data", (chunk) => { stderr += chunk.toString("utf8") })
    child.once("error", (error) => { clearTimeout(timer); reject(error) })
    child.once("exit", (code) => {
      clearTimeout(timer)
      if (code === 0) resolve(stdout)
      else reject(new Error(`${names[0]} exited ${code}: ${stderr.slice(0, 200)}`))
    })
  })
}

export async function discoverCodexModels(opts?: {
  run?: (cmd: string) => Promise<string>
  launch?: DiscoveryLaunch
}): Promise<ModelInfo[]> {
  try {
    const raw = opts?.run ? await opts.run("codex debug models") : await runCli(["codex"], ["debug", "models"], 10_000, opts?.launch)
    const parsed = JSON.parse(raw) as { models?: { slug: string; display_name: string; visibility?: string; supported_reasoning_levels?: { effort: string; description?: string }[]; service_tiers?: { id?: string }[] }[] }
    return (parsed.models ?? [])
      .filter((m) => m.visibility === "list")
      .map((m) => ({
        id: m.slug,
        displayName: m.display_name,
        agent: Agent.Codex,
        reasoningLevels: (m.supported_reasoning_levels ?? []).map((r) => ({
          id: r.effort,
          description: r.description,
        })),
        ...((m.service_tiers ?? []).some((t) => t.id === CODEX_FAST_SERVICE_TIER) ? { supportsFast: true as const } : {}),
      }))
  } catch {
    return []
  }
}

export async function discoverCursorModels(opts?: {
  run?: (cmd: string) => Promise<string>
  launch?: DiscoveryLaunch
}): Promise<ModelInfo[]> {
  try {
    const raw = opts?.run ? await opts.run("cursor-agent --list-models") : await runCli(["cursor-agent"], ["--list-models"], 10_000, opts?.launch)
    const models: ModelInfo[] = []
    for (const line of raw.split("\n")) {
      const m = line.match(/^(\S+)\s+-\s+(.+)$/)
      if (m) models.push({ id: m[1]!, displayName: m[2]!.trim(), agent: Agent.Cursor })
    }
    return models
  } catch {
    return []
  }
}

// grok and antigravity discoverers live in their agent folders (agents/grok/model-discovery.ts,
// agents/antigravity/model-discovery.ts — the latter runs `agy models` under a shadow HOME).
// grok's reads modelState from the ACP handshake rather than a CLI command, since
// `grok models` prints ids only (no per-model reasoning metadata, no JSON mode).

export async function discoverOpenCodeModels(opts?: {
  run?: (cmd: string) => Promise<string>
  launch?: DiscoveryLaunch
}): Promise<ModelInfo[]> {
  try {
    // opencode prints one "<providerID>/<modelID>" per line. The id is kept in
    // that form because OpenCodeAdapter.parseModel splits on the first "/" to
    // build session.prompt's { providerID, modelID }. Only authed providers
    // appear, so this doubles as the picker's opencode list.
    // `opencode models` boots the whole OpenCode runtime (plugins included) before
    // it prints anything — ~15 s on a warm box — so it gets a larger budget than
    // the other CLIs or the picker's opencode list stays empty.
    const raw = opts?.run ? await opts.run("opencode models") : await runCli(["opencode"], ["models"], 60_000, opts?.launch)
    const models: ModelInfo[] = []
    for (const line of raw.split("\n")) {
      const id = line.trim()
      if (!id || !id.includes("/")) continue
      models.push({ id, displayName: id, agent: Agent.OpenCode })
    }
    return models
  } catch {
    return []
  }
}

/**
 * Discovers with the system login first, then with each added account's launch env in turn, until
 * one returns models. Model lists are per agent, not per account, so the first login that works
 * fills the picker; an account that fails to materialize (expired token) is skipped.
 */
export async function discoverWithAccounts(
  discover: (launch?: DiscoveryLaunch) => Promise<ModelInfo[]>,
  launches: () => Promise<(() => Promise<DiscoveryLaunch>)[]>,
): Promise<ModelInfo[]> {
  const system = await discover().catch(() => [] as ModelInfo[])
  if (system.length > 0) return system
  let candidates: (() => Promise<DiscoveryLaunch>)[]
  try {
    candidates = await launches()
  } catch {
    return []
  }
  for (const materialize of candidates) {
    try {
      const models = await discover(await materialize())
      if (models.length > 0) return models
    } catch {
      // Try the next account.
    }
  }
  return []
}
