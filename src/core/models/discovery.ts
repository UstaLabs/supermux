import type { AgentKind } from "../agents/types"
import { AgentKind as Agent } from "../../shared/agents"
import { readFileSync } from "fs"
import { resolveCommand, spawnCommand } from "../process/launcher"
import { home } from "../../shared/home"

export type ModelInfo = {
  id: string
  displayName: string
  agent: AgentKind
  reasoningLevels?: { id: string; description?: string }[]
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

export async function discoverClaudeModels(opts?: {
  fetch?: typeof globalThis.fetch
  env?: Record<string, string | undefined>
  readCredentialFile?: () => string | undefined
}): Promise<ModelInfo[]> {
  const fetchFn = opts?.fetch ?? globalThis.fetch
  const env = opts?.env ?? process.env
  const readCredentialFile = opts?.readCredentialFile ?? (() => {
    try {
      return readFileSync(CREDENTIALS_PATH, "utf8")
    } catch {
      return undefined
    }
  })

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
      }))
    } catch {
      // Try the next configured auth source.
    }
  }
  return []
}

// Non-blocking so the periodic model refresh never stalls the event loop.
async function runCli(names: readonly string[], args: string[]): Promise<string> {
  const env = { ...process.env }
  const command = resolveCommand(names, env, process.platform)
  if (!command) throw new Error(`${names.join(" or ")} not found`)
  return await new Promise<string>((resolve, reject) => {
    const child = spawnCommand(command, args, { env, stdio: ["ignore", "pipe", "pipe"] })
    let stdout = ""
    let stderr = ""
    const timer = setTimeout(() => {
      try { child.kill("SIGTERM") } catch {}
      reject(new Error(`${names[0]} timed out`))
    }, 10_000)
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
}): Promise<ModelInfo[]> {
  try {
    const raw = opts?.run ? await opts.run("codex debug models") : await runCli(["codex"], ["debug", "models"])
    const parsed = JSON.parse(raw) as { models?: { slug: string; display_name: string; visibility?: string; supported_reasoning_levels?: { effort: string; description?: string }[] }[] }
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
      }))
  } catch {
    return []
  }
}

export async function discoverCursorModels(opts?: {
  run?: (cmd: string) => Promise<string>
}): Promise<ModelInfo[]> {
  try {
    const raw = opts?.run ? await opts.run("cursor-agent --list-models") : await runCli(["cursor-agent", "agent"], ["--list-models"])
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

// grok's discoverer lives in agents/grok/model-discovery.ts — it reads modelState
// from the ACP handshake rather than a CLI command, since `grok models` prints ids
// only (no per-model reasoning metadata, no JSON mode).

export async function discoverOpenCodeModels(opts?: {
  run?: (cmd: string) => Promise<string>
}): Promise<ModelInfo[]> {
  try {
    // opencode prints one "<providerID>/<modelID>" per line. The id is kept in
    // that form because OpenCodeAdapter.parseModel splits on the first "/" to
    // build session.prompt's { providerID, modelID }. Only authed providers
    // appear, so this doubles as the picker's opencode list.
    const raw = opts?.run ? await opts.run("opencode models") : await runCli(["opencode"], ["models"])
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
