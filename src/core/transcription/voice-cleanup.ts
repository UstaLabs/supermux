import { spawn as bunSpawn } from "bun"
import { tmpdir } from "os"
import { makeLogger } from "../../shared/log"

const log = makeLogger("voice-cleanup")

// Voice cleanup runs an agent CLI ONE-SHOT (no session: no tmux pane, no
// dev-channel, no MCP, no spawn/idle-reap/timeout machinery) — just a fast
// subprocess on the agent's own auth. Default engine is opencode's
// deepseek-v4-flash-free: free, ~5s, accurate in testing (beats cursor's
// composer-2.5-fast at ~11-22s). Override via MUX_VOICE_CLEANUP_ENGINE / _MODEL.
export type CleanupEngine = "opencode" | "cursor"

const ENV_ENGINE = process.env.MUX_VOICE_CLEANUP_ENGINE as CleanupEngine | undefined
// Default cursor: it runs cleanly one-shot under the systemd broker (no TTY) and
// returns plain text. opencode (deepseek-v4-flash-free) is faster + free but only
// works WITH a pseudo-terminal — under the broker it's agentic (does tool calls
// like web search) and its TUI output is unparseable — so it's opt-in via
// MUX_VOICE_CLEANUP_ENGINE=opencode (e.g. a TTY context), not the broker default.
export const VOICE_CLEANUP_ENGINE: CleanupEngine = ENV_ENGINE === "opencode" ? "opencode" : "cursor"
export const VOICE_CLEANUP_MODEL =
  process.env.MUX_VOICE_CLEANUP_MODEL ||
  (VOICE_CLEANUP_ENGINE === "opencode" ? "opencode/deepseek-v4-flash-free" : "composer-2.5-fast")

// Reliable fallback when the primary engine returns empty/errors (e.g. opencode's
// occasional cold-start miss): cursor completes cleanly under the broker context.
export const FALLBACK_ENGINE: CleanupEngine = "cursor"
export const FALLBACK_MODEL = process.env.MUX_VOICE_CLEANUP_FALLBACK_MODEL || "composer-2.5-fast"

const OPENCODE_BIN = process.env.MUX_OPENCODE_BIN ?? "opencode"
const CURSOR_BIN = process.env.MUX_CURSOR_BIN ?? "cursor-agent"
const DEFAULT_TIMEOUT_MS = Number(process.env.MUX_VOICE_CLEANUP_TIMEOUT_MS ?? 30_000)

export interface CleanupInput {
  draft: string
  recentMessages: { role: string; text: string }[]
  skills: string[]
}

export interface CleanupOpts {
  engine?: CleanupEngine
  model?: string
  cwd?: string
  timeoutMs?: number
  // Injectable runner for tests; the default spawns the agent CLI one-shot.
  run?: (argv: string[], cwd: string, timeoutMs: number) => Promise<{ code: number; out: string }>
}

// One-shot prompt: must return ONLY the corrected text — no tool, no preamble.
export function buildCleanupPrompt(input: CleanupInput): string {
  const ctx = input.recentMessages.map((m) => `${m.role}: ${m.text}`).join("\n")
  return [
    "You clean up a rough speech-to-text draft into the user's intended message.",
    "Use the conversation context and the command/skill names below to fix mis-heard words — especially technical or product names that appear in the conversation.",
    "Preserve the user's meaning, wording, and tone. Do NOT answer the message, expand it, add content, explain yourself, or use any tools.",
    "Output ONLY the corrected text — nothing else.",
    input.skills.length ? `\nKnown commands/skills: ${input.skills.join(", ")}` : "",
    input.recentMessages.length ? `\nConversation so far (most recent last):\n${ctx}` : "",
    `\nDraft: ${JSON.stringify(input.draft)}`,
    "\nCorrected text:",
  ]
    .filter(Boolean)
    .join("\n")
}

// argv for a one-shot, plugin-free run on the chosen engine.
export function cleanupArgv(engine: CleanupEngine, model: string, prompt: string): string[] {
  if (engine === "cursor") {
    return [CURSOR_BIN, "-p", prompt, "--output-format", "text", "--model", model, "--force"]
  }
  // opencode: `run --pure` skips external plugins (faster); message is positional.
  return [OPENCODE_BIN, "run", "--pure", "-m", model, prompt]
}

async function spawnOneShot(argv: string[], cwd: string, timeoutMs: number): Promise<{ code: number; out: string }> {
  // Neutral cwd so the agent doesn't load a project's rules/config.
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

// Tries the primary engine, then falls back to cursor on empty/error (so a cold
// miss never leaves the user with the raw draft). Returns the cleaned text and
// which engine produced it. THROWS only if EVERY engine fails — then the caller
// degrades to the raw draft (a failed cleanup must never lose it).
export async function cleanupDraft(
  input: CleanupInput,
  opts: CleanupOpts = {},
): Promise<{ text: string; engine: CleanupEngine | "none" }> {
  if (!input.draft.trim()) return { text: "", engine: "none" }
  const cwd = opts.cwd ?? tmpdir()
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const prompt = buildCleanupPrompt(input)
  const run = opts.run ?? spawnOneShot
  const primary = { engine: opts.engine || VOICE_CLEANUP_ENGINE, model: opts.model || VOICE_CLEANUP_MODEL }
  // Primary first, then the cursor fallback (skipped if the primary already is cursor).
  const chain = primary.engine === FALLBACK_ENGINE ? [primary] : [primary, { engine: FALLBACK_ENGINE, model: FALLBACK_MODEL }]
  for (const { engine, model } of chain) {
    try {
      const { code, out } = await run(cleanupArgv(engine, model, prompt), cwd, timeoutMs)
      const text = (out ?? "").trim()
      if (code === 0 && text) return { text, engine }
      log.warn("voice_cleanup_engine_miss", { engine, model, code, hasText: !!text })
    } catch (e) {
      log.warn("voice_cleanup_engine_error", { engine, model, err: String(e) })
    }
  }
  throw new Error(`voice cleanup failed on all engines (${chain.map((c) => c.engine).join(", ")})`)
}
