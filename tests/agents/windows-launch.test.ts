import { expect, test } from "bun:test"
import { EventEmitter } from "events"
import { PassThrough } from "stream"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { spawnOpenCodeServer } from "../../src/core/agents/opencode/spawn"
import { makeRealGrokRunner } from "../../src/core/agents/grok/runner"
import { cursorCommand } from "../../src/core/agents/cursor/core-host"

function fakeChild(onSpawn?: (child: any) => void): any {
  const child = new EventEmitter() as any
  child.pid = 42
  child.stdin = new PassThrough()
  child.stdout = new PassThrough()
  child.stderr = new PassThrough()
  child.kill = () => true
  onSpawn?.(child)
  return child
}

// Cursor runs on supermux-core now (the per-turn runner is gone); its command choice keeps dev's
// rule: cursor-agent first, then Cursor's official `agent` alias, never Grok's agent.exe.
test("Cursor prefers cursor-agent, then the official agent.cmd alias", () => {
  const onlyAlias = (path: string) => path.toLowerCase() === "c:\\cursor\\agent.cmd"
  expect(cursorCommand({ Path: "C:\\Cursor" }, "win32", onlyAlias).toLowerCase()).toBe("c:\\cursor\\agent.cmd")
  const both = (path: string) => ["c:\\cursor\\agent.cmd", "c:\\cursor\\cursor-agent.cmd"].includes(path.toLowerCase())
  expect(cursorCommand({ Path: "C:\\Cursor" }, "win32", both)).toBe("cursor-agent")
})

test("Cursor never launches Grok's agent.exe as its alias", () => {
  const grokOnly = (path: string) => path.toLowerCase() === "c:\\users\\u\\.grok\\bin\\agent.exe"
  expect(cursorCommand({ Path: "C:\\Users\\u\\.grok\\bin" }, "win32", grokOnly)).toBe("cursor-agent")
})

test("OpenCode runs a ps1 shim through PowerShell while preserving server argv", async () => {
  const calls: any[] = []
  const configHome = mkdtempSync(join(tmpdir(), "mux-win-opencode-"))
  try { await spawnOpenCodeServer({
    workdir: "C:\\Repo", configHome, authEnv: { Path: "C:\\Tools;C:\\PS" },
    port: 4321, platform: "win32", skipReady: true,
    fileExists: (path) => ["c:\\tools\\opencode.ps1", "c:\\ps\\pwsh.exe"].includes(path.toLowerCase()),
    spawn: ((command: string, args: string[], options: any) => {
      calls.push({ command, args, options })
      return fakeChild()
    }) as any,
    createClient: (() => ({
      session: { create() {}, update() {}, prompt() {}, abort() {}, list() {} },
      event: { subscribe() {} }, command: { list() {} },
    })) as any,
  }) } finally { rmSync(configHome, { recursive: true, force: true }) }
  expect(calls[0].command).toBe("C:\\PS\\pwsh.exe")
  expect(calls[0].args).toContain("-File")
  expect(calls[0].args).toContain("C:\\Tools\\opencode.ps1")
  expect(calls[0].args.slice(-5)).toEqual(["serve", "--hostname", "127.0.0.1", "--port", "4321"])
  expect(calls[0].options.env.OPENCODE_CONFIG_DIR).toContain("opencode")
})

test("Grok resolves a native exe and preserves ACP stdio argv", () => {
  const calls: any[] = []
  const client = { feed() {}, setWrite() {} } as any
  const runner = makeRealGrokRunner({
    platform: "win32", fileExists: (path) => path.toLowerCase() === "c:\\tools\\grok.exe",
    spawn: (command, args, options) => { calls.push({ command, args, options }); return fakeChild() },
  })
  runner({ workdir: "C:\\Repo", env: { Path: "C:\\Tools" }, client, onExit() {}, model: "grok-4" })
  expect(calls[0].command).toBe("C:\\Tools\\grok.exe")
  expect(calls[0].args).toEqual(["agent", "--model", "grok-4", "--always-approve", "stdio"])
  expect(calls[0].options.cwd).toBe("C:\\Repo")
})
