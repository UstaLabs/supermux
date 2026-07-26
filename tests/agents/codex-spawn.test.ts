import { describe, test, expect } from "bun:test"
import { Readable, Writable } from "stream"
import { spawnCodexAppServer } from "../../src/core/agents/codex/spawn"

describe("spawnCodexAppServer", () => {
  test("wires a mock spawn function with correct args and env", async () => {
    const calls: any[] = []
    const fakeSpawn = (cmd: string, args: string[], opts: any) => {
      calls.push({ cmd, args, env: opts.env })
      return {
        stdin: new Writable({ write(_c, _e, cb) { cb() } }),
        stdout: new Readable({ read() {} }),
        stderr: new Readable({ read() {} }),
        on: () => {},
        kill: () => {},
        pid: 1234,
      } as any
    }
    const handle = spawnCodexAppServer({
      codexHome: "/sess",
      workdir: "/wd",
      authEnv: { OPENAI_API_KEY: "sk-x" },
      spawn: fakeSpawn,
    })
    expect(calls[0].cmd).toBe("codex")
    expect(calls[0].args).toContain("app-server")
    // codex 0.133 rejects --dangerously-bypass-* and --cd as unexpected
    // arguments. Sandbox + approval bypass is configured via -c overrides;
    // cwd is passed in JSON-RPC thread/start, not on the CLI.
    expect(calls[0].args).toContain("-c")
    expect(calls[0].args.some((a: string) => a.includes("approval_policy"))).toBe(true)
    expect(calls[0].args.some((a: string) => a.includes("sandbox_mode"))).toBe(true)
    expect(calls[0].args).not.toContain("--dangerously-bypass-approvals-and-sandbox")
    expect(calls[0].args).not.toContain("--cd")
    expect(calls[0].env.CODEX_HOME).toBe("/sess")
    expect(calls[0].env.OPENAI_API_KEY).toBe("sk-x")
    expect(handle.pid).toBe(1234)
    expect(handle.client).toBeDefined()
  })

  test("passes model and reasoning level via -c overrides", async () => {
    const calls: any[] = []
    const fakeSpawn = (cmd: string, args: string[], opts: any) => {
      calls.push({ cmd, args, env: opts.env })
      return {
        stdin: new Writable({ write(_c, _e, cb) { cb() } }),
        stdout: new Readable({ read() {} }),
        stderr: new Readable({ read() {} }),
        on: () => {},
        kill: () => {},
        pid: 1234,
      } as any
    }
    spawnCodexAppServer({
      codexHome: "/sess",
      workdir: "/wd",
      authEnv: {},
      model: "gpt-5.5",
      reasoningLevel: "xhigh",
      spawn: fakeSpawn,
    })
    expect(calls[0].args).toContain('model="gpt-5.5"')
    expect(calls[0].args).toContain('model_reasoning_effort="xhigh"')
  })

  test("resolves and safely wraps a Windows codex.cmd shim", () => {
    const calls: any[] = []
    const fakeSpawn = (cmd: string, args: string[], opts: any) => {
      calls.push({ cmd, args, opts })
      return {
        stdin: new Writable({ write(_c, _e, cb) { cb() } }),
        stdout: new Readable({ read() {} }), stderr: new Readable({ read() {} }),
        on: () => {}, kill: () => {}, pid: 1234,
      } as any
    }
    spawnCodexAppServer({
      codexHome: "C:\\State", workdir: "C:\\Repo", authEnv: { Path: "C:\\Tools" },
      platform: "win32", fileExists: (p) => p.toLowerCase() === "c:\\tools\\codex.cmd",
      spawn: fakeSpawn,
    })
    expect(calls[0].cmd.toLowerCase()).toContain("cmd.exe")
    expect(calls[0].args.slice(0, 4)).toEqual(["/d", "/v:off", "/s", "/c"])
    expect(calls[0].opts.windowsVerbatimArguments).toBe(true)
  })
})
