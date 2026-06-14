import { describe, test, expect } from "bun:test"
import { viewerSessionName, splitTarget, attachArgv, createAgentTmux } from "./agent-tmux"

describe("agent-tmux naming", () => {
  test("viewer name is prefixed + unique per (device, target)", () => {
    const n = viewerSessionName("phone", "mux:sess-1")
    expect(n.startsWith("muxview_")).toBe(true)
    expect(viewerSessionName("phone", "mux:sess-1")).not.toBe(viewerSessionName("laptop", "mux:sess-1"))
    expect(viewerSessionName("phone", "mux:sess-1")).not.toBe(viewerSessionName("phone", "mux:sess-2"))
  })

  test("splitTarget separates session and window", () => {
    expect(splitTarget("mux:sess-1")).toEqual({ session: "mux", window: "sess-1" })
    expect(splitTarget("mux:a:b")).toEqual({ session: "mux", window: "a:b" })
  })
})

describe("agent-tmux attachArgv", () => {
  test("builds `sh -c` that creates a grouped viewer, pins the window, exec-attaches", () => {
    const argv = attachArgv({ device: "d", agentTarget: "mux:sess-1" })
    expect(argv[0]).toBe("sh")
    expect(argv[1]).toBe("-c")
    const script = argv[2]!
    const viewer = viewerSessionName("d", "mux:sess-1")
    expect(script).toContain(`new-session -d -s '${viewer}' -t 'mux'`)
    expect(script).toContain(`select-window -t '${viewer}:sess-1'`)
    expect(script).toContain(`exec tmux attach -t '${viewer}'`)
  })
})

describe("agent-tmux control ops (mock runner)", () => {
  test("killViewer kills the viewer session, never the agent target", async () => {
    const calls: string[][] = []
    const t = createAgentTmux({ run: async (a) => { calls.push(a); return { code: 0, stdout: "", stderr: "" } } })
    await t.killViewer("d", "mux:sess-1")
    expect(calls[0]).toEqual(["kill-session", "-t", viewerSessionName("d", "mux:sess-1")])
    expect(calls.flat()).not.toContain("mux:sess-1")
  })

  test("hasAgentWindow reflects the has-session exit code", async () => {
    const yes = createAgentTmux({ run: async () => ({ code: 0, stdout: "", stderr: "" }) })
    const no = createAgentTmux({ run: async () => ({ code: 1, stdout: "", stderr: "" }) })
    expect(await yes.hasAgentWindow("mux:s")).toBe(true)
    expect(await no.hasAgentWindow("mux:s")).toBe(false)
  })
})
