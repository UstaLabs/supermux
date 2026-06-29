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

  test("resolveWindowIdByName returns the matching window id", async () => {
    const calls: string[][] = []
    const client = createTmuxClient(async (args) => {
      calls.push(args)
      if (args[0] === "list-windows") return { code: 0, stdout: "@1\tother\n@7\tMy Session\n", stderr: "" }
      return { code: 0, stdout: "", stderr: "" }
    })
    const id = await client.resolveWindowIdByName("mysession", "My Session")
    expect(id).toBe("@7")
    expect(calls.some((args) => args[0] === "list-windows" && args.includes("mysession"))).toBe(true)
  })

  test("resolveWindowIdByName returns null when no name matches", async () => {
    const client = createTmuxClient(async (args) => {
      if (args[0] === "list-windows") return { code: 0, stdout: "@1\tother\n@7\tMy Session\n", stderr: "" }
      return { code: 0, stdout: "", stderr: "" }
    })
    const id = await client.resolveWindowIdByName("mysession", "nonexistent")
    expect(id).toBe(null)
  })

  test("capturePaneById returns pane text and issues capture-pane with the window id", async () => {
    const calls: string[][] = []
    const client = createTmuxClient(async (args) => {
      calls.push(args)
      if (args[0] === "capture-pane") return { code: 0, stdout: "hello from pane\n", stderr: "" }
      return { code: 0, stdout: "", stderr: "" }
    })
    const text = await client.capturePaneById("@7")
    expect(text).toBe("hello from pane\n")
    expect(calls.some((args) => args[0] === "capture-pane" && args.includes("@7"))).toBe(true)
  })

  test("capturePaneById returns null when capture-pane fails", async () => {
    const client = createTmuxClient(async (args) => {
      if (args[0] === "capture-pane") return { code: 1, stdout: "", stderr: "can't find window" }
      return { code: 0, stdout: "", stderr: "" }
    })
    expect(await client.capturePaneById("@gone")).toBe(null)
  })

  test("resolveWindowIdByName returns null when list-windows fails", async () => {
    const client = createTmuxClient(async (args) => {
      if (args[0] === "list-windows") return { code: 1, stdout: "", stderr: "can't find session" }
      return { code: 0, stdout: "", stderr: "" }
    })
    expect(await client.resolveWindowIdByName("nosession", "whatever")).toBe(null)
  })
})
