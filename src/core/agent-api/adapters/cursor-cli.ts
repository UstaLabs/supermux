// cursor-cli adapter — the universal fallback. One-shot text completion via the
// `cursor-agent -p ... --output-format text --model <m> --force` CLI (ported from
// voice-cleanup.ts). Reliable under the systemd broker (no TTY needed). Always the
// last fallback in the orchestration. The subprocess `run` is injectable so the
// adapter is fully unit-testable with no spawning.

import { spawn as bunSpawn } from "bun"
import { tmpdir } from "os"
import { DEFAULT_TIMEOUT_MS, type AgentApi, type CompleteOpts } from "../types"

const CURSOR_BIN = process.env.MUX_CURSOR_BIN ?? "cursor-agent"
const DEFAULT_MODEL = process.env.MUX_VOICE_CLEANUP_FALLBACK_MODEL ?? "composer-2.5-fast"

export type RunFn = (argv: string[], cwd: string, timeoutMs: number) => Promise<{ code: number; out: string }>

// Spawn the CLI one-shot, capturing stdout (stderr ignored), with a kill-on-timeout.
async function spawnOneShot(argv: string[], cwd: string, timeoutMs: number): Promise<{ code: number; out: string }> {
  const proc = bunSpawn(argv, { cwd, stdout: "pipe", stderr: "ignore" })
  const timer = setTimeout(() => {
    try {
      proc.kill()
    } catch {}
  }, timeoutMs)
  try {
    const out = await new Response(proc.stdout).text()
    const code = await proc.exited
    return { code, out }
  } finally {
    clearTimeout(timer)
  }
}

export interface CursorCliAdapterOpts {
  run?: RunFn
}

export function cursorCliAdapter(opts: CursorCliAdapterOpts = {}): AgentApi {
  const run = opts.run ?? spawnOneShot

  return {
    name: "cursor-cli",

    // cursor-agent is assumed present under the broker; this is the last-resort
    // fallback, so it always advertises availability.
    isAvailable(): boolean {
      return true
    },

    async complete(prompt: string, complOpts: CompleteOpts = {}): Promise<string> {
      const model = complOpts.model ?? DEFAULT_MODEL
      const argv = [CURSOR_BIN, "-p", prompt, "--output-format", "text", "--model", model, "--force"]
      const { code, out } = await run(argv, tmpdir(), complOpts.timeoutMs ?? DEFAULT_TIMEOUT_MS)
      const text = (out ?? "").trim()
      if (code !== 0 || !text) {
        throw new Error(`cursor-cli: cleanup failed (code=${code}${text ? "" : ", empty"})`)
      }
      return text
    },
  }
}
