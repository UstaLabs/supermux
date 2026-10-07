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
  test("resolves the window-id, links it into a dedicated viewer, exec-attaches", () => {
    const argv = attachArgv({ device: "d", agentTarget: "@5" })
    expect(argv[0]).toBe("sh")
    expect(argv[1]).toBe("-c")
    const script = argv[2]!
    const viewer = viewerSessionName("d", "@5")
    // Normalize target → stable window-id, bail if gone.
    expect(script).toContain(`wid=$(tmux display-message -p -t '@5' '#{window_id}'`)
    expect(script).toContain(`[ -n "$wid" ] || exit 1`)
    // Dedicated (NOT grouped) viewer: no `new-session -t <agent>`.
    expect(script).not.toContain(`new-session -d -s '${viewer}' -t`)
    expect(script).toContain(`new-session -d -s '${viewer}' -n '_mux_ph'`)
    // Link the target window in by id — guarded so reconnect can't double-link.
    expect(script).toContain(`link-window -s "$wid" -t '${viewer}':`)
    expect(script).toContain(`list-windows -t '${viewer}' -F '#{window_id}'`)
    expect(script).toContain(`grep -qx "$wid"`)
    // Placeholder is dropped; the agent-address-by-index select-window is gone.
    expect(script).toContain(`kill-window -t '${viewer}':'_mux_ph'`)
    expect(script).not.toContain("window_index")
    expect(script).not.toContain("select-window")
    expect(script).toContain(`exec tmux attach -t '${viewer}'`)
    expect(script).not.toContain("window_name")
  })

  test("accepts the mux:<name> fallback target form", () => {
    const argv = attachArgv({ device: "d", agentTarget: "mux:my-sess" })
    const script = argv[2]!
    const viewer = viewerSessionName("d", "mux:my-sess")
    expect(script).toContain(`display-message -p -t 'mux:my-sess' '#{window_id}'`)
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

  test("enables mouse on the viewer so wheel/touch scroll reaches tmux history", () => {
    const argv = attachArgv({ device: "d", agentTarget: "@5" })
    const script = argv[2]!
    const viewer = viewerSessionName("d", "@5")
    // Agents run on the DEFAULT tmux server, where mouse is off. Without this the
    // web terminal's wheel-forwarding never engages (xterm sees mouseTrackingMode
    // 'none'), so touch scroll falls back to a no-op in tmux's alt-screen buffer.
    // Scoped to the grouped viewer session — the base `mux` session stays mouse-off.
    expect(script).toContain(`set-option -t '${viewer}' mouse on`)
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

  test("resizeWindow forces the linked agent window to the focused viewer size", async () => {
    const calls: string[][] = []
    const t = createAgentTmux({ run: async (a) => { calls.push(a); return { code: 0, stdout: "", stderr: "" } } })
    await t.resizeWindow("@5", 64, 28)
    expect(calls[0]).toEqual(["resize-window", "-t", "@5", "-x", "64", "-y", "28"])
  })

  test("restoreAutomaticSize returns the linked window to latest-client sizing", async () => {
    const calls: string[][] = []
    const t = createAgentTmux({ run: async (a) => { calls.push(a); return { code: 0, stdout: "", stderr: "" } } })
    await t.restoreAutomaticSize("@5")
    expect(calls[0]).toEqual(["set-window-option", "-t", "@5", "window-size", "latest"])
  })

})
