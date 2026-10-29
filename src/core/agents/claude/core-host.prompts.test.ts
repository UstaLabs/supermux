import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { AgentDriver, AgentRuntime } from "../../../../packages/supermux-core/src/index.js"
import type { ClaudeOptions } from "../../../../packages/supermux-core/src/claude/index.js"
import { SESSION_INBOX_DIR, inboxFilePath } from "../../../shared/paths"
import { claudeInboxShellRules, claudeReadRule, createClaudeCoreHost } from "./core-host"

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

/** The rules a session with `id` launches with: Read of the whole inbox folder, and exact shell commands on its own file. */
function inboxRules(id: string): string[] {
  const file = inboxFilePath(id)
  return [`Read(/${SESSION_INBOX_DIR}/**)`, `Bash(cat ${file})`, `Bash(head ${file})`, `Bash(tail ${file})`, `Bash(wc -l ${file})`]
}

test("claude may read its inbox through allow rules (Read of the folder, exact shell commands on its own file), not by adding the folder as a workspace directory", async () => {
  const { captured, logged } = await launchSessions(2)
  // --add-dir would let accept-edits / auto modes edit in the folder without asking and load its .claude skills.
  for (const options of captured) expect(options.args ?? []).not.toContain("--add-dir")
  // Each session: the Read rule, then cat / head / tail / wc -l on ITS OWN file and no other.
  expect(captured[0]?.allowedTools).toEqual(inboxRules("id-inbox-0"))
  expect(captured[1]?.allowedTools).toEqual(inboxRules("id-inbox-1"))
  expect(captured[0]?.allowedTools?.join("\n")).not.toContain("id-inbox-1")
  // Exact commands: no `*`, no `:*`, so extra arguments and other forms still ask.
  for (const rule of captured[0]!.allowedTools!.filter(rule => rule.startsWith("Bash("))) expect(rule).not.toMatch(/[*]/)
  expect(logged.filter(line => line.includes("claude_inbox_"))).toEqual([])
})

test("an inbox folder the rules cannot carry: no rule for any session of the host, and one warning with the path", async () => {
  const unsafe = "/Users/John (Work)/.mux/session_inboxes" // parentheses are the rule syntax's own
  const { captured, logged } = await launchSessions(2, unsafe)
  expect(captured).toHaveLength(2)
  for (const options of captured) {
    expect(options.allowedTools).toEqual([])
    expect(options.args ?? []).not.toContain("--add-dir")
  }
  const warnings = logged.filter(line => line.includes("claude_inbox_"))
  expect(warnings).toHaveLength(1)
  expect(warnings[0]).toContain("claude_inbox_read_rule_omitted")
  expect(warnings[0]).toContain("[WARN]")
  expect(warnings[0]).toContain("[agents/claude/core-host]")
  expect(warnings[0]).toContain(JSON.stringify({ path: unsafe }))
})

test("a folder with a space keeps the Read rule (spaces survive inside Read(...)) but gets no shell rules, with one warning", async () => {
  const spaced = "/Users/John Smith/.mux/session_inboxes" // a command naming it must be quoted, and Claude matches the typed text
  const { captured, logged } = await launchSessions(2, spaced)
  for (const options of captured) expect(options.allowedTools).toEqual([`Read(//Users/John Smith/.mux/session_inboxes/**)`])
  const warnings = logged.filter(line => line.includes("claude_inbox_"))
  expect(warnings).toHaveLength(1)
  expect(warnings[0]).toContain("claude_inbox_shell_rules_omitted")
  expect(warnings[0]).toContain(JSON.stringify({ path: spaced }))
})

test("claudeReadRule: Read of everything under an absolute folder, nothing for a folder the rule syntax cannot carry", () => {
  expect(claudeReadRule("/home/u/.mux/session_inboxes")).toBe("Read(//home/u/.mux/session_inboxes/**)")
  expect(claudeReadRule("/home/u/.mux/session_inboxes//")).toBe("Read(//home/u/.mux/session_inboxes/**)")
  // Spaces stay inside the parentheses, where Claude's --allowedTools splitter leaves them alone.
  expect(claudeReadRule("/Users/John Smith/.mux/session_inboxes")).toBe("Read(//Users/John Smith/.mux/session_inboxes/**)")
  expect(claudeReadRule("/home/şirket/.mux/session_inboxes")).toBe("Read(//home/şirket/.mux/session_inboxes/**)")
  // A comma would survive there too, but no real path has one and refusing it is free defence in depth.
  expect(claudeReadRule("/home/u/a,b")).toBeUndefined()
  for (const dir of ["", "/", "relative/dir", "~/inbox", "/home/u/in(box)", "/home/u/in)box", "/home/*/inbox", "/home/u/in?ox", "/home/u/[ab]", "/home/u/a\\b", "/home/u/a\tb", "/home/u/a\nb", "C:\\Users\\u\\.mux"]) {
    expect(claudeReadRule(dir)).toBeUndefined()
  }
})

test("claudeInboxShellRules: exact cat / head / tail / wc -l commands on one plain path, nothing otherwise", () => {
  const file = "/home/u/.mux/session_inboxes/7c1d2e9a-0000.jsonl"
  expect(claudeInboxShellRules(file)).toEqual([`Bash(cat ${file})`, `Bash(head ${file})`, `Bash(tail ${file})`, `Bash(wc -l ${file})`])
  expect(claudeInboxShellRules("/home/şirket/.mux/x.jsonl")).toHaveLength(4)
  // A path that must be quoted in a shell is never typed as the rule reads: no rules.
  for (const path of ["", "/", "//", "/a/", "/a//b.jsonl", "/a/../b.jsonl", "/a/./b.jsonl", "relative/x.jsonl", "/Users/John Smith/x.jsonl", "/a,b/x.jsonl", "/a(b)/x.jsonl", "/a*/x.jsonl", "/a?/x.jsonl", "/a'b/x.jsonl", "/a$b/x.jsonl", "/a:*", "C:\\Users\\u\\x.jsonl"]) {
    expect(claudeInboxShellRules(path)).toEqual([])
  }
})
