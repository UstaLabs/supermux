import { spawn as bunSpawn } from "bun"
import { tmpdir } from "os"
import { makeLogger } from "../../shared/log"

const log = makeLogger("cursor-cleanup")

// Voice cleanup runs the cursor-agent CLI ONE-SHOT (`-p`), not as an agent
// session: no tmux pane, no dev-channel, no MCP, no spawn/idle-reap/timeout
// machinery — just a fast subprocess on the cursor subscription. "Composer 2.5
// Fast" is cursor's fast default; override with MUX_VOICE_CLEANUP_MODEL.
export const VOICE_CLEANUP_MODEL = process.env.MUX_VOICE_CLEANUP_MODEL ?? "composer-2.5-fast"
const CURSOR_BIN = process.env.MUX_CURSOR_BIN ?? "cursor-agent"
const DEFAULT_TIMEOUT_MS = Number(process.env.MUX_VOICE_CLEANUP_TIMEOUT_MS ?? 30_000)

export interface CleanupInput {
  draft: string
  recentMessages: { role: string; text: string }[]
  skills: string[]
}

export interface CleanupOpts {
  model?: string
  cwd?: string
  timeoutMs?: number
  // Injectable runner for tests; the default spawns cursor-agent one-shot.
  run?: (prompt: string, model: string) => Promise<{ code: number; out: string }>
}

// The cursor-agent runs one-shot and must return ONLY the corrected text — no
// resolve/reject tool, no chat preamble, no tool use. The prompt enforces that.
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

async function spawnCursor(prompt: string, model: string, cwd: string, timeoutMs: number): Promise<{ code: number; out: string }> {
  // cwd is a neutral temp dir so cursor-agent doesn't load a project's
  // .cursor/rules (which would slow it down / skew the cleanup).
  const proc = bunSpawn([CURSOR_BIN, "-p", prompt, "--output-format", "text", "--model", model, "--force"], {
    cwd,
    stdout: "pipe",
    stderr: "ignore",
  })
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
// the caller degrades to the raw draft — a failed cleanup must never lose the draft.
export async function cleanupViaCursor(input: CleanupInput, opts: CleanupOpts = {}): Promise<{ text: string }> {
  if (!input.draft.trim()) return { text: "" }
  const model = opts.model || VOICE_CLEANUP_MODEL
  const cwd = opts.cwd ?? tmpdir()
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const prompt = buildCleanupPrompt(input)
  const run = opts.run ?? ((p, m) => spawnCursor(p, m, cwd, timeoutMs))
  const { code, out } = await run(prompt, model)
  const text = (out ?? "").trim()
  if (code !== 0 || !text) {
    log.warn("cursor_cleanup_failed", { code, hasText: !!text })
    throw new Error(`cursor cleanup failed (code=${code}${text ? "" : ", empty output"})`)
  }
  return { text }
}
