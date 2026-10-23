// Turns broker state into values TELEMETRY.md allows. Everything here is pure: no I/O.
import type { AgentKind } from "../../shared/agents"
import { CLIENT_OSES, SURFACES, type Surface } from "./events"

const SLUG = /^[a-z0-9][a-z0-9._/-]*$/i

// OpenCode lists models from the user's own provider config; only these hosted providers are named.
export const OPENCODE_HOSTED_PROVIDERS = [
  "anthropic", "openai", "google", "openrouter", "xai", "groq", "deepseek", "mistral", "github-copilot", "opencode",
] as const

/** A model id safe to send: catalog hit or hosted OpenCode provider; else "custom"; none → "default". */
export function telemetryModel(
  agent: AgentKind,
  model: string | undefined,
  lookup: (agent: AgentKind) => { id: string }[],
): string {
  if (!model) return "default"
  if (model.length > 80 || !SLUG.test(model)) return "custom"
  if (agent === "opencode") {
    const provider = model.split("/")[0] ?? ""
    return (OPENCODE_HOSTED_PROVIDERS as readonly string[]).includes(provider) ? model : "custom"
  }
  return lookup(agent).some((m) => m.id === model) ? model : "custom"
}

export function telemetryEffort(effort: string | undefined): string {
  return effort && effort.length <= 24 && SLUG.test(effort) ? effort : "default"
}

export type ClientInfo = { surface?: Surface; clientVersion?: string; clientOs?: string; clientOsMajor?: number }

// /ws clients are apps; telegram/whatsapp are broker-side channels and can't be claimed.
const APP_SURFACES = SURFACES.filter((s) => s !== "telegram" && s !== "whatsapp") as readonly string[]

/** Read the app's self-description from /ws query params. Invalid values are dropped, never guessed. */
export function parseClientInfo(q: URLSearchParams): ClientInfo {
  const out: ClientInfo = {}
  const surface = q.get("clientSurface")
  if (surface && APP_SURFACES.includes(surface)) out.surface = surface as Surface
  const version = q.get("clientVersion")?.trim()
  if (version && version.length <= 32 && /^[0-9A-Za-z.+-]+$/.test(version)) out.clientVersion = version
  const os = q.get("clientOs")
  if (os && (CLIENT_OSES as readonly string[]).includes(os)) out.clientOs = os
  const major = Number(q.get("clientOsMajor"))
  if (Number.isInteger(major) && major >= 1 && major <= 999) out.clientOsMajor = major
  return out
}

export type FeatureUse = { feature: string; variant?: string }

const forgeHost = (body: Record<string, unknown>): string | undefined =>
  body.provider === "github" || body.provider === "gitlab" ? body.provider : undefined

// method + path → feature. `body` is the parsed JSON request body (or {}); only closed enums are read.
const REQUEST_FEATURES: Record<string, (body: Record<string, unknown>) => FeatureUse> = {
  "POST /worktrees": () => ({ feature: "worktree.create" }),
  "POST /forge/clone": (b) => ({ feature: "forge.clone", ...(forgeHost(b) ? { variant: forgeHost(b) } : {}) }),
  "POST /forge/create": (b) => ({ feature: "forge.create", ...(forgeHost(b) ? { variant: forgeHost(b) } : {}) }),
  "POST /forge/create-local": () => ({ feature: "forge.create", variant: "local" }),
  "POST /workspaces": () => ({ feature: "workspace.create" }),
  "POST /archived-sessions": () => ({ feature: "session.archive" }),
  "POST /mute": () => ({ feature: "session.mute" }),
  "POST /fs/write": () => ({ feature: "file.write" }),
  "POST /upload/init": () => ({ feature: "upload" }),
  "POST /transcribe": () => ({ feature: "voice.transcribe" }),
  "POST /speak/start": () => ({ feature: "voice.speak" }),
  "GET /search/messages": () => ({ feature: "search.messages" }),
  "POST /proxies": () => ({ feature: "proxy.expose" }),
  "POST /api/update/run": () => ({ feature: "update.run" }),
}

export function featureForRequest(method: string, path: string, body: Record<string, unknown>): FeatureUse | undefined {
  return REQUEST_FEATURES[`${method.toUpperCase()} ${path}`]?.(body)
}

const UPGRADE_FEATURES: Record<string, string> = {
  "/ws/term": "terminal.open",
  "/ws/display": "display.open",
  "/ws/scrcpy": "emulator.open",
}

export function featureForUpgrade(path: string): FeatureUse | undefined {
  const f = UPGRADE_FEATURES[path]
  return f ? { feature: f } : undefined
}

export function featureForFrame(frame: { type?: unknown }): FeatureUse | undefined {
  return frame.type === "editor_open" ? { feature: "editor.open" } : undefined
}
