import { describe, it, expect } from "bun:test"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { TerminalManager, type TermProc, type SpawnFn } from "../src/core/terminal/manager"
import type { TmuxRunner } from "../src/core/terminal/tmux-term"

// Hermetic: a fake subprocess + a fake tmux "world" sharing in-memory state, so
// these tests spawn NOTHING real (no tmux, no shell, no pty-helper) and can't
// hang the runner on live process handles. Real tmux persistence is covered by
// the manual smoke test / e2e, not the unit suite.

const STATE = mkdtempSync(join(tmpdir(), "muxterm-test-"))
const flush = () => new Promise((r) => setTimeout(r, 0))

interface FakeProc extends TermProc {
  writes: Uint8Array[]
  emit(s: string): void
  end(code: number): void
}

function makeFakeProc(): FakeProc {
  let ctrl!: ReadableStreamDefaultController<Uint8Array>
  const stdout = new ReadableStream<Uint8Array>({ start(c) { ctrl = c } })
  let resolve!: (n: number) => void
  const exited = new Promise<number>((r) => { resolve = r })
  let done = false
  const finish = (code: number) => { if (done) return; done = true; try { ctrl.close() } catch {} ; resolve(code) }
  const writes: Uint8Array[] = []
  return {
    pid: 4242,
    writes,
    stdin: { write: (d) => { writes.push(typeof d === "string" ? new TextEncoder().encode(d) : d) } },
    stdout,
    exited,
    kill: () => finish(143),
    emit: (s) => { try { ctrl.enqueue(new TextEncoder().encode(s)) } catch {} },
    end: (code) => finish(code),
  }
}

/** In-memory stand-in for the muxterm tmux server: the fake spawn "creates" a
 * session (mirroring `tmux new-session -A`) and the fake runner queries it. */
function fakeTmuxWorld() {
  const sessions = new Map<string, number>()
  const procs: FakeProc[] = []
  let clock = 100
  const run: TmuxRunner = async (args) => {
    const cmd = args[0]
    const targetOf = () => args[args.indexOf("-t") + 1] ?? ""
    if (cmd === "list-sessions") {
      const stdout = [...sessions].map(([n, c]) => `${n}\t${c}`).join("\n")
      return { code: sessions.size ? 0 : 1, stdout, stderr: sessions.size ? "" : "no server running" }
    }
    if (cmd === "has-session") return { code: sessions.has(targetOf()) ? 0 : 1, stdout: "", stderr: "" }
    if (cmd === "kill-session") { sessions.delete(targetOf()); return { code: 0, stdout: "", stderr: "" } }
    return { code: 0, stdout: "", stderr: "" }
  }
  const spawn: SpawnFn = (cmd) => {
    if (cmd.includes("new-session")) {
      const name = cmd[cmd.indexOf("-s") + 1]!
      if (!sessions.has(name)) sessions.set(name, clock++)
    }
    const p = makeFakeProc()
    procs.push(p)
    return p
  }
  return { sessions, procs, run, spawn }
}

function makeMgr(world = fakeTmuxWorld()) {
  const mgr = new TerminalManager({ stateDir: STATE, socket: "test", run: world.run, spawn: world.spawn })
  return { mgr, world }
}

const baseAttach = { workdir: "/tmp", cols: 80, rows: 24, onData: () => {}, onExit: () => {} }

