import { describe, expect, test } from "bun:test"
import type { RuntimeTarget, RuntimeViewer, SessionBackend } from "../runtime/session-backend"
import {
  createSessiondTerm,
  parseSessiondTerminalName,
  sessiondTerminalGroup,
  sessiondTerminalName,
} from "./sessiond-term"

const bytes = (value: string) => new TextEncoder().encode(value)
const text = (value: Uint8Array) => new TextDecoder().decode(value)
const tick = () => new Promise(resolve => setTimeout(resolve, 0))

class FakeBackend implements SessionBackend {
  targets = new Map<string, RuntimeTarget & { group: string }>()
  creates: Parameters<SessionBackend["create"]>[0][] = []
  writes: Array<{ targetId: string; data: Uint8Array }> = []
  resizes: Array<{ targetId: string; cols: number; rows: number }> = []
  kills: string[] = []
  viewerCloses = 0
  attachCalls: Array<{ targetId: string; viewerId: string }> = []
  failAttach?: Error
  failKill?: Error
  failPostCreateLivePid?: Error
  private outputs = new Map<string, (data: Uint8Array) => void | Promise<void>>()
  private viewerExit?: (code: number) => void
  private next = 1

  seed(group: string, name: string, alive = true): RuntimeTarget {
    const target = { id: `target-${this.next++}`, name, pid: alive ? 4000 + this.next : null, alive, group }
    this.targets.set(target.id, target)
    return target
  }

  async create(opts: Parameters<SessionBackend["create"]>[0]): Promise<RuntimeTarget> {
    this.creates.push({ ...opts, argv: [...opts.argv], env: { ...opts.env } })
    return this.seed(opts.group, opts.name)
  }
  async list(group?: string): Promise<RuntimeTarget[]> {
    return [...this.targets.values()].filter(target => group === undefined || target.group === group)
  }
  async resolve(group: string, name: string): Promise<string | null> {
    return [...this.targets.values()].find(target => target.group === group && target.name === name)?.id ?? null
  }
  async livePid(targetId: string): Promise<number | null> {
    if (this.creates.length > 0 && this.failPostCreateLivePid) throw this.failPostCreateLivePid
    return this.targets.get(targetId)?.pid ?? null
  }
  async write(targetId: string, data: Uint8Array): Promise<void> { this.writes.push({ targetId, data: data.slice() }) }
  async sendKeys(): Promise<void> {}
  async resize(targetId: string, cols: number, rows: number): Promise<void> { this.resizes.push({ targetId, cols, rows }) }
  async capture(): Promise<string | null> { return null }
  async attach(targetId: string, viewerId: string, onData: (data: Uint8Array) => void | Promise<void>): Promise<RuntimeViewer> {
    this.attachCalls.push({ targetId, viewerId })
    if (this.failAttach) throw this.failAttach
    this.outputs.set(viewerId, onData)
    let resolveExit!: (code: number) => void
    const exited = new Promise<number>(resolve => { resolveExit = resolve })
    this.viewerExit = resolveExit
    let open = true
    return {
      close: () => {
        if (!open) return
        open = false
        this.viewerCloses++
        this.outputs.delete(viewerId)
      },
      write: data => {
        if (!open) return false
        this.writes.push({ targetId, data: data.slice() })
        return true
      },
      resize: (cols, rows) => {
        if (!open) return false
        this.resizes.push({ targetId, cols, rows })
        return true
      },
      exited,
    } as RuntimeViewer
  }
  async interrupt(): Promise<void> {}
  async kill(targetId: string): Promise<void> {
    this.kills.push(targetId)
    if (this.failKill) throw this.failKill
    const target = this.targets.get(targetId)
    if (target) { target.alive = false; target.pid = null }
  }

  async emit(value: string): Promise<void> {
    for (const output of this.outputs.values()) await output(bytes(value))
  }
  exit(code: number): void { this.viewerExit?.(code) }
}

