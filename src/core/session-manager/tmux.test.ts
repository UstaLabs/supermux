import { describe, expect, test } from "bun:test"
import { createTmuxClient } from "./tmux"

describe("tmux window IDs", () => {
  test("spawnSessionWindow returns tmux window id", async () => {
    const calls: string[][] = []
    const client = createTmuxClient(async (args) => {
      calls.push(args)
      if (args[0] === "has-session") return { code: 0, stdout: "", stderr: "" }
      return { code: 0, stdout: "@42\n", stderr: "" }
    })
    const result = await client.spawnSessionWindow({ session: "mux", window: "worker", workdir: "/tmp", command: "bash" })
    expect(result.windowId).toBe("@42")
    expect(calls.some((args) => args.includes("-P") && args.includes("#{window_id}"))).toBe(true)
  })

  test("livePanePid returns the live pane pid, and null for a dead or missing pane", async () => {
    const client = createTmuxClient(async (args) => {
      if (args[0] !== "list-panes") return { code: 0, stdout: "", stderr: "" }
      const target = args[args.indexOf("-t") + 1]
      if (target === "@alive") return { code: 0, stdout: "0 4242\n", stderr: "" }
      if (target === "@dead") return { code: 0, stdout: "1 4242\n", stderr: "" }  // pane_dead=1
      return { code: 1, stdout: "", stderr: "can't find window" }                  // gone
    })
    expect(await client.livePanePid("@alive")).toBe(4242)
    expect(await client.livePanePid("@dead")).toBe(null)
    expect(await client.livePanePid("@gone")).toBe(null)
  })
})
