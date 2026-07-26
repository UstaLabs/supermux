// cursor-cli adapter — the universal fallback. One-shot text completion via the
// `cursor-agent -p ... --output-format text --model <m> --force` CLI (ported from
// voice-cleanup.ts). Reliable under the systemd broker (no TTY needed). Always the
// last fallback in the orchestration. The subprocess `run` is injectable so the
// adapter is fully unit-testable with no spawning.

import { tmpdir } from "os"
import { DEFAULT_TIMEOUT_MS, type AgentApi, type CompleteOpts } from "../types"
import { resolveCommand, spawnCommand } from "../../process/launcher"

const DEFAULT_MODEL = process.env.MUX_VOICE_CLEANUP_FALLBACK_MODEL ?? "composer-2.5-fast"

export type RunFn = (argv: string[], cwd: string, timeoutMs: number) => Promise<{ code: number; out: string }>

// Spawn the CLI one-shot, capturing stdout (stderr ignored), with a kill-on-timeout.
async function spawnOneShot(argv: string[], cwd: string, timeoutMs: number): Promise<{ code: number; out: string }> {
  const env = { ...process.env }
  const requested = argv[0] ?? "cursor-agent"
  const names = requested === "cursor-agent" ? ["cursor-agent", "agent"] : [requested]
  const command = resolveCommand(names, env, process.platform) ?? requested
  return await new Promise((resolve) => {
    const proc = spawnCommand(command, argv.slice(1), { cwd, env, stdio: ["ignore", "pipe", "ignore"] })
    let out = ""
    let settled = false
    const finish = (code: number) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolve({ code, out })
    }
    proc.stdout?.on("data", (chunk) => { out += chunk.toString("utf8") })
    proc.once("error", () => finish(127))
    proc.once("exit", (code) => finish(code ?? 1))
    const timer = setTimeout(() => {
      try { proc.kill("SIGTERM") } catch {}
      finish(124)
    }, timeoutMs)
  })
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
      const argv = [process.env.MUX_CURSOR_BIN ?? "cursor-agent", "-p", prompt, "--output-format", "text", "--model", model, "--force"]
      const { code, out } = await run(argv, tmpdir(), complOpts.timeoutMs ?? DEFAULT_TIMEOUT_MS)
      const text = (out ?? "").trim()
      if (code !== 0 || !text) {
        throw new Error(`cursor-cli: cleanup failed (code=${code}${text ? "" : ", empty"})`)
      }
      return text
    },
  }
}
