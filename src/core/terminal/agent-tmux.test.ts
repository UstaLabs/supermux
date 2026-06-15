import { describe, test, expect } from "bun:test"
import { viewerSessionName, attachArgv, createAgentTmux } from "./agent-tmux"

describe("agent-tmux naming", () => {
  test("viewer name is prefixed + unique per (device, target)", () => {
    const n = viewerSessionName("phone", "mux:sess-1")
    expect(n.startsWith("muxview_")).toBe(true)
    expect(viewerSessionName("phone", "mux:sess-1")).not.toBe(viewerSessionName("laptop", "mux:sess-1"))
    expect(viewerSessionName("phone", "mux:sess-1")).not.toBe(viewerSessionName("phone", "mux:sess-2"))
  })
})

describe("agent-tmux attachArgv", () => {
  test("resolves the window by id (display-message), groups a viewer, exec-attaches", () => {
    const argv = attachArgv({ device: "d", agentTarget: "@5" })
    expect(argv[0]).toBe("sh")
    expect(argv[1]).toBe("-c")
    const script = argv[2]!
    const viewer = viewerSessionName("d", "@5")
    expect(script).toContain(`display-message -p -t '@5' '#{session_name}'`)
    expect(script).toContain(`display-message -p -t '@5' '#{window_index}'`)
    expect(script).toContain(`new-session -d -s '${viewer}' -t "$s"`)
    expect(script).toContain(`select-window -t '${viewer}':"$w"`)
    expect(script).toContain(`[ -n "$s" ] && [ -n "$w" ] || exit 1`)
    expect(script).toContain(`exec tmux attach -t '${viewer}'`)
    expect(script).not.toContain("window_name")
  })

  test("accepts the mux:<name> fallback target form", () => {
    const argv = attachArgv({ device: "d", agentTarget: "mux:my-sess" })
    const script = argv[2]!
    const viewer = viewerSessionName("d", "mux:my-sess")
    expect(script).toContain(`display-message -p -t 'mux:my-sess' '#{session_name}'`)
    expect(script).toContain(`exec tmux attach -t '${viewer}'`)
  })

  test("shell-quotes single quotes in the target (no injection)", () => {
    const argv = attachArgv({ device: "d", agentTarget: "x'y" })
    // POSIX escape: x'y -> 'x'\''y'  (the quote can't terminate the string early)
    expect(argv[2]!).toContain("'x'\\''y'")
  })

  test("turns the viewer session's status bar off", () => {
    const argv = attachArgv({ device: "d", agentTarget: "@5" })
    const script = argv[2]!
    const viewer = viewerSessionName("d", "@5")
    expect(script).toContain(`set-option -t '${viewer}' status off`)
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

})
