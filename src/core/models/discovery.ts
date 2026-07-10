import type { AgentKind } from "../agents/types"
import { AgentKind as Agent } from "../../shared/agents"
import { readFileSync } from "fs"
import { exec } from "child_process"
import { promisify } from "util"
import { home } from "../../shared/home"

const execAsync = promisify(exec)

export type ModelInfo = {
  id: string
  displayName: string
  agent: AgentKind
  reasoningLevels?: { id: string; description?: string }[]
}

const CREDENTIALS_PATH = `${home()}/.claude/.credentials.json`

function readOAuthToken(): string | undefined {
  try {
    const raw = JSON.parse(readFileSync(CREDENTIALS_PATH, "utf8"))
    return raw?.claudeAiOauth?.accessToken
  } catch {
    return undefined
  }
}

export async function discoverClaudeModels(opts?: {
  fetch?: typeof globalThis.fetch
}): Promise<ModelInfo[]> {
  const fetchFn = opts?.fetch ?? globalThis.fetch
  const token = readOAuthToken()
  if (!token) return []
  try {
    const res = await fetchFn("https://api.anthropic.com/v1/models?limit=100", {
      headers: {
        "Authorization": `Bearer ${token}`,
        "anthropic-version": "2023-06-01",
      },
    })
    if (!res.ok) return []
    const body = await res.json() as { data?: { id: string; display_name: string }[] }
    return (body.data ?? []).map((m) => ({
      id: m.id,
      displayName: m.display_name,
      agent: Agent.Claude,
    }))
  } catch {
    return []
  }
}

// Non-blocking so the periodic model refresh never stalls the event loop.
async function runCli(command: string): Promise<string> {
  const { stdout } = await execAsync(command, { encoding: "utf8", timeout: 10_000 })
  return stdout
}

export async function discoverCodexModels(opts?: {
  run?: (cmd: string) => Promise<string>
}): Promise<ModelInfo[]> {
  const runFn = opts?.run ?? runCli
  try {
    const raw = await runFn("codex debug models")
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
  const runFn = opts?.run ?? runCli
  try {
    const raw = await runFn("cursor-agent --list-models")
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

export async function discoverOpenCodeModels(opts?: {
  run?: (cmd: string) => Promise<string>
}): Promise<ModelInfo[]> {
  const runFn = opts?.run ?? runCli
  try {
    // opencode prints one "<providerID>/<modelID>" per line. The id is kept in
    // that form because OpenCodeAdapter.parseModel splits on the first "/" to
    // build session.prompt's { providerID, modelID }. Only authed providers
    // appear, so this doubles as the picker's opencode list.
    const raw = await runFn("opencode models")
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
