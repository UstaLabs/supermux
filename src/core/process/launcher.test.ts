import { describe, expect, test } from "bun:test"
import { resolveCommand, spawnCommand, spawnCommandSync, windowsCmdCommandLine } from "./launcher"

const windowsFs = (...files: string[]) => ({
  fileExists: (path: string) => files.some((file) => file.toLowerCase() === path.toLowerCase()),
})

describe("resolveCommand", () => {
  test("uses Windows exe, cmd, ps1 precedence and case-insensitive Path/PATHEXT keys", () => {
    const env = { Path: "C:\\One;D:\\Two", PathExt: ".Ps1;.CmD;.Exe" }
    const deps = windowsFs("C:\\One\\tool.cmd", "D:\\Two\\tool.exe")
    expect(resolveCommand(["tool"], env, "win32", deps)).toBe("D:\\Two\\tool.exe")
  })

  test("checks Cursor aliases in caller order", () => {
    const env = { PATH: "C:\\Cursor" }
    const deps = windowsFs("C:\\Cursor\\agent.exe")
    expect(resolveCommand(["cursor-agent", "agent"], env, "win32", deps)).toBe("C:\\Cursor\\agent.exe")
  })

  test("resolves explicit Windows paths and appends a supported extension", () => {
    const deps = windowsFs("C:\\Tools\\codex.CMD")
    expect(resolveCommand(["C:\\Tools\\codex"], {}, "win32", deps)).toBe("C:\\Tools\\codex.cmd")
  })

  test("uses direct names and colon-delimited PATH on POSIX", () => {
    const deps = { fileExists: (path: string) => path === "/opt/bin/codex" }
    expect(resolveCommand(["codex"], { PATH: "/usr/bin:/opt/bin" }, "linux", deps)).toBe("/opt/bin/codex")
    expect(resolveCommand(["/opt/bin/codex"], {}, "linux", deps)).toBe("/opt/bin/codex")
  })

  test("uses the env supplied for each call, not a cached PATH", () => {
    const deps = windowsFs("C:\\Fresh\\opencode.exe")
    expect(resolveCommand(["opencode"], { Path: "C:\\Old" }, "win32", deps)).toBeNull()
    expect(resolveCommand(["opencode"], { PATH: "C:\\Fresh" }, "win32", deps)).toBe("C:\\Fresh\\opencode.exe")
  })
})

describe("spawnCommand", () => {
  test("spawns exe files directly with the original argv", () => {
    const calls: unknown[][] = []
    const spawn = (...args: unknown[]) => { calls.push(args); return { pid: 1 } as never }
    spawnCommand("C:\\Tools\\codex.exe", ["app-server", "two words"], {
      platform: "win32", env: { PATH: "live" }, stdio: "pipe", spawn,
    })
    expect(calls).toEqual([["C:\\Tools\\codex.exe", ["app-server", "two words"], { env: { PATH: "live" }, stdio: "pipe" }]])
  })

  test("runs cmd shims via ComSpec without interpolating raw metacharacters", () => {
    const calls: unknown[][] = []
    const spawn = (...args: unknown[]) => { calls.push(args); return { pid: 1 } as never }
    const argv = ["two words", "a&b|c<d>e", "100%SAFE%", "wow!", 'say "hi"', "caret^value"]
    spawnCommand("C:\\Tools\\agent.cmd", argv, {
      platform: "win32", env: { ComSpec: "C:\\Windows\\System32\\cmd.exe" }, spawn,
    })
    const [cmd, args, options] = calls[0] as [string, string[], Record<string, unknown>]
    expect(cmd).toBe("C:\\Windows\\System32\\cmd.exe")
    expect(args.slice(0, 4)).toEqual(["/d", "/v:off", "/s", "/c"])
    expect(args[4]).toBe(windowsCmdCommandLine("C:\\Tools\\agent.cmd", argv))
    expect(args[4]).not.toContain("a&b|c<d>e")
    expect(args[4]).not.toContain("100%SAFE%")
    expect(options.env).toEqual({ ComSpec: "C:\\Windows\\System32\\cmd.exe" })
    expect(options.windowsVerbatimArguments).toBe(true)
  })

  test("runs ps1 scripts through discovered PowerShell with fixed safe flags and -File argv", () => {
    const calls: unknown[][] = []
    const spawn = (...args: unknown[]) => { calls.push(args); return { pid: 1 } as never }
    spawnCommand("C:\\Tools\\opencode.ps1", ["serve", "value; Remove-Item C:\\\\*"], {
      platform: "win32",
      env: { Path: "C:\\PowerShell" },
      fileExists: windowsFs("C:\\PowerShell\\pwsh.exe").fileExists,
      spawn,
    })
    expect(calls[0]?.[0]).toBe("C:\\PowerShell\\pwsh.exe")
    expect(calls[0]?.[1]).toEqual([
      "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
      "-File", "C:\\Tools\\opencode.ps1", "serve", "value; Remove-Item C:\\\\*",
    ])
  })
})

describe("spawnCommandSync", () => {
  test("uses the same safe cmd wrapper for synchronous plugin lifecycle calls", () => {
    const calls: unknown[][] = []
    const spawnSync = (...args: unknown[]) => { calls.push(args); return { status: 0 } as never }
    spawnCommandSync("C:\\Tools\\codex.cmd", ["plugin", "add", "mux@mux"], {
      platform: "win32", env: { ComSpec: "C:\\Windows\\System32\\cmd.exe" }, spawnSync,
    })
    expect(calls[0]?.[0]).toBe("C:\\Windows\\System32\\cmd.exe")
    expect(calls[0]?.[1]).toEqual([
      "/d", "/v:off", "/s", "/c", windowsCmdCommandLine("C:\\Tools\\codex.cmd", ["plugin", "add", "mux@mux"]),
    ])
    expect((calls[0]?.[2] as any).windowsVerbatimArguments).toBe(true)
  })

  test("preserves direct POSIX argv synchronously", () => {
    const calls: unknown[][] = []
    spawnCommandSync("/usr/local/bin/codex", ["plugin", "remove", "mux@mux"], {
      platform: "linux", env: { PATH: "/usr/local/bin" },
      spawnSync: ((...args: unknown[]) => { calls.push(args); return { status: 0 } }) as never,
    })
    expect(calls).toEqual([["/usr/local/bin/codex", ["plugin", "remove", "mux@mux"], { env: { PATH: "/usr/local/bin" } }]])
  })
})
