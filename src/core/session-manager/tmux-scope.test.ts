import { describe, expect, test } from "bun:test"
import { createTmuxClient } from "./tmux"

// Mode A regression: a new session's tmux SERVER must be born in its own systemd
// scope (via `systemd-run --user --scope`), NOT as a plain broker child. The
// broker runs under mux.service with KillMode=control-group; a server in that
// cgroup is SIGKILLed on every broker restart/redeploy, taking every agent pane
// (and thus every session) down with it. Birthing the server in a sibling scope
// keeps it (and its panes) alive across broker restarts.

type Call = { cmd: string; args: string[] }

function fakes(opts: { hasSession: boolean; systemdRunThrows?: boolean; systemdRunCode?: number }) {
  const calls: Call[] = []
  const run = async (args: string[]) => {
    calls.push({ cmd: "tmux", args })
    if (args[0] === "has-session") return { code: opts.hasSession ? 0 : 1, stdout: "", stderr: "" }
    if (args[0] === "list-windows") return { code: 0, stdout: "@plain\tw\n", stderr: "" }
    return { code: 0, stdout: "@plain\n", stderr: "" }
  }
  const runRaw = async (cmd: string, args: string[]) => {
    calls.push({ cmd, args })
    if (cmd === "systemd-run" && opts.systemdRunThrows) throw new Error("spawn systemd-run ENOENT")
    if (cmd === "/bin/sh") return { code: 0, stdout: "", stderr: "" }
    const code = opts.systemdRunCode ?? 0
    return { code, stdout: code === 0 ? "@scoped\n" : "", stderr: code === 0 ? "" : "scope failed" }
  }
  return { calls, client: createTmuxClient(run, runRaw) }
}

describe("tmux server scope (survives broker restart)", () => {
  test("creating the first session births the server via systemd-run --scope", async () => {
    const { calls, client } = fakes({ hasSession: false })
    const { windowId } = await client.spawnSessionWindow({ session: "mux", window: "w", workdir: "/tmp", command: "cmd" })

    const sr = calls.find((c) => c.cmd === "systemd-run")
    expect(sr).toBeDefined()
    expect(sr!.args).toEqual(expect.arrayContaining(["--user", "--scope", "tmux", "new-session"]))
    expect(windowId).toBe("@scoped")
    // Must NOT also fire a plain `tmux new-session` (no double-spawn).
    expect(calls.some((c) => c.cmd === "tmux" && c.args[0] === "new-session")).toBe(false)
  })

  test("falls back to a detached tmux new-session when systemd-run is unavailable", async () => {
    const { calls, client } = fakes({ hasSession: false, systemdRunThrows: true })
    const { windowId } = await client.spawnSessionWindow({ session: "mux", window: "w", workdir: "/tmp", command: "cmd" })

    expect(calls.some((c) => c.cmd === "systemd-run")).toBe(true)
    const detached = calls.find((c) => c.cmd === "/bin/sh")
    expect(detached).toBeDefined()
    expect(detached!.args).toEqual(expect.arrayContaining(["tmux", "new-session"]))
    expect(windowId).toBe("@plain")
  })

  test("adding a window to an existing session does NOT use systemd-run", async () => {
    const { calls, client } = fakes({ hasSession: true })
    const { windowId } = await client.spawnSessionWindow({ session: "mux", window: "w2", workdir: "/tmp", command: "cmd" })

    expect(calls.some((c) => c.cmd === "systemd-run")).toBe(false)
    expect(calls.some((c) => c.cmd === "tmux" && c.args[0] === "new-window")).toBe(true)
    expect(windowId).toBe("@plain")
  })
})