describe("SessiondTerm", () => {
  test("creates a scratch PowerShell target with safe deterministic naming and the broker environment", async () => {
    const backend = new FakeBackend()
    const created = await createSessiondTerm({
      backend,
      kind: "scratch",
      deviceName: "phone/one",
      sessionName: "broker:session/one",
      terminalId: "term:one/../../x",
      workdir: "C:\\work tree",
      cols: 101,
      rows: 37,
      environment: { Path: "C:\\bin", MUX_TEST_VALUE: "kept" },
      findExecutable: name => name === "pwsh.exe" ? "C:\\Program Files\\PowerShell\\7\\pwsh.exe" : null,
    })

    expect(backend.creates).toHaveLength(1)
    expect(backend.creates[0]).toEqual({
      group: sessiondTerminalGroup("broker:session/one"),
      name: sessiondTerminalName("term:one/../../x"),
      cwd: "C:\\work tree",
      argv: ["C:\\Program Files\\PowerShell\\7\\pwsh.exe", "-NoLogo"],
      env: { Path: "C:\\bin", MUX_TEST_VALUE: "kept" },
      cols: 101,
      rows: 37,
    })
    expect(created.targetId).toBe("target-1")
    expect(created.created).toBe(true)
    expect(backend.attachCalls[0]!.viewerId).not.toContain("phone/one")
    expect(sessiondTerminalGroup("broker:session/one")).toMatch(/^muxterm-[0-9a-f]+$/)
    expect(sessiondTerminalName("term:one/..\/..\/x")).toMatch(/^term-[0-9a-f]+$/)
    expect(parseSessiondTerminalName(sessiondTerminalName("term:one/../../x"))).toBe("term:one/../../x")
  })

  test("falls back to Windows PowerShell and reuses a live scratch target", async () => {
    const backend = new FakeBackend()
    const target = backend.seed(sessiondTerminalGroup("s"), sessiondTerminalName("t"))
    const created = await createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {},
      findExecutable: name => name === "powershell.exe" ? "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" : null,
    })
    expect(created.targetId).toBe(target.id)
    expect(created.created).toBe(false)
    expect(backend.creates).toHaveLength(0)
  })

  test("replaces a resolved dead scratch target", async () => {
    const backend = new FakeBackend()
    const stale = backend.seed(sessiondTerminalGroup("s"), sessiondTerminalName("t"), false)
    const created = await createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })
    expect(backend.kills).toEqual([stale.id])
    expect(created.created).toBe(true)
    expect(created.targetId).not.toBe(stale.id)
  })

  test("streams output in order and forwards input and resize with boolean backpressure", async () => {
    const backend = new FakeBackend()
    const { proc, targetId } = await createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })
    const reader = proc.stdout.getReader()
    await backend.emit("one")
    await backend.emit("two")
    expect(text((await reader.read()).value!)).toBe("one")
    expect(text((await reader.read()).value!)).toBe("two")
    expect(proc.stdin.write(bytes("dir\r"))).toBe(true)
    expect(proc.resize?.(120, 42)).toBe(true)
    expect(backend.writes.map(write => [write.targetId, text(write.data)])).toEqual([[targetId, "dir\r"]])
    expect(backend.resizes).toEqual([{ targetId, cols: 120, rows: 42 }])
    proc.kill()
  })

  test("stream cancellation and repeated detach close the viewer exactly once without killing the target", async () => {
    const backend = new FakeBackend()
    const { proc } = await createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })
    await proc.stdout.cancel()
    proc.kill()
    proc.kill()
    await tick()
    expect(backend.viewerCloses).toBe(1)
    expect(backend.kills).toEqual([])
    expect(await proc.exited).toBe(143)
  })

  test("natural target exit closes the stream and reports the process code", async () => {
    const backend = new FakeBackend()
    const { proc } = await createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })
    const reader = proc.stdout.getReader()
    backend.exit(7)
    expect(await proc.exited).toBe(7)
    expect((await reader.read()).done).toBe(true)
    expect(backend.viewerCloses).toBe(1)
  })

  test("attaches an agent target without creating or killing it", async () => {
    const backend = new FakeBackend()
    const target = backend.seed("mux", "claude")
    const { proc, created } = await createSessiondTerm({
      backend, kind: "agent", deviceName: "d", sessionName: "s", terminalId: "agent",
      agentTarget: target.id, workdir: "C:\\w", cols: 80, rows: 24,
      environment: {}, findExecutable: () => { throw new Error("must not discover a shell") },
    })
    expect(created).toBe(false)
    expect(backend.creates).toHaveLength(0)
    expect(proc.stdin.write(bytes("x"))).toBe(true)
    proc.kill()
    expect(backend.kills).toEqual([])
  })

  test("rejects missing/dead agent targets and cleans up a newly-created scratch target after attach failure", async () => {
    const backend = new FakeBackend()
    await expect(createSessiondTerm({
      backend, kind: "agent", deviceName: "d", sessionName: "s", terminalId: "agent",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })).rejects.toThrow("agent target is required")
    const dead = backend.seed("mux", "claude", false)
    await expect(createSessiondTerm({
      backend, kind: "agent", agentTarget: dead.id, deviceName: "d", sessionName: "s", terminalId: "agent",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })).rejects.toThrow("agent target is not alive")

    backend.failAttach = new Error("attach denied")
    await expect(createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })).rejects.toThrow("attach denied")
    expect(backend.kills).toEqual([backend.creates.length === 1 ? "target-2" : "unexpected"])
  })

  test("reports both attach and cleanup failures without an unhandled rejection", async () => {
    const backend = new FakeBackend()
    backend.failAttach = new Error("attach denied")
    backend.failKill = new Error("cleanup denied")
    await expect(createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })).rejects.toThrow("attach denied; target cleanup failed: cleanup denied")
  })

  test("cleans a newly-created target when post-create liveness and cleanup both fail", async () => {
    const backend = new FakeBackend()
    backend.failPostCreateLivePid = new Error("post-create liveness denied")
    backend.failKill = new Error("cleanup denied")
    await expect(createSessiondTerm({
      backend, kind: "scratch", deviceName: "d", sessionName: "s", terminalId: "t",
      workdir: "C:\\w", cols: 80, rows: 24, environment: {}, findExecutable: () => "powershell.exe",
    })).rejects.toThrow("post-create liveness denied; target cleanup failed: cleanup denied")
    expect(backend.kills).toEqual(["target-1"])
  })
})
