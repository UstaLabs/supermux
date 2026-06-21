// Onboarding-editable broker config, persisted in the `settings` table under
// key "app". Precedence at read time: stored value → env var → built-in default
// (see resolveAppConfig). Mirrors curator-config.ts: a tolerant parser that
// never throws, so a corrupt/old row degrades to safe defaults.

import { ENGINES } from "../agent-api/index"

export type ExposureMode = "local" | "public"

/** The tunnel chosen by `supermux connect`, for --status/--switch/--off. */
export interface TunnelRecord {
  provider: string // "cloudflared" | "tailscale" | "netbird" | "ngrok" | "manual"
  mode: string // provider-specific mode id (e.g. "named", "serve")
  publicUrl: string
}

export interface AppConfig {
  paName: string
  paWorkdir: string
  telegramBotToken: string // "" when unset
  webPublicUrl: string // "" when unset
  webPort: string // "" when unset; kept as string to match the env-var shape
  exposureMode: ExposureMode
  wildcardBaseDomain: string // "" when unset; optional per-session-proxy wildcard
  // Agent credentials (store-only; injected into spawned agents via env). "" when unset.
  claudeOauthToken: string
  anthropicApiKey: string
  codexApiKey: string
  cursorApiKey: string
  onboarded: boolean
  // Set by `supermux connect`; absent when no tunnel is configured. Store-only.
  tunnel?: TunnelRecord
  // Voice pipeline settings. Store-only; absent when not configured.
  voiceCleanupEngine?: string // engine used by voice cleanup (codex | opencode-zen | opencode-go | claude | cursor | cursor-cli); default codex
  voiceCleanupModel?: string // model used by the voice-cleanup agent
  voiceCleanupGlossary?: string[] // project/technical terms the cleanup must keep verbatim; default-seeded
  whisperModel?: string // path or name of the Whisper model file
  whisperLang?: string // language code (e.g. "tr", "en") or "auto"
}

export const SETTINGS_KEY_APP = "app"

/**
 * Built-in glossary seed for voice cleanup. These are project/product/technical
 * names the cleanup agent must NOT "correct" to a similarly-named world-prior
 * (e.g. "Supermux" → "Supermaven"). Returned by resolveAppConfig when the user
 * has not stored their own list; an explicitly-stored empty array is preserved.
 */
export const DEFAULT_VOICE_CLEANUP_GLOSSARY: string[] = [
  "Supermux",
  "Claude",
  "Codex",
  "Whisper",
  "Haiku",
  "Opus",
  "Sonnet",
  "OpenCode",
  "Cursor",
  "Tailscale",
]

export const defaultAppConfig: AppConfig = {
  paName: "assistant",
  paWorkdir: "",
  telegramBotToken: "",
  webPublicUrl: "",
  webPort: "",
  exposureMode: "local",
  wildcardBaseDomain: "",
  claudeOauthToken: "",
  anthropicApiKey: "",
  codexApiKey: "",
  cursorApiKey: "",
  onboarded: false,
}

function str(v: unknown, fallback: string): string {
  return typeof v === "string" ? v : fallback
}

/**
 * Coerce arbitrary input into a glossary string array, or undefined if the shape
 * is unusable. An array keeps its string entries (trimmed; empties dropped). A
 * comma-separated string is split → trimmed → empties dropped. An explicitly
 * empty array stays [] (the user cleared the list). Anything else → undefined.
 */
function parseGlossary(v: unknown): string[] | undefined {
  if (Array.isArray(v)) return v.filter((x): x is string => typeof x === "string").map((x) => x.trim()).filter(Boolean)
  if (typeof v === "string") return v.split(",").map((x) => x.trim()).filter(Boolean)
  return undefined
}

/** Coerce arbitrary input into a TunnelRecord, or undefined if shape is wrong. */
function parseTunnelRecord(v: unknown): TunnelRecord | undefined {
  if (!v || typeof v !== "object") return undefined
  const t = v as Record<string, unknown>
  if (typeof t.provider !== "string" || typeof t.mode !== "string" || typeof t.publicUrl !== "string") {
    return undefined
  }
  return { provider: t.provider, mode: t.mode, publicUrl: t.publicUrl }
}

export interface AppConfigEnv {
  MUX_PA_NAME?: string
  MUX_PA_WORKDIR?: string
  MUX_TELEGRAM_BOT_TOKEN?: string
  MUX_WEB_PUBLIC_URL?: string
  MUX_WEB_PORT?: string
}

/** Pick the first non-empty string among the candidates, else "". */
function firstNonEmpty(...vals: (string | undefined)[]): string {
  for (const v of vals) if (typeof v === "string" && v !== "") return v
  return ""
}

/**
 * Layer the persisted (partial) config over env vars over built-in defaults.
 * String fields: stored-non-empty → env-non-empty → default. Empty stored
 * strings are treated as "unset" so clearing a field reveals the env seed.
 * `onboarded` is store-only (no env source).
 */
