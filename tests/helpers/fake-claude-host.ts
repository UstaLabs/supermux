import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createClaudeCoreHost, type ClaudeCoreHost } from "../../src/core/agents/claude/core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../packages/supermux-core/src/index.js"
import type { ClaudeOptions } from "../../packages/supermux-core/src/claude/index.js"

export function fakeClaudeHost(nativeId = "claude-session-id"): {
  host: ClaudeCoreHost
  opens: DriverContext[]
  prompts: string[][]
  close(): Promise<void>
} {
  const opens: DriverContext[] = []
  const prompts: string[][] = []
  const stateDirectory = mkdtempSync(join(tmpdir(), "mux-fake-claude-host-"))
  const factory = (_options: ClaudeOptions, _overrides: SessionConfiguration): AgentDriver => ({
    id: "claude",
    async open(ctx) {
      opens.push(ctx)
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? nativeId,
        capabilities: { resume: true, steer: false, fork: false, detach: true, configure: false, history: false },
        async prompt(content) {
          prompts.push(content.map((c) => ("text" in c ? String(c.text) : `<${c.type}>`)))
          return { stopReason: "end_turn" }
        },
        async interrupt() {},
        async close() {},
      }
      return runtime
    },
  })
  const host = createClaudeCoreHost({ stateDirectory, driverFactory: factory })
  return {
    host,
    opens,
    prompts,
    async close() {
      await host.close({ agents: "shutdown" }).catch(() => {})
      rmSync(stateDirectory, { recursive: true, force: true })
    },
  }
}
