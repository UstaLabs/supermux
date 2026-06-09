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
})