export function resolveAppConfig(stored: Partial<AppConfig>, env: AppConfigEnv): AppConfig {
  const mode = stored.exposureMode === "public" || stored.exposureMode === "local" ? stored.exposureMode : defaultAppConfig.exposureMode
  return {
    paName: firstNonEmpty(stored.paName, env.MUX_PA_NAME) || defaultAppConfig.paName,
    paWorkdir: firstNonEmpty(stored.paWorkdir, env.MUX_PA_WORKDIR),
    telegramBotToken: firstNonEmpty(stored.telegramBotToken, env.MUX_TELEGRAM_BOT_TOKEN),
    webPublicUrl: firstNonEmpty(stored.webPublicUrl, env.MUX_WEB_PUBLIC_URL),
    webPort: firstNonEmpty(stored.webPort, env.MUX_WEB_PORT),
    exposureMode: mode,
    wildcardBaseDomain: firstNonEmpty(stored.wildcardBaseDomain), // no env source; store-only (like onboarded)
    claudeOauthToken: firstNonEmpty(stored.claudeOauthToken),
    anthropicApiKey: firstNonEmpty(stored.anthropicApiKey),
    codexApiKey: firstNonEmpty(stored.codexApiKey),
    cursorApiKey: firstNonEmpty(stored.cursorApiKey),
    onboarded: stored.onboarded === undefined ? defaultAppConfig.onboarded : Boolean(stored.onboarded),
    tunnel: parseTunnelRecord(stored.tunnel), // store-only, no env source
    ...(stored.voiceCleanupEngine !== undefined ? { voiceCleanupEngine: stored.voiceCleanupEngine } : {}),
    ...(stored.voiceCleanupModel !== undefined ? { voiceCleanupModel: stored.voiceCleanupModel } : {}),
    // Glossary is default-seeded: a stored array (incl. empty) wins; otherwise the built-in seed.
    voiceCleanupGlossary: Array.isArray(stored.voiceCleanupGlossary) ? stored.voiceCleanupGlossary : DEFAULT_VOICE_CLEANUP_GLOSSARY,
    ...(stored.whisperModel !== undefined ? { whisperModel: stored.whisperModel } : {}),
    ...(stored.whisperLang !== undefined ? { whisperLang: stored.whisperLang } : {}),
  }
}

/**
 * Coerce arbitrary input into a SPARSE partial: only keys actually present in
 * `input` are kept (type-coerced); absent keys are NOT defaulted. This is the
 * storage/precedence form — defaults are applied later by resolveAppConfig, so a
 * never-set field stays absent and correctly reveals the env seed on read.
 * Unknown keys are dropped. Never throws.
 */
export function sanitizeAppConfigPatch(input: unknown): Partial<AppConfig> {
  const o = (input ?? {}) as Record<string, unknown>
  const out: Partial<AppConfig> = {}
  if (typeof o.paName === "string") out.paName = o.paName
  if (typeof o.paWorkdir === "string") out.paWorkdir = o.paWorkdir
  if (typeof o.telegramBotToken === "string") out.telegramBotToken = o.telegramBotToken
  if (typeof o.webPublicUrl === "string") out.webPublicUrl = o.webPublicUrl
  if (typeof o.webPort === "string") out.webPort = o.webPort
  if (o.exposureMode === "public" || o.exposureMode === "local") out.exposureMode = o.exposureMode
  if (typeof o.wildcardBaseDomain === "string") out.wildcardBaseDomain = o.wildcardBaseDomain
  if (typeof o.claudeOauthToken === "string") out.claudeOauthToken = o.claudeOauthToken
  if (typeof o.anthropicApiKey === "string") out.anthropicApiKey = o.anthropicApiKey
  if (typeof o.codexApiKey === "string") out.codexApiKey = o.codexApiKey
  if (typeof o.cursorApiKey === "string") out.cursorApiKey = o.cursorApiKey
  if (o.onboarded !== undefined) out.onboarded = Boolean(o.onboarded)
  if (o.tunnel !== undefined) {
    const t = parseTunnelRecord(o.tunnel)
    if (t) out.tunnel = t
  }
  if (typeof o.voiceCleanupEngine === "string" && (ENGINES as string[]).includes(o.voiceCleanupEngine)) out.voiceCleanupEngine = o.voiceCleanupEngine
  if (typeof o.voiceCleanupModel === "string") out.voiceCleanupModel = o.voiceCleanupModel
  if (o.voiceCleanupGlossary !== undefined) {
    const g = parseGlossary(o.voiceCleanupGlossary)
    if (g !== undefined) out.voiceCleanupGlossary = g
  }
  if (typeof o.whisperModel === "string") out.whisperModel = o.whisperModel
  if (typeof o.whisperLang === "string") out.whisperLang = o.whisperLang
  return out
}

/**
 * Coerce arbitrary input (stored JSON, request body) into a valid AppConfig.
 * Unknown keys dropped; invalid enum/types fall back to `base`. Never throws.
 */
