import { spawn as bunSpawn } from "bun"
import { readFileSync } from "fs"
import { tmpdir, homedir } from "os"
import { join } from "path"
import { makeLogger } from "../../shared/log"
import { DEFAULT_TIMEOUT_MS } from "../agent-api/types"
import { type CleanupInput, buildCleanupPrompt } from "../agent-api/prompt"

const log = makeLogger("voice-cleanup")

// Voice cleanup, PRIMARY path: a DIRECT HTTP call to OpenCode Zen's OpenAI-compatible
// API, reusing the opencode subscription key from auth.json. Fast (~2s), free
// (deepseek-v4-flash-free), and broker-safe — no CLI, no subprocess, no TTY (the thing
// that broke the opencode/cursor CLI under the systemd broker). FALLBACK: the
// cursor-agent CLI one-shot (reliable under the broker) if the API call fails.
export type CleanupEngine = "opencode-api" | "cursor" | "none"

const ZEN_BASE = process.env.MUX_OPENCODE_ZEN_BASE ?? "https://opencode.ai/zen/v1"
const API_MODEL = process.env.MUX_VOICE_CLEANUP_API_MODEL ?? "deepseek-v4-flash-free"
const API_ENABLED = process.env.MUX_VOICE_CLEANUP_API !== "0"
const OPENCODE_AUTH = process.env.MUX_OPENCODE_AUTH ?? join(homedir(), ".local", "share", "opencode", "auth.json")

// Fallback CLI (cursor) — only used if the direct API fails.
const CURSOR_BIN = process.env.MUX_CURSOR_BIN ?? "cursor-agent"
const FALLBACK_MODEL = process.env.MUX_VOICE_CLEANUP_FALLBACK_MODEL ?? "composer-2.5-fast"

// For logging/visibility (the model the primary path uses).
export const VOICE_CLEANUP_MODEL = API_ENABLED ? API_MODEL : FALLBACK_MODEL

export interface CleanupOpts {
  timeoutMs?: number
  apiModel?: string
  prefer?: "api" | "cli"
  // Injectables for tests:
  fetchFn?: typeof fetch
  readKey?: () => string | null
  run?: (argv: string[], cwd: string, timeoutMs: number) => Promise<{ code: number; out: string }>
}

// Re-exported from the canonical agent-api/prompt module so existing importers
// (and the voice-cleanup tests) keep a stable entry point.
export { type CleanupInput, buildCleanupPrompt }

function opencodeKey(): string | null {
  try {
    const auth = JSON.parse(readFileSync(OPENCODE_AUTH, "utf8"))
    return (auth?.opencode?.key as string) ?? (auth?.["opencode-go"]?.key as string) ?? null
  } catch {
    return null
  }
}

// PRIMARY: direct OpenCode Zen API (OpenAI-compatible chat/completions).
async function cleanupViaApi(input: CleanupInput, opts: CleanupOpts): Promise<{ text: string; engine: CleanupEngine }> {
  const key = (opts.readKey ?? opencodeKey)()
  if (!key) throw new Error("opencode zen key not found")
  const f = opts.fetchFn ?? fetch
  const model = opts.apiModel ?? API_MODEL
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), opts.timeoutMs ?? DEFAULT_TIMEOUT_MS)
  try {
    const res = await f(`${ZEN_BASE}/chat/completions`, {
      method: "POST",
      headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
      body: JSON.stringify({ model, messages: [{ role: "user", content: buildCleanupPrompt(input) }] }),
      signal: ctrl.signal,
    })
    if (!res.ok) throw new Error(`opencode zen api ${res.status}`)
    const data = (await res.json()) as { choices?: { message?: { content?: string } }[] }
    const text = String(data?.choices?.[0]?.message?.content ?? "").trim()
    if (!text) throw new Error("opencode zen api returned empty")
    return { text, engine: "opencode-api" }
  } finally {
    clearTimeout(timer)
  }
}

async function spawnOneShot(argv: string[], cwd: string, timeoutMs: number): Promise<{ code: number; out: string }> {
  const proc = bunSpawn(argv, { cwd, stdout: "pipe", stderr: "ignore" })
  const timer = setTimeout(() => {
    try { proc.kill() } catch {}
  }, timeoutMs)
  try {
    const out = await new Response(proc.stdout).text()
    const code = await proc.exited
    return { code, out }
  } finally {
    clearTimeout(timer)
  }
}

// FALLBACK: cursor-agent CLI one-shot (clean text output, works under the broker).
async function cleanupViaCli(input: CleanupInput, opts: CleanupOpts): Promise<{ text: string; engine: CleanupEngine }> {
  const run = opts.run ?? spawnOneShot
  const argv = [CURSOR_BIN, "-p", buildCleanupPrompt(input), "--output-format", "text", "--model", FALLBACK_MODEL, "--force"]
  const { code, out } = await run(argv, tmpdir(), opts.timeoutMs ?? DEFAULT_TIMEOUT_MS)
  const text = (out ?? "").trim()
  if (code !== 0 || !text) throw new Error(`cursor cli cleanup failed (code=${code}${text ? "" : ", empty"})`)
  return { text, engine: "cursor" }
}

// Direct API first (fast/free/broker-safe), cursor CLI fallback. Returns the cleaned
// text + which engine produced it. THROWS only if BOTH fail → caller keeps the raw draft.
export async function cleanupDraft(input: CleanupInput, opts: CleanupOpts = {}): Promise<{ text: string; engine: CleanupEngine }> {
  if (!input.draft.trim()) return { text: "", engine: "none" }
  const prefer = opts.prefer ?? (API_ENABLED ? "api" : "cli")
  if (prefer === "api") {
    try {
      return await cleanupViaApi(input, opts)
    } catch (e) {
      log.warn("voice_cleanup_api_failed", { err: String(e) })
    }
  }
  return await cleanupViaCli(input, opts)
}
