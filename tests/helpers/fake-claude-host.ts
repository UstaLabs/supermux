import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createClaudeCoreHost, type ClaudeCoreHost } from "../../src/core/agents/claude/core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../packages/supermux-core/src/index.js"
import type { ClaudeOptions } from "../../packages/supermux-core/src/claude/index.js"

export function fakeClaudeHost(nativeId = "claude-session-id", options: {
  openHangs?: (ctx: DriverContext) => boolean
  /** Holds an open until the returned promise settles (a start still in progress). */
  openWaits?: (ctx: DriverContext) => Promise<void> | undefined
} = {}): {
  host: ClaudeCoreHost
  opens: DriverContext[]
  prompts: string[][]
  /** How many runtimes were closed (an agent stopped). */
  readonly closes: number
  close(): Promise<void>
} {
  const opens: DriverContext[] = []
  const prompts: string[][] = []
  let closes = 0
  const stateDirectory = mkdtempSync(join(tmpdir(), "mux-fake-claude-host-"))
  const factory = (_options: ClaudeOptions, _overrides: SessionConfiguration): AgentDriver => ({
    id: "claude",
    async open(ctx) {
      opens.push(ctx)
      // Never settles on its own; only the core's lifetime abort (host close) ends it.
      if (options.openHangs?.(ctx)) return new Promise<AgentRuntime>((_, reject) => ctx.signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true }))
      await options.openWaits?.(ctx)
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? nativeId,
        capabilities: { resume: true, steer: false, fork: false, detach: true, configure: false, history: false },
        async prompt(content) {
          prompts.push(content.map((c) => ("text" in c ? String(c.text) : `<${c.type}>`)))
          return { stopReason: "end_turn" }
        },
        async interrupt() {},
        async close() { closes++ },
      }
      return runtime
    },
  })
  const host = createClaudeCoreHost({ stateDirectory, driverFactory: factory })
  return {
    host,
    opens,
    prompts,
    get closes() { return closes },
    async close() {
      await host.close({ agents: "shutdown" }).catch(() => {})
      rmSync(stateDirectory, { recursive: true, force: true })
    },
  }
}