export function parseAppConfig(input: unknown, base: AppConfig = defaultAppConfig): AppConfig {
  const o = (input ?? {}) as Record<string, unknown>
  const mode = o.exposureMode === "public" || o.exposureMode === "local" ? o.exposureMode : base.exposureMode
  return {
    paName: str(o.paName, base.paName),
    paWorkdir: str(o.paWorkdir, base.paWorkdir),
    telegramBotToken: str(o.telegramBotToken, base.telegramBotToken),
    webPublicUrl: str(o.webPublicUrl, base.webPublicUrl),
    webPort: str(o.webPort, base.webPort),
    exposureMode: mode,
    wildcardBaseDomain: str(o.wildcardBaseDomain, base.wildcardBaseDomain),
    claudeOauthToken: str(o.claudeOauthToken, base.claudeOauthToken),
    anthropicApiKey: str(o.anthropicApiKey, base.anthropicApiKey),
    codexApiKey: str(o.codexApiKey, base.codexApiKey),
    cursorApiKey: str(o.cursorApiKey, base.cursorApiKey),
    onboarded: o.onboarded === undefined ? base.onboarded : Boolean(o.onboarded),
    tunnel: parseTunnelRecord(o.tunnel) ?? base.tunnel,
    ...(o.voiceCleanupEngine !== undefined ? { voiceCleanupEngine: str(o.voiceCleanupEngine, base.voiceCleanupEngine ?? "") || undefined } : base.voiceCleanupEngine !== undefined ? { voiceCleanupEngine: base.voiceCleanupEngine } : {}),
    ...(o.voiceCleanupModel !== undefined ? { voiceCleanupModel: str(o.voiceCleanupModel, base.voiceCleanupModel ?? "") || undefined } : base.voiceCleanupModel !== undefined ? { voiceCleanupModel: base.voiceCleanupModel } : {}),
    voiceCleanupGlossary: parseGlossary(o.voiceCleanupGlossary) ?? base.voiceCleanupGlossary ?? DEFAULT_VOICE_CLEANUP_GLOSSARY,
    ...(o.whisperModel !== undefined ? { whisperModel: str(o.whisperModel, base.whisperModel ?? "") || undefined } : base.whisperModel !== undefined ? { whisperModel: base.whisperModel } : {}),
    ...(o.whisperLang !== undefined ? { whisperLang: str(o.whisperLang, base.whisperLang ?? "") || undefined } : base.whisperLang !== undefined ? { whisperLang: base.whisperLang } : {}),
  }
}

/** Fields that must NEVER be returned over the API. */
export const SECRET_FIELDS = ["telegramBotToken", "claudeOauthToken", "anthropicApiKey", "codexApiKey", "cursorApiKey"] as const

/**
 * Strip every secret from a config and replace it with a boolean "<x>Configured"
 * flag. The single source of truth for what the REST layer may expose.
 */
export function redactAppConfig(cfg: AppConfig): Record<string, unknown> {
  const { telegramBotToken, claudeOauthToken, anthropicApiKey, codexApiKey, cursorApiKey, ...rest } = cfg
  return {
    ...rest,
    telegramConfigured: telegramBotToken !== "",
    claudeConfigured: claudeOauthToken !== "",
    anthropicConfigured: anthropicApiKey !== "",
    codexConfigured: codexApiKey !== "",
    cursorConfigured: cursorApiKey !== "",
  }
}

/** Map non-empty stored credentials to the env-var names the agent CLIs read. */
export function credentialEnvVars(cfg: AppConfig): Record<string, string> {
  const out: Record<string, string> = {}
  if (cfg.claudeOauthToken) out.CLAUDE_CODE_OAUTH_TOKEN = cfg.claudeOauthToken
  if (cfg.anthropicApiKey) out.ANTHROPIC_API_KEY = cfg.anthropicApiKey
  if (cfg.codexApiKey) out.OPENAI_API_KEY = cfg.codexApiKey
  if (cfg.cursorApiKey) out.CURSOR_API_KEY = cfg.cursorApiKey
  return out
}

/**
 * Apply stored credentials to an env object WITHOUT clobbering vars already set
 * (an explicitly-exported env var always wins). Returns the names it set.
 */
export function hydrateCredentialEnv(cfg: AppConfig, env: Record<string, string | undefined>): string[] {
  const applied: string[] = []
  for (const [k, v] of Object.entries(credentialEnvVars(cfg))) {
    if (!env[k]) {
      env[k] = v
      applied.push(k)
    }
  }
  return applied
}

/**
 * Authoritatively apply stored credentials to an env object, OVERWRITING any
 * existing value (unlike hydrateCredentialEnv). Use after an explicit config
 * write where the stored value is the user's intent. Only non-empty stored
 * creds are applied, so a credential the user never set won't clobber a
 * shell-exported env var. Returns the names it set.
 */
export function applyCredentialEnv(cfg: AppConfig, env: Record<string, string | undefined>): string[] {
  const vars = credentialEnvVars(cfg)
  for (const [k, v] of Object.entries(vars)) env[k] = v
  return Object.keys(vars)
}
