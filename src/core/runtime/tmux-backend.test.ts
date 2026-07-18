import { describe, expect, test } from "bun:test"
import { spawnSync } from "node:child_process"
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createTmuxSessionBackend } from "./tmux-backend"

type Result = { code: number; stdout: string; stderr: string }

function createFakeTmux() {
  const calls: Array<{ method: string; args: unknown[] }> = []
  const record = <T>(method: string, result: T) => async (...args: unknown[]): Promise<T> => {
    calls.push({ method, args })
    return result
  }
  return {
    calls,
    client: {
      spawnSessionWindow: record("spawnSessionWindow", { windowId: "@42" }),
      killWindowById: record("killWindowById", undefined),
      listSessionWindows: record("listSessionWindows", ["worker"]),
      livePanePid: record("livePanePid", 4242),
      sendKeysToWindowId: record("sendKeysToWindowId", undefined),
      capturePaneById: record("capturePaneById", "plain output"),
      capturePaneRawById: record("capturePaneRawById", "\u001b[2mraw output"),
      resolveWindowIdByName: record("resolveWindowIdByName", "@42"),
    },
  }
}

describe("tmux session backend", () => {
  test("renders argv and env through a POSIX login shell", async () => {
    const fake = createFakeTmux()
    const backend = createTmuxSessionBackend({ tmux: fake.client })

    await backend.create({
      group: "mux",
      name: "worker",
      cwd: "/tmp/project",
      argv: ["agent", "$(touch /tmp/pwn); 'quoted'", "line\nbreak"],
      env: { SAFE: "hello world", DANGER: "$(touch /tmp/env-pwn)" },
    })

    expect(fake.calls[0]?.method).toBe("spawnSessionWindow")
    const spawn = fake.calls[0]?.args[0] as { session: string; window: string; workdir: string; command: string }
    expect({ ...spawn, command: undefined }).toEqual({
      session: "mux",
      window: "worker",
      workdir: "/tmp/project",
      command: undefined,
    })
    expect(spawn.command.startsWith("bash -lc ")).toBe(true)
  })

  test("preserves exact argv and env without shell injection", async () => {
    const root = mkdtempSync(join(tmpdir(), "mux-posix-command-"))
    try {
      const captureScript = join(root, "capture.mjs")
      const capturePath = join(root, "capture.json")
      const injectedFromArg = join(root, "arg-injected")
      const injectedFromEnv = join(root, "env-injected")
      writeFileSync(captureScript, [
        'import { writeFileSync } from "node:fs"',
        'writeFileSync(process.env.CAPTURE_PATH, JSON.stringify({ argv: process.argv.slice(2), sample: process.env.SAMPLE }))',
      ].join("\n"))
      const argv = [
        process.execPath,
        captureScript,
        "space separated",
        "single ' quote",
        "line\nbreak",
        `$(touch ${injectedFromArg}); touch ${injectedFromArg}`,
      ]
      const sample = `env ' value\n$(touch ${injectedFromEnv}); touch ${injectedFromEnv}`
      const fake = createFakeTmux()
      const backend = createTmuxSessionBackend({ tmux: fake.client })
      await backend.create({
        group: "mux",
        name: "worker",
        cwd: root,
        argv,
        env: { CAPTURE_PATH: capturePath, SAMPLE: sample },
      })
      const spawn = fake.calls[0]?.args[0] as { command: string }

      const executed = spawnSync("/bin/sh", ["-c", spawn.command], { encoding: "utf8" })

      expect(executed.status).toBe(0)
      expect(executed.stderr).toBe("")
      expect(JSON.parse(readFileSync(capturePath, "utf8"))).toEqual({ argv: argv.slice(2), sample })
      expect(existsSync(injectedFromArg)).toBe(false)
      expect(existsSync(injectedFromEnv)).toBe(false)
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  })

  test("rejects non-portable environment names before invoking tmux", async () => {
    const fake = createFakeTmux()
    const backend = createTmuxSessionBackend({ tmux: fake.client })

    await expect(backend.create({
      group: "mux",
      name: "worker",
      cwd: "/tmp/project",
      argv: ["agent"],
      env: { "-i": "option injection" },
    })).rejects.toThrow("invalid environment variable name: -i")
    expect(fake.calls).toEqual([])
  })

  test("maps target operations to the existing tmux client and command seam", async () => {
    const fake = createFakeTmux()
    const commands: Array<{ args: string[]; input?: number[] }> = []
    const runTmux = async (args: string[], input?: Uint8Array): Promise<Result> => {
      commands.push({ args, input: input ? [...input] : undefined })
      return { code: 0, stdout: "", stderr: "" }
    }
    const backend = createTmuxSessionBackend({ tmux: fake.client, runTmux, defaultGroup: "mux" })

    expect(await backend.create({ group: "mux", name: "worker", cwd: "/tmp", argv: ["agent"], env: {} }))
      .toEqual({ id: "@42", name: "worker", pid: 4242, alive: true })
    expect(await backend.list()).toEqual([{ id: "@42", name: "worker", pid: 4242, alive: true }])
    expect(await backend.resolve("mux", "worker")).toBe("@42")
    expect(await backend.livePid("@42")).toBe(4242)
    await backend.sendKeys("@42", ["Enter"])
    expect(await backend.capture("@42")).toBe("plain output")
    expect(await backend.capture("@42", true)).toBe("\u001b[2mraw output")
    await backend.resize("@42", 120, 40)
    await backend.interrupt("@42")
    await backend.kill("@42")

    expect(commands).toContainEqual({ args: ["resize-window", "-t", "@42", "-x", "120", "-y", "40"], input: undefined })
    expect(fake.calls).toContainEqual({ method: "sendKeysToWindowId", args: ["@42", ["Enter"]] })
    expect(fake.calls).toContainEqual({ method: "sendKeysToWindowId", args: ["@42", ["C-c"]] })
    expect(fake.calls).toContainEqual({ method: "killWindowById", args: ["@42"] })
  })

  test("writes bytes through a private tmux buffer and pastes by window id", async () => {
    const fake = createFakeTmux()
    const commands: Array<{ args: string[]; input?: number[] }> = []
    const runTmux = async (args: string[], input?: Uint8Array): Promise<Result> => {
      commands.push({ args, input: input ? [...input] : undefined })
      return { code: 0, stdout: "", stderr: "" }
    }
    const backend = createTmuxSessionBackend({ tmux: fake.client, runTmux, bufferId: () => "runtime-test" })

    await backend.write("@42", new Uint8Array([0, 39, 10, 255]))

    expect(commands).toEqual([
      { args: ["load-buffer", "-b", "runtime-test", "-"], input: [0, 39, 10, 255] },
      { args: ["paste-buffer", "-d", "-b", "runtime-test", "-t", "@42"], input: undefined },
      { args: ["delete-buffer", "-b", "runtime-test"], input: undefined },
    ])
  })

  test("deletes the private buffer after paste failure and preserves the paste error", async () => {
    const fake = createFakeTmux()
    const commands: string[][] = []
    const runTmux = async (args: string[]): Promise<Result> => {
      commands.push(args)
      if (args[0] === "paste-buffer") return { code: 1, stdout: "", stderr: "target gone" }
      if (args[0] === "delete-buffer") throw new Error("tmux server disappeared")
      return { code: 0, stdout: "", stderr: "" }
    }
    const backend = createTmuxSessionBackend({ tmux: fake.client, runTmux, bufferId: () => "sensitive" })

    await expect(backend.write("@gone", new TextEncoder().encode("secret")))
      .rejects.toThrow("tmux paste-buffer failed: target gone")
    expect(commands).toEqual([
      ["load-buffer", "-b", "sensitive", "-"],
      ["paste-buffer", "-d", "-b", "sensitive", "-t", "@gone"],
      ["delete-buffer", "-b", "sensitive"],
    ])
  })

  test("keeps viewer attachment owned by TerminalManager", async () => {
    const fake = createFakeTmux()
    const backend = createTmuxSessionBackend({ tmux: fake.client })

    await expect(backend.attach("@42", "viewer", () => {}))
      .rejects.toThrow("tmux backend viewer attachment is owned by TerminalManager")
  })
})
