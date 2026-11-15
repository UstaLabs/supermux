import { expect, test } from "bun:test"
import { mkdtempSync, readFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { CliAdoption, cliAdoption, cliNoteFor, withCliNote } from "./adoption"

const opts = { binDir: "/state/bin", socketsDir: "/state/sockets" }

test("a session knows the CLI once it was launched with it or told once", () => {
  const a = new CliAdoption()
  expect(a.knows("s1")).toBe(false)
  a.markLaunch("s1")
  expect(a.knows("s1")).toBe(true)
  a.markNote("s2")
  expect(a.knows("s2")).toBe(true)
})

test("calls: every CLI call counts; an MCP call counts only for a tool the CLI also does", () => {
  const a = new CliAdoption(undefined, () => new Date("2026-11-15T20:00:00Z"))
  a.recordCall("s", "cli", "list_sessions")
  a.recordCall("s", "mcp", "list_sessions")
  a.recordCall("s", "mcp", "message_session")   // kept on MCP: not a moved tool
  expect(a.get("s")).toEqual({ cliCalls: 1, lastCliAt: "2026-11-15T20:00:00.000Z", mcpMovedCalls: 1, lastMcpMovedAt: "2026-11-15T20:00:00.000Z" })
})

test("the record survives a restart (a JSON file, written atomically)", () => {
  const file = join(mkdtempSync(join(tmpdir(), "sm-adopt-")), "cli-adoption.json")
  const a = new CliAdoption(file)
  a.markLaunch("s1")
  a.flush()
  expect(JSON.parse(readFileSync(file, "utf8")).version).toBe(1)
  expect(new CliAdoption(file).knows("s1")).toBe(true)
})

test("the note: once, only for a session whose process predates the CLI, never for an internal one", () => {
  const a = new CliAdoption()
  const first = withCliNote("old", false, "hello", opts, a)
  expect(first.endsWith("\n\nhello")).toBe(true)
  expect(first).toContain("run `MUX_SESSION_ID=old MUX_SOCKETS_DIR=/state/sockets /state/bin/supermux` instead")
  expect(first).toContain("never wait\nor poll")
  expect(withCliNote("old", false, "again", opts, a)).toBe("again")
  a.markLaunch("new")
  expect(withCliNote("new", false, "hi", opts, a)).toBe("hi")
  expect(withCliNote("rpc", true, "task", opts, a)).toBe("task")
})

test("on Windows the note's command is PowerShell", () => {
  expect(cliNoteFor("s", { binDir: "C:\\state\\bin", socketsDir: "C:\\state\\sockets", platform: "win32" }))
    .toContain('$env:MUX_SESSION_ID="s"; $env:MUX_SOCKETS_DIR="C:\\state\\sockets"; & "C:\\state\\bin\\supermux.cmd"')
})

test("unbound (tests, scripts) the store is off: no note is ever added", () => {
  expect(withCliNote("x", false, "hi", opts, cliAdoption())).toBe("hi")
})

test("a broker restart re-attaches running agents: the launch mark alone doesn't spare a process that lacks the CLI", () => {
  const a = new CliAdoption()
  a.markLaunch("old")                                   // prepared at the restart, but the agent process is days old
  expect(withCliNote("old", false, "hi", { ...opts, hasCli: () => false }, a)).toContain("<system-reminder>\nThis is a one-time note from supermux, not from the user.")
  expect(withCliNote("old", false, "again", { ...opts, hasCli: () => false }, a)).toBe("again")  // told once
  a.markLaunch("new")
  expect(withCliNote("new", false, "hi", { ...opts, hasCli: () => true }, a)).toBe("hi")
  // the OS can't tell (Windows): the launch mark decides
  expect(withCliNote("new", false, "hi", { ...opts, hasCli: () => undefined }, a)).toBe("hi")
})

test.if(process.platform === "linux")("processHasCli reads a process's own PATH", async () => {
  const { spawn } = await import("node:child_process")
  const { processHasCli } = await import("./adoption")
  const p = spawn("sleep", ["2"], { env: { PATH: "/state/bin:/usr/bin:/bin" } })
  const q = spawn("sleep", ["2"], { env: { PATH: "/usr/bin:/bin" } })
  try {
    await new Promise((r) => setTimeout(r, 100))
    expect(processHasCli(p.pid!, "/state/bin")).toBe(true)
    expect(processHasCli(q.pid!, "/state/bin")).toBe(false)
    expect(processHasCli(999999, "/state/bin")).toBeUndefined()
  } finally { p.kill(); q.kill() }
})

test("the note carries the very hint new sessions get: environment.md's CLI paragraph, read from there (one source)", async () => {
  const { readEnvironmentMd } = await import("../agents/environment")
  const { cliHintFromEnvironment } = await import("./adoption")
  const hint = cliHintFromEnvironment()!
  expect(hint).toStartWith("The same things are a CLI in your shell")
  expect(readEnvironmentMd()).toContain(hint)
  expect(cliNoteFor("s", opts)).toContain(hint)
  for (const cmd of ["supermux help", "supermux session ls", "supermux msg <session>", "supermux card show", "supermux port ls"]) expect(hint).toContain(cmd)
})
