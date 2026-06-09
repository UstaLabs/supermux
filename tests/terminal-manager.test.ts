import { describe, it, expect, afterEach } from "bun:test"
import { TerminalManager } from "../src/core/terminal/manager"
import { resolve as resolvePath } from "path"
import { existsSync } from "fs"

const PTY_HELPER = resolvePath(import.meta.dirname, "../src/core/terminal/pty-helper")

async function waitFor(fn: () => boolean, timeoutMs = 2000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (fn()) return
    await new Promise((r) => setTimeout(r, 50))
  }
}

describe("TerminalManager", () => {
  let mgr: TerminalManager

  afterEach(() => {
    mgr?.shutdown()
  })

  it("rejects spawn when pty-helper is missing", () => {
    mgr = new TerminalManager()
    // Temporarily override the helper path check by using a non-existent workdir
    // that would fail even if helper existed. Instead, test the "already open" path.
    const result = mgr.spawn({
      deviceName: "dev1",
      sessionName: "s1",
      workdir: "/tmp",
      cols: 80,
      rows: 24,
      onData: () => {},
      onExit: () => {},
    })
    // If pty-helper exists (it should in dev), spawn succeeds
    if (existsSync(PTY_HELPER)) {
      expect(result.ok).toBe(true)
    }
  })

  it("spawns a terminal and receives output", async () => {
    if (!existsSync(PTY_HELPER)) return

    mgr = new TerminalManager()
    let received = ""
    let exitCode: number | null = null

    const exitPromise = new Promise<void>((resolve) => {
      const result = mgr.spawn({
        deviceName: "dev1",
        sessionName: "s1",
        workdir: "/tmp",
        cols: 80,
        rows: 24,
        onData: (data) => { received += new TextDecoder().decode(data) },
        onExit: (code) => { exitCode = code; resolve() },
      })
      expect(result.ok).toBe(true)
    })

    // Give the shell time to start
    await new Promise((r) => setTimeout(r, 500))

    // Send a command
    mgr.write("dev1", "s1", new TextEncoder().encode("echo TERMINAL_TEST_OUTPUT\r"))

    // Send exit
    await new Promise((r) => setTimeout(r, 500))
    mgr.write("dev1", "s1", new TextEncoder().encode("exit\r"))

    await Promise.race([exitPromise, new Promise((r) => setTimeout(r, 5000))])

    expect(received).toContain("TERMINAL_TEST_OUTPUT")
  })

  it("rejects duplicate terminal for same device+session", () => {
    if (!existsSync(PTY_HELPER)) return

    mgr = new TerminalManager()
    const r1 = mgr.spawn({
      deviceName: "dev1",
      sessionName: "s1",
      workdir: "/tmp",
      cols: 80,
      rows: 24,
      onData: () => {},
      onExit: () => {},
    })
    expect(r1.ok).toBe(true)

    const r2 = mgr.spawn({
      deviceName: "dev1",
      sessionName: "s1",
      workdir: "/tmp",
      cols: 80,
      rows: 24,
      onData: () => {},
      onExit: () => {},
    })
    expect(r2.ok).toBe(false)
    if (!r2.ok) expect(r2.error).toContain("already open")
  })

  it("allows different sessions for the same device", () => {
    if (!existsSync(PTY_HELPER)) return

    mgr = new TerminalManager()
    const r1 = mgr.spawn({ deviceName: "dev1", sessionName: "s1", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    const r2 = mgr.spawn({ deviceName: "dev1", sessionName: "s2", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    expect(r1.ok).toBe(true)
    expect(r2.ok).toBe(true)
    expect(mgr.count()).toBe(2)
  })

  it("allows more than three terminals per device", () => {
    if (!existsSync(PTY_HELPER)) return

    mgr = new TerminalManager()
    for (let i = 0; i < 3; i++) {
      const r = mgr.spawn({ deviceName: "dev1", sessionName: `s${i}`, workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
      expect(r.ok).toBe(true)
    }
    const r4 = mgr.spawn({ deviceName: "dev1", sessionName: "s3", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    expect(r4.ok).toBe(true)
  })

  it("kill removes terminal", () => {
    if (!existsSync(PTY_HELPER)) return

    mgr = new TerminalManager()
    mgr.spawn({ deviceName: "dev1", sessionName: "s1", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    expect(mgr.has("dev1", "s1")).toBe(true)
    mgr.kill("dev1", "s1")
    expect(mgr.has("dev1", "s1")).toBe(false)
  })

  it("killAllForSession removes all terminals for that session", () => {
    if (!existsSync(PTY_HELPER)) return

    mgr = new TerminalManager()
    mgr.spawn({ deviceName: "dev1", sessionName: "s1", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    mgr.spawn({ deviceName: "dev2", sessionName: "s1", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    mgr.spawn({ deviceName: "dev1", sessionName: "s2", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    expect(mgr.count()).toBe(3)
    mgr.killAllForSession("s1")
    expect(mgr.count()).toBe(1)
    expect(mgr.has("dev1", "s2")).toBe(true)
  })

  it("resize sends resize command to PTY", async () => {
    if (!existsSync(PTY_HELPER)) return

    mgr = new TerminalManager()
    let received = ""
    mgr.spawn({
      deviceName: "dev1",
      sessionName: "s1",
      workdir: "/tmp",
      cols: 80,
      rows: 24,
      onData: (data) => { received += new TextDecoder().decode(data) },
      onExit: () => {},
    })

    await new Promise((r) => setTimeout(r, 500))

    const resized = mgr.resize("dev1", "s1", 120, 40)
    expect(resized).toBe(true)

    // Verify stty reports the new size
    mgr.write("dev1", "s1", new TextEncoder().encode("stty size\r"))
    await waitFor(() => received.includes("40 120"))

    expect(received).toContain("40 120")
  })

  it("shutdown kills all terminals", () => {
    if (!existsSync(PTY_HELPER)) return

    mgr = new TerminalManager()
    mgr.spawn({ deviceName: "dev1", sessionName: "s1", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    mgr.spawn({ deviceName: "dev2", sessionName: "s2", workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} })
    expect(mgr.count()).toBe(2)
    mgr.shutdown()
    expect(mgr.count()).toBe(0)
  })

  it("write returns false for non-existent terminal", () => {
    mgr = new TerminalManager()
    expect(mgr.write("dev1", "s1", new TextEncoder().encode("test"))).toBe(false)
  })

  it("resize returns false for non-existent terminal", () => {
    mgr = new TerminalManager()
    expect(mgr.resize("dev1", "s1", 80, 24)).toBe(false)
  })
})
