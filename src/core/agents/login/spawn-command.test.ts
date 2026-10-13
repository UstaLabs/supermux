import { describe, expect, test } from "bun:test"
import { spawn } from "node:child_process"
import { existsSync, readdirSync } from "node:fs"
import { tmpdir } from "node:os"
import { claudeLoginSpawnCommand, loginSpawnCommands, ptyLoginSpawnCommand, shQuote } from "./spawn-command"
import { loginSpawnCommand as antigravityLoginSpawnCommand } from "../antigravity/auth"
import { resolveCommand } from "../../process/launcher"

describe("antigravity loginSpawnCommand", () => {
  test("runs agy print mode under a Linux pty, without GEMINI_API_KEY or a host browser, in a throwaway cwd", () => {
    const prior = { GEMINI_API_KEY: process.env.GEMINI_API_KEY, DISPLAY: process.env.DISPLAY, WAYLAND_DISPLAY: process.env.WAYLAND_DISPLAY }
    process.env.GEMINI_API_KEY = "leak"
    process.env.DISPLAY = ":0"
    process.env.WAYLAND_DISPLAY = "wayland-0"
    try {
      const spec = antigravityLoginSpawnCommand("linux")!
      expect(spec.env?.DISPLAY).toBeUndefined()
      expect(spec.env?.WAYLAND_DISPLAY).toBeUndefined()
      expect(spec.env?.BROWSER).toBe("true")
      expect(process.env.DISPLAY).toBe(":0")
      expect(spec.cwd).toBeDefined()
      expect(spec.cwd!.startsWith(tmpdir())).toBe(true)
      expect(readdirSync(spec.cwd!)).toEqual([])
      expect(spec.cwd).not.toBe(process.cwd())
      spec.cleanup!()
      expect(existsSync(spec.cwd!)).toBe(false)
      const path = resolveCommand(["agy"], spec.env!, "linux") ?? "agy"
      expect({ cmd: spec.cmd, args: spec.args }).toEqual({
        cmd: "script",
        args: ["-qec", `stty cols 600; exec ${shQuote(path)} '-p=Reply with the single word OK.'`, "/dev/null"],
      })
      expect(spec.env?.GEMINI_API_KEY).toBeUndefined()
    } finally {
      for (const [k, v] of Object.entries(prior)) {
        if (v === undefined) delete process.env[k]
        else process.env[k] = v
      }
    }
  })

  test("macOS uses the BSD script wrapper labelled for agy", () => {
    const spec = antigravityLoginSpawnCommand("darwin")!
    spec.cleanup?.()
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
