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

/** The few closed values a feature may read from its request; the web channel fills these in. */
export type FeatureContext = { worktree?: boolean; muted?: boolean; forgeKind?: string }

const forgeVariant = (kind: string | undefined) => (kind === "github" || kind === "gitlab" ? { variant: kind } : {})

// method + path (or path pattern) → feature, matched only after the request succeeded.
const REQUEST_FEATURES: { method: string; path: string | RegExp; use: (c: FeatureContext) => FeatureUse | undefined }[] = [
  { method: "POST", path: "/sessions", use: (c) => (c.worktree ? { feature: "worktree.create" } : undefined) },
  { method: "POST", path: "/forge/clone", use: (c) => ({ feature: "forge.clone", ...forgeVariant(c.forgeKind) }) },
  { method: "POST", path: "/forge/create", use: (c) => ({ feature: "forge.create", ...forgeVariant(c.forgeKind) }) },
  { method: "POST", path: "/forge/create-local", use: () => ({ feature: "forge.create", variant: "local" }) },
  { method: "POST", path: "/workspaces", use: () => ({ feature: "workspace.create" }) },
  { method: "DELETE", path: /^\/sessions\/[^/]+$/, use: () => ({ feature: "session.archive" }) },
  { method: "POST", path: /^\/sessions\/[^/]+\/mute$/, use: (c) => (c.muted ? { feature: "session.mute" } : undefined) },
  { method: "PUT", path: "/fs/write", use: () => ({ feature: "file.write" }) },
  { method: "POST", path: "/upload/init", use: () => ({ feature: "upload" }) },
  { method: "POST", path: /^(\/sessions\/[^/]+)?\/transcribe$/, use: () => ({ feature: "voice.transcribe" }) },
  { method: "POST", path: "/speak/start", use: () => ({ feature: "voice.speak" }) },
  { method: "GET", path: "/search/messages", use: () => ({ feature: "search.messages" }) },
  { method: "POST", path: "/proxies", use: () => ({ feature: "proxy.expose" }) },
  { method: "POST", path: "/api/update/run", use: () => ({ feature: "update.run" }) },
]

export function featureForRequest(method: string, path: string, ctx: FeatureContext = {}): FeatureUse | undefined {
  const m = method.toUpperCase()
  const hit = REQUEST_FEATURES.find((f) => f.method === m && (typeof f.path === "string" ? f.path === path : f.path.test(path)))
  return hit?.use(ctx)
}

/** Which request bodies the web channel needs to peek at before routing, and for what. */
export function featureNeedsBody(method: string, path: string): boolean {
  if (method.toUpperCase() !== "POST") return false
  return path === "/sessions" || path === "/forge/clone" || path === "/forge/create" || /^\/sessions\/[^/]+\/mute$/.test(path)
}

const UPGRADE_FEATURES: Record<string, string> = {
  "/ws/term": "terminal.open",
  "/ws/display": "display.open",
  "/ws/scrcpy": "emulator.open",
  "/transcribe/stream": "voice.transcribe",
}

export function featureForUpgrade(path: string): FeatureUse | undefined {
  const f = UPGRADE_FEATURES[path]
  return f ? { feature: f } : undefined
}

export function featureForFrame(frame: { type?: unknown }): FeatureUse | undefined {
  return frame.type === "editor_open" ? { feature: "editor.open" } : undefined
}
