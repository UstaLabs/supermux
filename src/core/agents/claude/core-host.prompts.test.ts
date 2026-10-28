import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { AgentDriver, AgentRuntime } from "../../../../packages/supermux-core/src/index.js"
import type { ClaudeOptions } from "../../../../packages/supermux-core/src/claude/index.js"
import { SESSION_INBOX_DIR } from "../../../shared/paths"
import { claudeReadRule, createClaudeCoreHost } from "./core-host"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

/** The session's own working directory, beside its state directory: the core refuses a cwd that is the state directory. */
function workdir(): string {
  const work = mkdtempSync(join(tmpdir(), "claude-work-"))
  dirs.push(work)
  return work
}

function fakeDriver(): AgentDriver {
  return {
    id: "claude",
    async open() {
      const runtime: AgentRuntime = {
        agentSessionId: "n1",
        capabilities: { resume: true, steer: false, fork: false, detach: true },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
      }
      return runtime
    },
  }
}

test("claude default mode is bypassPermissions", async () => {
  const captured: ClaudeOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "claude-host-"))
  dirs.push(dir)
  const work = workdir()
  const host = createClaudeCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      return fakeDriver()
    },
  })
  const extra = { sessionHome: dir, sessionName: "s", sessionId: "id1", workdir: work, cwd: work }
  const handle = host.register({ id: "id1", env: {}, extra })
  await handle.start({ cwd: work })
  expect(captured[0]?.permissions.permissionMode).toBe("bypassPermissions")
  expect(captured[0]?.permissionPrompts).toBe("host")
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})

test("claude ask mode launches with Claude's default permission mode", async () => {
  const captured: ClaudeOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "claude-host-"))
  dirs.push(dir)
  const work = workdir()
  const host = createClaudeCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      return fakeDriver()
    },
  })
  const extra = { sessionHome: dir, sessionName: "s", sessionId: "id2", workdir: work, cwd: work, permissionMode: "ask" }
  const handle = host.register({ id: "id2", env: {}, extra })
  await handle.start({ cwd: work })
  expect(captured[0]?.permissions.permissionMode).toBe("default")
  expect(captured[0]?.permissionPrompts).toBe("host")
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})

/** Starts `sessions` Claude sessions on ONE host (with another inbox folder when given): the launch options, and what the logger wrote to stderr meanwhile. */
async function launchSessions(sessions: number, inboxDir?: string): Promise<{ captured: ClaudeOptions[]; logged: string[] }> {
  const captured: ClaudeOptions[] = []
  const logged: string[] = []
  const write = process.stderr.write
  process.stderr.write = ((chunk: string | Uint8Array) => { logged.push(String(chunk)); return true }) as typeof process.stderr.write
  try {
    const dir = mkdtempSync(join(tmpdir(), "claude-host-"))
    dirs.push(dir)
    const host = createClaudeCoreHost({
      stateDirectory: dir,
      ...(inboxDir ? { inboxDir } : {}),
      driverFactory: (options) => {
        captured.push(options)
        return fakeDriver()
      },
    })
    for (let i = 0; i < sessions; i++) {
      const work = workdir()
      const extra = { sessionHome: join(dir, `home-${i}`), sessionName: `s${i}`, sessionId: `id-inbox-${i}`, workdir: work, cwd: work }
      const handle = host.register({ id: `id-inbox-${i}`, env: {}, extra })
      await handle.start({ cwd: work })
      await handle.stop({ mode: "shutdown" })
    }
    await host.close({ agents: "shutdown" })
  } finally { process.stderr.write = write }
  return { captured, logged }
}

test("claude may read its inbox through a read-only allow rule, not by adding the folder as a workspace directory", async () => {
  const { captured, logged } = await launchSessions(1)
  // --add-dir would let accept-edits / auto modes edit in the folder without asking and load its .claude skills.
  expect(captured[0]?.args ?? []).not.toContain("--add-dir")
  // The launch carries exactly one allow rule: Read of everything under the inbox (an absolute path starts with //).
  expect(captured[0]?.allowedTools).toEqual([`Read(/${SESSION_INBOX_DIR}/**)`])
  expect(logged.filter(line => line.includes("claude_inbox_read_rule_omitted"))).toEqual([])
})

test("an inbox folder the rule cannot carry: no rule for any session of the host, and one warning with the path", async () => {
  const unsafe = "/Users/John Smith/.mux/session_inboxes" // a macOS home folder with a space
  const { captured, logged } = await launchSessions(2, unsafe)
  expect(captured).toHaveLength(2)
  for (const options of captured) {
    expect(options.allowedTools).toEqual([])
    expect(options.args ?? []).not.toContain("--add-dir")
  }
  const warnings = logged.filter(line => line.includes("claude_inbox_read_rule_omitted"))
  expect(warnings).toHaveLength(1)
  expect(warnings[0]).toContain("[WARN]")
  expect(warnings[0]).toContain("[agents/claude/core-host]")
  expect(warnings[0]).toContain(JSON.stringify({ path: unsafe }))
})

test("claudeReadRule: Read of everything under an absolute folder, nothing for a folder the rule syntax cannot carry", () => {
  expect(claudeReadRule("/home/u/.mux/session_inboxes")).toBe("Read(//home/u/.mux/session_inboxes/**)")
  expect(claudeReadRule("/home/u/.mux/session_inboxes//")).toBe("Read(//home/u/.mux/session_inboxes/**)")
  for (const dir of ["", "/", "relative/dir", "~/inbox", "/home/u/in box", "/home/u/in(box)", "/home/*/inbox", "/home/u/in?ox", "/home/u/[ab]", "/home/u/a,b", "/home/u/a\\b", "/home/u/a\tb"]) {
    expect(claudeReadRule(dir)).toBeUndefined()
  }
})
