import { describe, expect, test } from "bun:test"
import { spawn } from "node:child_process"
import { claudeLoginSpawnCommand } from "./spawn-command"

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
