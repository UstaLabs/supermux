import { describe, expect, test } from "bun:test"
import type { ProcessJob } from "./job-object"
import {
  SessionStore,
  type SessionProcess,
  type SessionProcessFactory,
  type SessionTerminal,
} from "./session-store"

const encoder = new TextEncoder()
const decoder = new TextDecoder()

function bytes(value: string): Uint8Array {
  return encoder.encode(value)
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function harness(options: { assignError?: Error; factoryError?: Error; jobFactoryError?: Error } = {}) {
  const events: string[] = []
  const sessions: Array<{
    options: Parameters<SessionProcessFactory>[0]
    emit(data: Uint8Array): void
    exit(code: number): void
    rejectExit(error: Error): void
    terminal: SessionTerminal & { writes: Uint8Array[]; dimensions: Array<[number, number]>; closeCount: number }
    process: SessionProcess & { killCount: number }
  }> = []
  let pid = 5000
  let jobIndex = 0
  const jobs: Array<ProcessJob & { assignCount: number; terminateCount: number; closeCount: number }> = []

  const processFactory: SessionProcessFactory = (spawnOptions, onData) => {
    if (options.factoryError) throw options.factoryError
    const exited = deferred<number>()
    const terminal = {
      writes: [] as Uint8Array[],
      dimensions: [] as Array<[number, number]>,
      closeCount: 0,
      write(data: Uint8Array | string) {
        this.writes.push(typeof data === "string" ? bytes(data) : new Uint8Array(data))
        events.push("terminal-write")
        return typeof data === "string" ? data.length : data.byteLength
      },
      resize(cols: number, rows: number) {
        this.dimensions.push([cols, rows])
        events.push(`terminal-resize:${cols}x${rows}`)
      },
      close() {
        this.closeCount++
        events.push("terminal-close")
      },
    }
    const process = {
      pid: pid++,
      exited: exited.promise,
      killCount: 0,
      kill() {
        this.killCount++
        events.push("process-kill")
        exited.resolve(137)
      },
    }
    sessions.push({
      options: spawnOptions,
      emit: onData,
      exit: exited.resolve,
      rejectExit: exited.reject,
      terminal,
      process,
    })
    return { process, terminal }
  }

  const jobFactory = () => {
    if (options.jobFactoryError) throw options.jobFactoryError
    const index = jobIndex++
    const job = {
      assignCount: 0,
      terminateCount: 0,
      closeCount: 0,
      assign(assignedPid: number) {
        this.assignCount++
        events.push(`job-${index}-assign:${assignedPid}`)
        if (options.assignError) throw options.assignError
      },
      terminate(exitCode = 1) {
        this.terminateCount++
        events.push(`job-${index}-terminate:${exitCode}`)
        sessions[index]?.exit(exitCode)
      },
      close() {
        this.closeCount++
        events.push(`job-${index}-close`)
      },
    }
    jobs.push(job)
    return job
  }

  let id = 0
  const store = new SessionStore({
    processFactory,
    jobFactory,
    idFactory: () => `opaque-${++id}-4f954a`,
    rawByteLimit: 1024,
  })
  return { store, sessions, jobs, events }
}

const base = {
  group: "agents",
  name: "worker",
  cwd: "C:\\repo",
  argv: ["powershell.exe", "-NoProfile"],
  env: { TEST_VALUE: "hello" },
  cols: 100,
  rows: 30,
}

async function tick(): Promise<void> {
  await Promise.resolve()
  await Bun.sleep(0)
}

describe("SessionStore", () => {
  test("creates opaque targets with exact launch settings and group-scoped names", async () => {
    const { store, sessions, jobs } = harness()
    const first = await store.create(base)
    const otherGroup = await store.create({ ...base, group: "other" })

    expect(first).toEqual({ id: "opaque-1-4f954a", name: "worker", pid: 5000, alive: true })
    expect(first.id).not.toContain(base.name)
    expect(otherGroup.name).toBe("worker")
    expect(sessions[0]?.options).toEqual(base)
    expect(jobs[0]?.assignCount).toBe(1)
    await expect(store.create(base)).rejects.toThrow(/duplicate.*agents.*worker/i)
    expect(await store.resolve("agents", "worker")).toBe(first.id)
    expect(await store.list("agents")).toEqual([first])
    expect((await store.list()).length).toBe(2)
  })

  test("captures ordered output and fans immutable exact bytes to attached viewers", async () => {
    const { store, sessions } = harness()
    const target = await store.create(base)
    const seenA: string[] = []
    const seenB: string[] = []
    const viewerA = await store.attach(target.id, "a", async data => {
      await Promise.resolve()
      seenA.push(decoder.decode(data))
      data.fill(0)
    })
    await store.attach(target.id, "b", data => {
      seenB.push(decoder.decode(data))
    })

    sessions[0]!.emit(bytes("first "))
    sessions[0]!.emit(bytes("\x1b[31mred\x1b[0m"))
    expect(await store.capture(target.id)).toBe("first red")
    expect(await store.capture(target.id, true)).toBe("first \x1b[31mred\x1b[0m")
    expect(seenA).toEqual(["first ", "\x1b[31mred\x1b[0m"])
    expect(seenB).toEqual(seenA)

    viewerA.close()
    viewerA.close()
    await store.detach(target.id, "a")
    sessions[0]!.emit(bytes("!"))
    expect(await store.capture(target.id)).toBe("first red!")
    expect(seenA).toHaveLength(2)
    expect(seenB.at(-1)).toBe("!")
    expect(await store.livePid(target.id)).toBe(target.pid)
  })

  test("writes, resizes, sends semantic keys, and interrupts with byte 0x03", async () => {
    const { store, sessions } = harness()
    const target = await store.create(base)
    const viewer = await store.attach(target.id, "viewer", () => {})

    await store.write(target.id, new Uint8Array([0, 255, 65]))
    await store.resize(target.id, 120, 40)
    await store.sendKeys(target.id, ["Enter", "Tab", "C-c", "literal"])
    await store.interrupt(target.id)
    expect(viewer.write(bytes("viewer input"))).toBe(true)
    expect(viewer.resize(90, 20)).toBe(true)

    expect(sessions[0]!.terminal.writes.map(value => [...value])).toEqual([
      [0, 255, 65],
      [...bytes("\r\t\x03literal")],
      [3],
      [...bytes("viewer input")],
    ])
    expect(sessions[0]!.terminal.dimensions).toEqual([[120, 40], [90, 20]])
  })

  test("detach does not kill and explicit kill terminates the job before closing ConPTY", async () => {
    const { store, sessions, jobs, events } = harness()
    const target = await store.create(base)
    const viewer = await store.attach(target.id, "viewer", () => {})
    viewer.close()
    expect(jobs[0]?.terminateCount).toBe(0)
    expect(sessions[0]?.terminal.closeCount).toBe(0)

    await store.kill(target.id)
    await store.kill(target.id)
    expect(events.indexOf("job-0-terminate:1")).toBeLessThan(events.indexOf("terminal-close"))
    expect(jobs[0]?.terminateCount).toBe(1)
    expect(jobs[0]?.closeCount).toBe(1)
    expect(sessions[0]?.terminal.closeCount).toBe(1)
    expect(await store.livePid(target.id)).toBeNull()
  })

  test("natural exit closes handles without termination and preserves exited metadata and captures", async () => {
    const { store, sessions, jobs } = harness()
    const target = await store.create(base)
    sessions[0]!.emit(bytes("finished"))
    expect(await store.capture(target.id)).toBe("finished")
    sessions[0]!.exit(7)
    await tick()

    expect(jobs[0]?.terminateCount).toBe(0)
    expect(jobs[0]?.closeCount).toBe(1)
    expect(sessions[0]?.terminal.closeCount).toBe(1)
    expect(await store.livePid(target.id)).toBeNull()
    expect(await store.list("agents")).toEqual([{ ...target, pid: null, alive: false }])
    expect(await store.capture(target.id)).toBe("finished")
  })

  test("cleans up factory and assignment failures", async () => {
    const jobFailure = harness({ jobFactoryError: new Error("kernel32 unavailable") })
    await expect(jobFailure.store.create(base)).rejects.toThrow("kernel32 unavailable")
    expect(await jobFailure.store.list()).toEqual([])

    const factoryFailure = harness({ factoryError: new Error("spawn unavailable") })
    await expect(factoryFailure.store.create(base)).rejects.toThrow("spawn unavailable")
    expect(factoryFailure.jobs[0]?.closeCount).toBe(1)
    expect(await factoryFailure.store.list()).toEqual([])

    const assignmentFailure = harness({ assignError: new Error("assignment denied") })
    await expect(assignmentFailure.store.create(base)).rejects.toThrow("assignment denied")
    expect(assignmentFailure.sessions[0]?.process.killCount).toBe(1)
    expect(assignmentFailure.sessions[0]?.terminal.closeCount).toBe(1)
    expect(assignmentFailure.jobs[0]?.closeCount).toBe(1)
    expect(await assignmentFailure.store.list()).toEqual([])
  })

  test("handles rejected exits and output/kill races without duplicate cleanup or unhandled rejection", async () => {
    const { store, sessions, jobs } = harness()
    const first = await store.create(base)
    sessions[0]!.emit(bytes("before"))
    sessions[0]!.rejectExit(new Error("wait failed"))
    await tick()
    expect(await store.capture(first.id)).toBe("before")
    expect(jobs[0]?.closeCount).toBe(1)

    const second = await store.create({ ...base, name: "racer" })
    sessions[1]!.emit(bytes("queued"))
    await Promise.all([store.kill(second.id), store.kill(second.id)])
    sessions[1]!.emit(bytes("late"))
    expect(await store.capture(second.id, true)).toBe("queued")
    expect(jobs[1]?.terminateCount).toBe(1)
    expect(jobs[1]?.closeCount).toBe(1)
    expect(sessions[1]?.terminal.closeCount).toBe(1)
  })
})
