// #29: a language-server install tool or a configured server that is a Windows .cmd shim (npm, a user's own server)
// could not start: node's spawn runs no .cmd without a shell. Resolved on the PATH first, the launcher runs it through
// cmd.exe, verbatim and hidden; and every LSP process now gets the launcher's defaults.
import { expect, test } from "bun:test"
import { EventEmitter } from "node:events"
import { runInstall } from "./install"
import { LspServerProcess } from "./server"

/** A spawn that records what it was asked to start, and a quiet child for it. */
function recorder() {
  const calls: Array<{ command: string; args: readonly string[]; options: Record<string, unknown> }> = []
  const spawn = ((command: string, args: readonly string[], options: Record<string, unknown>) => {
    calls.push({ command, args, options })
    const child = Object.assign(new EventEmitter(), {
      stdout: new EventEmitter(),
      stderr: new EventEmitter(),
      stdin: { write: () => true },
      pid: 4242,
      kill: () => true,
    })
    return child
  }) as never
  return { calls, spawn }
}

const files = (...paths: string[]) => (path: string) => paths.some((p) => p.toLowerCase() === path.toLowerCase())

test("on Windows an install tool found as a .cmd shim runs through cmd.exe, verbatim and hidden", () => {
  const { calls, spawn } = recorder()
  runInstall(["npm", "install", "-g", "pyright"], () => {}, () => {},
    { env: { PATH: "C:\\tools", ComSpec: "C:\\Windows\\System32\\cmd.exe" } },
    { platform: "win32", fileExists: files("C:\\tools\\npm.cmd"), spawn })
  expect(calls).toHaveLength(1)
  expect(calls[0]!.command).toBe("C:\\Windows\\System32\\cmd.exe")
  expect(calls[0]!.args.at(-1)).toContain("C:\\tools\\npm.cmd")
  expect(calls[0]!.options).toMatchObject({ windowsHide: true, windowsVerbatimArguments: true })
})

test("on Windows a language server configured as a .cmd shim runs through cmd.exe, in its folder, with its env", () => {
  const { calls, spawn } = recorder()
  new LspServerProcess({
    command: "C:\\tools\\my-ls.cmd", args: ["--stdio"], cwd: "C:\\work", env: { MY_LS: "1", ComSpec: "C:\\Windows\\System32\\cmd.exe" },
    onMessage: () => {}, onExit: () => {},
    launch: { platform: "win32", fileExists: files("C:\\tools\\my-ls.cmd"), spawn },
  })
  expect(calls[0]!.command).toBe("C:\\Windows\\System32\\cmd.exe")
  // The shim, then its argument quoted for cmd.exe (windowsCmdCommandLine: every argument escaped, none read as a shell line).
  expect(calls[0]!.args.at(-1)).toContain("C:\\tools\\my-ls.cmd")
  expect(calls[0]!.args.at(-1)).toContain("--stdio")
  expect(calls[0]!.options).toMatchObject({ cwd: "C:\\work", windowsHide: true })
  expect((calls[0]!.options.env as Record<string, string>).MY_LS).toBe("1")
})

test("elsewhere the tool or server found on the PATH runs as itself, with the launcher's defaults", () => {
  const { calls, spawn } = recorder()
  runInstall(["go", "install", "golang.org/x/tools/gopls@latest"], () => {}, () => {},
    { env: { PATH: "/opt/go/bin" } }, { platform: "linux", fileExists: files("/opt/go/bin/go"), spawn })
  new LspServerProcess({
    command: "gopls", args: [], cwd: "/work", env: { PATH: "/opt/go/bin" },
    onMessage: () => {}, onExit: () => {},
    launch: { platform: "linux", fileExists: files("/opt/go/bin/gopls"), spawn },
  })
  expect(calls.map((c) => [c.command, [...c.args]])).toEqual([
    ["/opt/go/bin/go", ["install", "golang.org/x/tools/gopls@latest"]],
    ["/opt/go/bin/gopls", []],
  ])
  for (const call of calls) expect(call.options.windowsHide).toBe(true)
})