describe("TerminalManager (hermetic)", () => {
  it("attach creates a tmux session; detach keeps it; re-attach reuses it", async () => {
    const { mgr } = makeMgr()
    expect(mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach }).ok).toBe(true)
    expect(mgr.has("d", "s", "t1")).toBe(true)
    expect(await mgr.hasSession("s", "t1")).toBe(true)

    mgr.detach("d", "s", "t1")
    expect(mgr.has("d", "s", "t1")).toBe(false)
    expect(await mgr.hasSession("s", "t1")).toBe(true) // ← persists across detach

    expect(mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach }).ok).toBe(true)
    expect(mgr.has("d", "s", "t1")).toBe(true)
  })

  it("close destroys the tmux session and viewer", async () => {
    const { mgr } = makeMgr()
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach })
    await mgr.close("s", "t1")
    expect(mgr.has("d", "s", "t1")).toBe(false)
    expect(await mgr.hasSession("s", "t1")).toBe(false)
  })

  it("supports multiple terminals per session and lists them sorted", async () => {
    const { mgr } = makeMgr()
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach })
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t2", ...baseAttach })
    const list = await mgr.listForSession("s")
    expect(list.map((l) => l.id)).toEqual(["t1", "t2"])
  })

  it("killAllForSession clears one session, leaves others", async () => {
    const { mgr } = makeMgr()
    mgr.attach({ deviceName: "d", sessionName: "s1", terminalId: "t1", ...baseAttach })
    mgr.attach({ deviceName: "d", sessionName: "s2", terminalId: "t1", ...baseAttach })
    await mgr.killAllForSession("s1")
    expect(await mgr.hasSession("s1", "t1")).toBe(false)
    expect(await mgr.hasSession("s2", "t1")).toBe(true)
  })

  it("a natural exit fires onExit; an intentional detach does NOT", async () => {
    const { mgr, world } = makeMgr()
    const naturalExits: number[] = []
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach, onExit: (c) => { naturalExits.push(c) } })
    world.procs[0]!.end(0) // shell/tmux ended on its own
    await flush()
    expect(naturalExits).toEqual([0])

    const detachExits: number[] = []
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t2", ...baseAttach, onExit: (c) => { detachExits.push(c) } })
    mgr.detach("d", "s", "t2")
    await flush()
    expect(detachExits).toEqual([]) // detach is intentional → no exit frame
  })

  it("relays viewer output to onData", async () => {
    const { mgr, world } = makeMgr()
    let received = ""
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach, onData: (d) => { received += new TextDecoder().decode(d) } })
    world.procs[0]!.emit("hello world")
    await flush()
    expect(received).toContain("hello world")
  })

  it("write/resize target the viewer's stdin and reject unknown terminals", () => {
    const { mgr, world } = makeMgr()
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach })
    expect(mgr.write("d", "s", "t1", new TextEncoder().encode("ls\n"))).toBe(true)
    expect(mgr.resize("d", "s", "t1", 120, 40)).toBe(true)
    // stdin saw the input plus the NUL-prefixed resize escape
    const all = world.procs[0]!.writes.map((w) => new TextDecoder().decode(w)).join("")
    expect(all).toContain("ls\n")
    expect(all).toContain("R120:40")
    expect(mgr.write("d", "s", "nope", new TextEncoder().encode("x"))).toBe(false)
    expect(mgr.resize("d", "s", "nope", 80, 24)).toBe(false)
  })

  it("shutdown detaches all viewers without destroying tmux sessions", async () => {
    const { mgr } = makeMgr()
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t1", ...baseAttach })
    mgr.attach({ deviceName: "d", sessionName: "s", terminalId: "t2", ...baseAttach })
    expect(mgr.count()).toBe(2)
    mgr.shutdown()
    expect(mgr.count()).toBe(0)
    expect(await mgr.hasSession("s", "t1")).toBe(true) // tmux sessions survive a broker stop
  })

  it("agent kind: attach builds a grouped-viewer argv; detach kills the viewer, not the agent", async () => {
    const { viewerSessionName } = await import("../src/core/terminal/agent-tmux")
    const spawnedArgs: string[][] = []
    const spawn = (cmd: string[]): TermProc => { spawnedArgs.push(cmd); return makeFakeProc() }
    const agentCalls: string[][] = []
    const mgr = new TerminalManager({
      stateDir: STATE,
      socket: "test",
      run: async () => ({ code: 0, stdout: "", stderr: "" }),
      spawn,
      agentRun: async (a) => { agentCalls.push(a); return { code: 0, stdout: "", stderr: "" } },
    })

    const r = mgr.attach({
      deviceName: "d", sessionName: "s", terminalId: "agent",
      ...baseAttach, kind: "agent", agentTarget: "mux:s",
    })
    expect(r.ok).toBe(true)

    // the spawned argv is an `sh -c` that builds the grouped viewer + attaches
    const argv = spawnedArgs.at(-1)!
    expect(argv).toContain("sh")
    const script = argv[argv.indexOf("sh") + 2]!
    expect(script).toContain("new-session -d")
    expect(script).toContain("exec tmux attach")
    expect(agentCalls.flat()).not.toContain("kill-session")

    mgr.detach("d", "s", "agent")
    await flush()
    const kills = agentCalls.filter((c) => c[0] === "kill-session")
    expect(kills.length).toBe(1)
    expect(kills[0]![2]).toBe(viewerSessionName("d", "mux:s"))
    expect(kills.flat()).not.toContain("mux:s")
  })
})
