import { describe, it, expect, afterEach } from "bun:test"
import { spawnSync } from "child_process"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { resolve as resolvePath, join } from "path"
import { existsSync } from "fs"
import { TerminalManager } from "../src/core/terminal/manager"

const PTY_HELPER = resolvePath(import.meta.dirname, "../src/core/terminal/pty-helper")
const TMUX = Bun.which("tmux")
const CAN_RUN = !!TMUX && existsSync(PTY_HELPER)

async function waitFor(fn: () => boolean | Promise<boolean>, timeoutMs = 4000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await fn()) return
    await new Promise((r) => setTimeout(r, 50))
  }
}

describe("TerminalManager (tmux-backed)", () => {
  let mgr: TerminalManager | undefined
  const sockets: string[] = []
  let n = 0

  function makeMgr(): { mgr: TerminalManager; socket: string } {
    const socket = `muxterm-test-${process.pid}-${n++}`
    sockets.push(socket)
    const stateDir = mkdtempSync(join(tmpdir(), "muxterm-test-"))
    const m = new TerminalManager({ stateDir, socket })
    return { mgr: m, socket }
  }

  afterEach(() => {
    mgr?.shutdown()
    mgr = undefined
    // Tear down any tmux servers this test spun up so sessions don't leak.
    for (const s of sockets.splice(0)) {
      spawnSync("tmux", ["-L", s, "kill-server"], { stdio: "ignore" })
    }
  })

  it("attaches a terminal whose tmux session outlives the viewer (detach)", async () => {
    if (!CAN_RUN) return
    const made = makeMgr(); mgr = made.mgr

    const r = mgr.attach({ deviceName: "dev1", sessionName: "s1", terminalId: "t1", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    expect(r.ok).toBe(true)
    expect(mgr.has("dev1", "s1", "t1")).toBe(true)

    // tmux session comes up shortly after the viewer spawns.
    await waitFor(() => mgr!.hasSession("s1", "t1"))
    expect(await mgr.hasSession("s1", "t1")).toBe(true)

    // Detach = kill the viewer only. The tmux session must survive.
    mgr.detach("dev1", "s1", "t1")
    expect(mgr.has("dev1", "s1", "t1")).toBe(false)
    expect(await mgr.hasSession("s1", "t1")).toBe(true)

    // Re-attach to the SAME terminal id reuses the surviving session.
    const r2 = mgr.attach({ deviceName: "dev1", sessionName: "s1", terminalId: "t1", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    expect(r2.ok).toBe(true)
    expect(mgr.has("dev1", "s1", "t1")).toBe(true)
  })

  it("relays shell output through tmux", async () => {
    if (!CAN_RUN) return
    const made = makeMgr(); mgr = made.mgr
    let received = ""
    mgr.attach({ deviceName: "dev1", sessionName: "s1", terminalId: "t1", workdir: "/tmp", cols: 80, rows: 24, onData: (d) => { received += new TextDecoder().decode(d) }, onExit: () => {} })
    await waitFor(() => mgr!.hasSession("s1", "t1"))
    await new Promise((r) => setTimeout(r, 400)) // let the shell prompt settle
    mgr.write("dev1", "s1", "t1", new TextEncoder().encode("echo TERMINAL_TMUX_OK\n"))
    await waitFor(() => received.includes("TERMINAL_TMUX_OK"))
    expect(received).toContain("TERMINAL_TMUX_OK")
  })

  it("close destroys the tmux session", async () => {
    if (!CAN_RUN) return
    const made = makeMgr(); mgr = made.mgr
    mgr.attach({ deviceName: "dev1", sessionName: "s1", terminalId: "t1", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    await waitFor(() => mgr!.hasSession("s1", "t1"))

    await mgr.close("s1", "t1")
    expect(mgr.has("dev1", "s1", "t1")).toBe(false)
    expect(await mgr.hasSession("s1", "t1")).toBe(false)
  })

  it("supports multiple terminals per session and lists them", async () => {
    if (!CAN_RUN) return
    const made = makeMgr(); mgr = made.mgr
    mgr.attach({ deviceName: "dev1", sessionName: "s1", terminalId: "t1", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    mgr.attach({ deviceName: "dev1", sessionName: "s1", terminalId: "t2", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    await waitFor(async () => (await mgr!.listForSession("s1")).length === 2)
    const list = await mgr.listForSession("s1")
    expect(list.map((l) => l.id).sort()).toEqual(["t1", "t2"])
  })

  it("killAllForSession removes all of a session's terminals", async () => {
    if (!CAN_RUN) return
    const made = makeMgr(); mgr = made.mgr
    mgr.attach({ deviceName: "dev1", sessionName: "s1", terminalId: "t1", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    mgr.attach({ deviceName: "dev1", sessionName: "s2", terminalId: "t1", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    await waitFor(() => mgr!.hasSession("s1", "t1"))
    await waitFor(() => mgr!.hasSession("s2", "t1"))

    await mgr.killAllForSession("s1")
    expect(await mgr.hasSession("s1", "t1")).toBe(false)
    expect(await mgr.hasSession("s2", "t1")).toBe(true) // other session untouched
  })

  it("write/resize return false for a non-existent terminal", () => {
    const made = makeMgr(); mgr = made.mgr
    expect(mgr.write("dev1", "s1", "t1", new TextEncoder().encode("x"))).toBe(false)
    expect(mgr.resize("dev1", "s1", "t1", 80, 24)).toBe(false)
  })
})
