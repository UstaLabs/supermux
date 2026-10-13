import { describe, expect, test } from "bun:test"
import { spawn } from "node:child_process"
import { claudeLoginSpawnCommand, loginSpawnCommands, ptyLoginSpawnCommand, shQuote } from "./spawn-command"
import { loginSpawnCommand as antigravityLoginSpawnCommand } from "../antigravity/auth"
import { resolveCommand } from "../../process/launcher"

describe("antigravity loginSpawnCommand", () => {
  test("runs agy print mode under a Linux pty, without GEMINI_API_KEY", () => {
    const prior = process.env.GEMINI_API_KEY
    process.env.GEMINI_API_KEY = "leak"
    try {
      const spec = antigravityLoginSpawnCommand("linux")!
      const path = resolveCommand(["agy"], spec.env!, "linux") ?? "agy"
      expect({ cmd: spec.cmd, args: spec.args }).toEqual({
        cmd: "script",
        args: ["-qec", `stty cols 600; exec ${shQuote(path)} '-p=Reply with the single word OK.'`, "/dev/null"],
      })
      expect(spec.env?.GEMINI_API_KEY).toBeUndefined()
    } finally {
      if (prior === undefined) delete process.env.GEMINI_API_KEY
      else process.env.GEMINI_API_KEY = prior
    }
  })

  test("macOS uses the BSD script wrapper labelled for agy", () => {
    const spec = antigravityLoginSpawnCommand("darwin")!
    expect(spec.cmd).toBe("/bin/sh")
    expect(spec.args[1]).toContain("supermux-agy-script")
    expect(spec.args[2]).toBe("supermux-agy-login")
    expect(spec.detached).toBe(true)
  })

  test("Windows has no pty: no system login command", () => {
    expect(antigravityLoginSpawnCommand("win32")).toBeNull()
  })

  test("is the antigravity entry of the per-kind table", () => {
    expect(typeof loginSpawnCommands.antigravity).toBe("function")
  })
})

describe("shQuote / ptyLoginSpawnCommand", () => {
  test("single-quotes a shell word, escaping embedded quotes", () => {
    expect(shQuote("/opt/a b/agy")).toBe("'/opt/a b/agy'")
    expect(shQuote("it's")).toBe("'it'\\''s'")
  })

  test("linux wraps any command in util-linux script", () => {
    expect(ptyLoginSpawnCommand("echo hi", "x", "linux")).toEqual({ cmd: "script", args: ["-qec", "echo hi", "/dev/null"] })
  })
})

describe("claudeLoginSpawnCommand", () => {
  test("uses BSD script syntax on macOS", () => {
    expect(claudeLoginSpawnCommand("darwin")).toEqual({
      cmd: "/bin/sh",
      args: [
        "-c",
        `cat | /bin/sh -c 'trap "/usr/bin/pkill -TERM -P \\"$PPID\\" -x cat 2>/dev/null || true" EXIT; /usr/bin/script -q /dev/null /bin/sh -c "$1"' supermux-claude-script "$1"`,
        "supermux-claude-login",
        "stty cols 600; exec claude auth login",
      ],
      detached: true,
    })
  })

  test("uses util-linux script syntax on Linux", () => {
    expect(claudeLoginSpawnCommand("linux")).toEqual({
      cmd: "script",
      args: ["-qec", "stty cols 600; exec claude auth login", "/dev/null"],
    })
  })

  test.skipIf(process.platform !== "darwin")("macOS wrapper exits when the PTY command exits while stdin remains open", async () => {
    const spec = claudeLoginSpawnCommand("darwin")
    const args = [...spec.args]
    args[3] = "printf 'child-done\\n'; exit 7"
    const child = spawn(spec.cmd, args, { detached: true, stdio: ["pipe", "pipe", "pipe"] })
    child.stdout.resume()
    child.stderr.resume()

    let timeout: ReturnType<typeof setTimeout> | undefined
    try {
      const result = await Promise.race([
        new Promise<{ code: number | null; signal: NodeJS.Signals | null }>((resolve) => {
          child.once("exit", (code, signal) => resolve({ code, signal }))
        }),
        new Promise<never>((_, reject) => {
          timeout = setTimeout(() => reject(new Error("macOS Claude login wrapper did not exit")), 2_000)
        }),
      ])
      expect(result).toEqual({ code: 7, signal: null })
    } finally {
      if (timeout) clearTimeout(timeout)
      try {
        if (child.pid) process.kill(-child.pid, "SIGKILL")
      } catch {}
    }
  })
})
