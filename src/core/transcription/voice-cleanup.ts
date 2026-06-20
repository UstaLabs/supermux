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
export const VOICE_CLEANUP_ENGINE: CleanupEngine = ENV_ENGINE === "cursor" ? "cursor" : "opencode"
export const VOICE_CLEANUP_MODEL =
  process.env.MUX_VOICE_CLEANUP_MODEL ||
  (VOICE_CLEANUP_ENGINE === "cursor" ? "composer-2.5-fast" : "opencode/deepseek-v4-flash-free")

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

// Returns the cleaned text. THROWS on failure (non-zero exit / empty output) so
// the caller degrades to the raw draft — a failed cleanup must never lose it.
export async function cleanupDraft(input: CleanupInput, opts: CleanupOpts = {}): Promise<{ text: string }> {
  if (!input.draft.trim()) return { text: "" }
  const engine = opts.engine || VOICE_CLEANUP_ENGINE
  const model = opts.model || VOICE_CLEANUP_MODEL
  const cwd = opts.cwd ?? tmpdir()
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const argv = cleanupArgv(engine, model, buildCleanupPrompt(input))
  const run = opts.run ?? spawnOneShot
  const { code, out } = await run(argv, cwd, timeoutMs)
  const text = (out ?? "").trim()
  if (code !== 0 || !text) {
    log.warn("voice_cleanup_failed", { engine, model, code, hasText: !!text })
    throw new Error(`voice cleanup failed (engine=${engine}, code=${code}${text ? "" : ", empty output"})`)
  }
  return { text }
}
