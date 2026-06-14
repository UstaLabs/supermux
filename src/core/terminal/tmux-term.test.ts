import { describe, expect, test } from "bun:test"
import {
  createTermTmux,
  tmuxSessionName,
  parseTerminalId,
  attachArgv,
} from "./tmux-term"

describe("tmux-term naming", () => {
  test("session name is prefixed, hex-encoded, and reversible", () => {
    const name = tmuxSessionName("sess.1", "abc123")
    expect(name.startsWith("muxterm_")).toBe(true)
    expect(name.endsWith("_abc123")).toBe(true)
    expect(parseTerminalId("sess.1", name)).toBe("abc123")
  })

  test("parseTerminalId rejects names from other sessions", () => {
    const name = tmuxSessionName("sessA", "t1")
    expect(parseTerminalId("sessB", name)).toBeNull()
    expect(parseTerminalId("sessA", "totally-unrelated")).toBeNull()
  })

  test("hex-encoding prevents prefix collisions between sessions", () => {
    // Without hex, session "a" + id "b_t1" could be confused with session
    // "a_b" + id "t1". Hex-encoding the session keeps the boundary unambiguous.
    expect(parseTerminalId("a", tmuxSessionName("a_b", "t1"))).toBeNull()
  })

  test("attachArgv builds `tmux -L … -f … new-session -A` with size + workdir", () => {
    const argv = attachArgv({ agentSession: "s", terminalId: "t1", workdir: "/w", cols: 100, rows: 40, confPath: "/c" })
    expect(argv.slice(0, 5)).toEqual(["tmux", "-L", "muxterm", "-f", "/c"])
    expect(argv).toContain("new-session")
    expect(argv).toContain("-A")
    expect(argv[argv.indexOf("-s") + 1]).toBe(tmuxSessionName("s", "t1"))
    expect(argv[argv.indexOf("-x") + 1]).toBe("100")
    expect(argv[argv.indexOf("-y") + 1]).toBe("40")
    expect(argv[argv.indexOf("-c") + 1]).toBe("/w")
  })
})

describe("tmux-term control ops (mock runner)", () => {
  test("listTerminals keeps only this session's sessions, sorted by createdAt", async () => {
    const t = createTermTmux({
      confPath: "/c",
      run: async () => ({
        code: 0,
        stdout:
          `${tmuxSessionName("mine", "t2")}\t100\n` +
          `${tmuxSessionName("mine", "t1")}\t50\n` +
          `${tmuxSessionName("other", "x")}\t10\n`,
        stderr: "",
      }),
    })
    const list = await t.listTerminals("mine")
    expect(list.map((l) => l.id)).toEqual(["t1", "t2"]) // sorted by createdAt asc
    expect(list[0]!.createdAt).toBe(50_000) // seconds → ms
  })

  test("listTerminals returns [] when no server is running", async () => {
    const t = createTermTmux({
      confPath: "/c",
      run: async () => ({ code: 1, stdout: "", stderr: "no server running on /tmp/tmux" }),
    })
    expect(await t.listTerminals("mine")).toEqual([])
  })

  test("killTerminal targets the right tmux session", async () => {
    const calls: string[][] = []
    const t = createTermTmux({ confPath: "/c", run: async (args) => { calls.push(args); return { code: 0, stdout: "", stderr: "" } } })
    await t.killTerminal("mine", "t1")
    expect(calls[0]).toEqual(["kill-session", "-t", tmuxSessionName("mine", "t1")])
  })

  test("killAllTerminals lists then kills each of this session's terminals", async () => {
    const calls: string[][] = []
    const t = createTermTmux({
      confPath: "/c",
      run: async (args) => {
        calls.push(args)
        if (args[0] === "list-sessions") {
          return { code: 0, stdout: `${tmuxSessionName("mine", "t1")}\t1\n${tmuxSessionName("mine", "t2")}\t2\n`, stderr: "" }
        }
        return { code: 0, stdout: "", stderr: "" }
      },
    })
    await t.killAllTerminals("mine")
    const kills = calls.filter((c) => c[0] === "kill-session").map((c) => c[2])
    expect(kills.sort()).toEqual([tmuxSessionName("mine", "t1"), tmuxSessionName("mine", "t2")].sort())
  })

  test("hasTerminal reflects the tmux exit code", async () => {
    const present = createTermTmux({ confPath: "/c", run: async () => ({ code: 0, stdout: "", stderr: "" }) })
    const absent = createTermTmux({ confPath: "/c", run: async () => ({ code: 1, stdout: "", stderr: "can't find session" }) })
    expect(await present.hasTerminal("mine", "t1")).toBe(true)
    expect(await absent.hasTerminal("mine", "t1")).toBe(false)
  })
})
