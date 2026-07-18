import { describe, expect, test } from "bun:test"
import type { ProcessJob } from "./job-object"
import {
  SessionStore,
  type SessionProcess,
  type SessionProcessFactory,
  type SessionStoreOptions,
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

type HarnessOptions = {
  assignError?: Error
  factoryError?: Error
  jobFactoryError?: Error
  writePlan?: Array<number | Error>
  terminateFailures?: number
  terminalCloseFailures?: number
  jobCloseFailures?: number
  manualEof?: boolean
  noProcessExitOnTerminate?: boolean
  store?: Partial<SessionStoreOptions>
}

function harness(options: HarnessOptions = {}) {
  const events: string[] = []
  const sessions: Array<{
    options: Parameters<SessionProcessFactory>[0]
    emit(data: Uint8Array): void
    exit(code: number): void
    rejectExit(error: Error): void
    eof(): void
    drain(): void
    terminal: SessionTerminal & {
      offered: Uint8Array[]
      accepted: Uint8Array[]
      dimensions: Array<[number, number]>
      closeCount: number
    }
    process: SessionProcess & { killCount: number }
  }> = []
  let pid = 5000
  let jobIndex = 0
  let terminateFailures = options.terminateFailures ?? 0
  let terminalCloseFailures = options.terminalCloseFailures ?? 0
  let jobCloseFailures = options.jobCloseFailures ?? 0
  const writePlan = [...(options.writePlan ?? [])]
  const jobs: Array<ProcessJob & { assignCount: number; terminateCount: number; closeCount: number }> = []

  const processFactory: SessionProcessFactory = (spawnOptions, onData) => {
    if (options.factoryError) throw options.factoryError
    const exited = deferred<number>()
    const terminalEof = deferred<void>()
    let drainHandler = () => {}
    const terminal = {
      offered: [] as Uint8Array[],
      accepted: [] as Uint8Array[],
      dimensions: [] as Array<[number, number]>,
      closeCount: 0,
      eof: terminalEof.promise,
      setDrainHandler(handler: () => void) {
        drainHandler = handler
      },
      write(data: Uint8Array | string) {
        const offered = typeof data === "string" ? bytes(data) : new Uint8Array(data)
        this.offered.push(offered)
        const result = writePlan.shift() ?? offered.byteLength
        if (result instanceof Error) throw result
        if (result >= 0 && result <= offered.byteLength) this.accepted.push(offered.slice(0, result))
        events.push(`terminal-write:${result}/${offered.byteLength}`)
        return result
      },
      resize(cols: number, rows: number) {
        this.dimensions.push([cols, rows])
        events.push(`terminal-resize:${cols}x${rows}`)
      },
      close() {
        this.closeCount++
        events.push("terminal-close")
        if (terminalCloseFailures-- > 0) throw new Error("terminal close failed")
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
        if (!options.manualEof) terminalEof.resolve()
      },
    }
    sessions.push({
      options: spawnOptions,
      emit: onData,
      exit(code) {
        exited.resolve(code)
        if (!options.manualEof) terminalEof.resolve()
      },
      rejectExit(error) {
        exited.reject(error)
        if (!options.manualEof) terminalEof.resolve()
      },
      eof: terminalEof.resolve,
      drain: () => drainHandler(),
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
        if (terminateFailures-- > 0) throw new Error("job termination failed")
        if (!options.noProcessExitOnTerminate) sessions[index]?.exit(exitCode)
      },
      close() {
        this.closeCount++
        events.push(`job-${index}-close`)
        if (jobCloseFailures-- > 0) throw new Error("job close failed")
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
    terminalEofTimeoutMs: 20,
    processExitTimeoutMs: 20,
    ...options.store,
  })
  return { store, sessions, jobs, events }
}

const base = {
  group: "agents",
  name: "worker",
  cwd: "C:\\repo",
  argv: ["powershell.exe", "-NoProfile"],
  env: { PATH: "broker-path", HOME: "broker-home", TEST_VALUE: "hello" },
  cols: 100,
  rows: 30,
}

async function tick(): Promise<void> {
  await Promise.resolve()
  await Bun.sleep(0)
}

async function waitUntil(check: () => boolean, timeoutMs = 200): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (!check()) {
    if (Date.now() >= deadline) throw new Error("condition timed out")
    await Bun.sleep(1)
  }
}

function acceptedText(session: ReturnType<typeof harness>["sessions"][number]): string {
  const length = session.terminal.accepted.reduce((total, chunk) => total + chunk.byteLength, 0)
  const joined = new Uint8Array(length)
  let offset = 0
  for (const chunk of session.terminal.accepted) {
    joined.set(chunk, offset)
    offset += chunk.byteLength
  }
  return decoder.decode(joined)
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
  })

  test("captures ordered output and fans immutable exact bytes to healthy viewers", async () => {
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
    await waitUntil(() => seenA.length === 2 && seenB.length === 2)
    expect(seenB).toEqual(seenA)
    viewerA.close()
    viewerA.close()
    await store.detach(target.id, "a")
  })

  test("replays full-limit raw history before racing live output without detaching", async () => {
    const { store, sessions } = harness({ store: { rawByteLimit: 5, viewerByteLimit: 5 } })
    const target = await store.create(base)
    sessions[0]!.emit(bytes("discard"))
    sessions[0]!.emit(bytes("12345"))
    const seen: string[] = []
    const replayDelivery = deferred<void>()
    const attaching = store.attach(target.id, "late", async data => {
      seen.push(decoder.decode(data))
      if (seen.length === 1) await replayDelivery.promise
    })
    await waitUntil(() => seen.length === 1)
    sessions[0]!.emit(bytes("live"))
    replayDelivery.resolve()
    const viewer = await attaching
    await waitUntil(() => seen.join("") === "12345live")
    expect(seen.join("")).toBe("12345live")
    expect(viewer.write(bytes("input"))).toBe(true)
  })

  test("reattach replays output produced while detached", async () => {
    const { store, sessions } = harness()
    const target = await store.create(base)
    const first: string[] = []
    const viewer = await store.attach(target.id, "phone", data => { first.push(decoder.decode(data)) })
    sessions[0]!.emit(bytes("one"))
    await waitUntil(() => first.join("") === "one")
    viewer.close()
    sessions[0]!.emit(bytes("-away"))
    const replayed: string[] = []
    await store.attach(target.id, "phone", data => { replayed.push(decoder.decode(data)) })
    await waitUntil(() => replayed.join("") === "one-away")
  })

  test("serializes an attach with racing output without gaps or duplicates", async () => {
    const { store, sessions } = harness()
    const target = await store.create(base)
    sessions[0]!.emit(bytes("before"))
    const seen: string[] = []
    const attaching = store.attach(target.id, "racing", async data => {
      await Promise.resolve()
      seen.push(decoder.decode(data))
    })
    sessions[0]!.emit(bytes("after"))
    await attaching
    await waitUntil(() => seen.join("") === "beforeafter")
    expect(seen.join("")).toBe("beforeafter")
  })

  test("isolates and bounds a hanging viewer without blocking capture, healthy viewers, or cleanup", async () => {
    const { store, sessions, jobs } = harness({ store: { viewerByteLimit: 5 } })
    const target = await store.create(base)
    let hangingCalls = 0
    const healthy: string[] = []
    await store.attach(target.id, "hanging", () => {
      hangingCalls++
      return new Promise<void>(() => {})
    })
    await store.attach(target.id, "healthy", data => {
      healthy.push(decoder.decode(data))
    })

    sessions[0]!.emit(bytes("abc"))
    sessions[0]!.emit(bytes("def"))
    expect(await store.capture(target.id, true)).toBe("abcdef")
    await waitUntil(() => healthy.length === 2)
    sessions[0]!.emit(bytes("g"))
    await waitUntil(() => healthy.length === 3)
    expect(hangingCalls).toBe(1)

    await store.kill(target.id)
    expect(jobs[0]?.closeCount).toBe(1)
    expect(await store.list()).toEqual([])
  })

  test("detaches a rejecting viewer without poisoning later output", async () => {
    const { store, sessions } = harness()
    const target = await store.create(base)
    let rejectedCalls = 0
    const healthy: string[] = []
    await store.attach(target.id, "rejecting", () => {
      rejectedCalls++
      return Promise.reject(new Error("viewer disconnected"))
    })
    await store.attach(target.id, "healthy", data => { healthy.push(decoder.decode(data)) })
    sessions[0]!.emit(bytes("one"))
    await waitUntil(() => healthy.length === 1)
    sessions[0]!.emit(bytes("two"))
    await waitUntil(() => healthy.length === 2)
    expect(rejectedCalls).toBe(1)
    expect(healthy).toEqual(["one", "two"])
  })

  test("drains partial and zero ConPTY writes without reordering or duplication", async () => {
    const { store, sessions } = harness({ writePlan: [2, 0, 1, 0, 1, 1] })
    const target = await store.create(base)
    const first = store.write(target.id, bytes("abcd"))
    const second = store.sendKeys(target.id, ["Enter"])
    let firstDone = false
    void first.then(() => { firstDone = true })
    await tick()
    expect(firstDone).toBe(false)
    expect(acceptedText(sessions[0]!)).toBe("ab")

    sessions[0]!.drain()
    await tick()
    expect(acceptedText(sessions[0]!)).toBe("abc")
    sessions[0]!.drain()
    await Promise.all([first, second])
    expect(acceptedText(sessions[0]!)).toBe("abcd\r")
  })

  test("synchronous viewer writes enqueue whole immutable chunks and reject bounded overflow", async () => {
    const { store, sessions } = harness({ writePlan: [0, 3], store: { inputByteLimit: 4 } })
    const target = await store.create(base)
    const viewer = await store.attach(target.id, "viewer", () => {})
    const mutable = bytes("abc")
    expect(viewer.write(mutable)).toBe(true)
    mutable.fill("x".charCodeAt(0))
    expect(viewer.write(bytes("de"))).toBe(false)
    sessions[0]!.drain()
    await tick()
    expect(acceptedText(sessions[0]!)).toBe("abc")
  })

  test("viewer writes report immediate terminal faults and leave the queue reusable", async () => {
    for (const fault of [new Error("native write failed"), -1, 99]) {
      const { store, sessions } = harness({ writePlan: [fault] })
      const target = await store.create({ ...base, name: `fault-${String(fault)}` })
      const viewer = await store.attach(target.id, "viewer", () => {})

      expect(viewer.write(bytes("abc"))).toBe(false)
      expect(viewer.write(bytes("ok"))).toBe(true)
      expect(acceptedText(sessions[0]!)).toBe("ok")
    }
  })

  test("async writes reject terminal throws and invalid accepted byte counts", async () => {
    for (const [fault, message] of [
      [new Error("native write failed"), /native write failed/i],
      [-1, /invalid accepted byte count: -1/i],
      [99, /invalid accepted byte count: 99/i],
    ] as const) {
      const { store } = harness({ writePlan: [fault] })
      const target = await store.create({ ...base, name: `async-fault-${String(fault)}` })
      await expect(store.write(target.id, bytes("abc"))).rejects.toThrow(message)
    }
  })

  test("rejects oversized async input before copying and remains usable", async () => {
    let sliceCalls = 0
    class SliceObservedBytes extends Uint8Array {
      override slice(start?: number, end?: number): Uint8Array<ArrayBuffer> {
        sliceCalls++
        return super.slice(start, end)
      }
    }
    const { store, sessions } = harness({ store: { inputByteLimit: 2 } })
    const target = await store.create(base)
    const oversized = new SliceObservedBytes([1, 2, 3])

    await expect(store.write(target.id, oversized)).rejects.toThrow(/queue exceeds 2 bytes/i)
    expect(sliceCalls).toBe(0)
    expect(sessions[0]!.terminal.offered).toHaveLength(0)
    await store.write(target.id, bytes("ok"))
    expect(acceptedText(sessions[0]!)).toBe("ok")
  })

  test("rejects queued async input when terminal cleanup closes the writer", async () => {
    const { store } = harness({ writePlan: [0] })
    const target = await store.create(base)
    const pending = store.write(target.id, bytes("blocked"))
    await store.kill(target.id)
    await expect(pending).rejects.toThrow(/terminal.*closed/i)
  })

  test("rejects async input overflow without disturbing the already queued chunk", async () => {
    const { store, sessions } = harness({ writePlan: [0, 3], store: { inputByteLimit: 3 } })
    const target = await store.create(base)
    const pending = store.write(target.id, bytes("abc"))
    await expect(store.write(target.id, bytes("d"))).rejects.toThrow(/queue exceeds 3 bytes/i)
    sessions[0]!.drain()
    await pending
    expect(acceptedText(sessions[0]!)).toBe("abc")
  })

  test("writes, resizes, semantic keys, and interrupt through the input queue", async () => {
    const { store, sessions } = harness()
    const target = await store.create(base)
    const viewer = await store.attach(target.id, "viewer", () => {})
    await store.write(target.id, new Uint8Array([0, 255, 65]))
    await store.resize(target.id, 120, 40)
    await store.sendKeys(target.id, ["Enter", "Tab", "C-c", "literal"])
    await store.interrupt(target.id)
    expect(viewer.write(bytes("viewer input"))).toBe(true)
    expect(viewer.resize(90, 20)).toBe(true)
    expect(sessions[0]!.terminal.accepted.map(value => [...value])).toEqual([
      [0, 255, 65],
      [...bytes("\r\t\x03literal")],
      [3],
      [...bytes("viewer input")],
    ])
  })

  test("failed Job termination preserves liveness and permits a later retry", async () => {
    const { store, sessions, jobs } = harness({ terminateFailures: 1 })
    const target = await store.create(base)
    await expect(store.kill(target.id)).rejects.toThrow("job termination failed")
    expect(await store.livePid(target.id)).toBe(target.pid)
    await store.write(target.id, bytes("still live"))
    expect(acceptedText(sessions[0]!)).toBe("still live")

    await store.kill(target.id)
    expect(jobs[0]?.terminateCount).toBe(2)
    expect(await store.livePid(target.id)).toBeNull()
  })

  test("cleanup attempts terminal and Job independently and retries failed closes", async () => {
    for (const failure of [
      { terminalCloseFailures: 1 },
      { jobCloseFailures: 1 },
      { terminalCloseFailures: 1, jobCloseFailures: 1 },
    ]) {
      const { store, sessions, jobs } = harness(failure)
      const target = await store.create({ ...base, name: `cleanup-${JSON.stringify(failure)}` })
      await expect(store.kill(target.id)).rejects.toThrow(/close failed/i)
      expect(sessions[0]?.terminal.closeCount).toBe(1)
      expect(jobs[0]?.closeCount).toBe(1)
      await store.kill(target.id)
      expect(sessions[0]!.terminal.closeCount + jobs[0]!.closeCount).toBeGreaterThanOrEqual(3)
      expect(await store.list()).toEqual([])
    }
  })

  test("waits for trailing ConPTY output and EOF before cleanup", async () => {
    const { store, sessions, jobs } = harness({ manualEof: true, store: { terminalEofTimeoutMs: 100 } })
    const target = await store.create(base)
    const trailing: string[] = []
    await store.attach(target.id, "trailing-observer", data => { trailing.push(decoder.decode(data)) })
    sessions[0]!.exit(7)
    await tick()
    expect(await store.list()).toEqual([])
    expect(await store.capture(target.id)).toBeNull()
    expect(jobs[0]?.closeCount).toBe(0)
    sessions[0]!.emit(bytes("trailing"))
    await waitUntil(() => trailing.length === 1)
    expect(trailing).toEqual(["trailing"])
    sessions[0]!.eof()
    await waitUntil(() => store.listExited().length === 1)
    expect(store.listExited()).toEqual([
      expect.objectContaining({ id: target.id, alive: false, pid: null, exitCode: 7 }),
    ])
    expect(jobs[0]?.closeCount).toBe(1)
  })

  test("settles viewer exit only after trailing output delivery and ConPTY EOF", async () => {
    const { store, sessions } = harness({ manualEof: true, store: { terminalEofTimeoutMs: 100 } })
    const target = await store.create(base)
    const delivered: string[] = []
    const delivery = deferred<void>()
    const viewer = await store.attach(target.id, "ordered-exit", async data => {
      delivered.push(decoder.decode(data))
      await delivery.promise
    })
    let exitCode: number | undefined
    void viewer.exited?.then(code => { exitCode = code })

    sessions[0]!.exit(7)
    sessions[0]!.emit(bytes("trailing"))
    sessions[0]!.eof()
    await waitUntil(() => delivered.length === 1)
    await tick()
    expect(exitCode).toBeUndefined()
    delivery.resolve()
    expect(await viewer.exited).toBe(7)
    expect(delivered).toEqual(["trailing"])
  })

  test("settles ordered viewer exit even when later native cleanup must be retried", async () => {
    const { store, sessions } = harness({ terminalCloseFailures: 1 })
    const target = await store.create(base)
    const viewer = await store.attach(target.id, "cleanup-failure-exit", () => {})
    sessions[0]!.exit(9)
    const result = await Promise.race([
      viewer.exited!,
      Bun.sleep(30).then(() => "timeout" as const),
    ])
    expect(result).toBe(9)
    await store.kill(target.id)
    expect(store.listExited()).toEqual([expect.objectContaining({ id: target.id, exitCode: 9 })])
  })

  test("uses a finite EOF and process-exit fallback", async () => {
    const eofTimeout = harness({ manualEof: true, store: { terminalEofTimeoutMs: 5 } })
    const first = await eofTimeout.store.create(base)
    eofTimeout.sessions[0]!.exit(9)
    await waitUntil(() => eofTimeout.store.listExited().some(item => item.id === first.id))

    const processTimeout = harness({
      manualEof: true,
      noProcessExitOnTerminate: true,
      store: { terminalEofTimeoutMs: 5, processExitTimeoutMs: 5 },
    })
    const second = await processTimeout.store.create(base)
    await processTimeout.store.kill(second.id)
    expect(processTimeout.store.listExited()).toEqual([
      expect.objectContaining({ id: second.id, exitCode: null }),
    ])
  })

  test("follows active backend semantics while keeping only bounded detached exit history", async () => {
    const { store, sessions } = harness({ store: { maxExitedHistory: 2 } })
    for (const code of [1, 2, 3]) {
      const target = await store.create(base)
      sessions.at(-1)!.emit(bytes(`run-${code}`))
      sessions.at(-1)!.exit(code)
      await waitUntil(() => store.listExited().some(item => item.id === target.id))
      expect(await store.list()).toEqual([])
      expect(await store.resolve(base.group, base.name)).toBeNull()
      expect(await store.capture(target.id)).toBeNull()
    }
    expect(store.listExited()).toEqual([
      expect.objectContaining({ exitCode: 2 }),
      expect.objectContaining({ exitCode: 3 }),
    ])
    expect(store.listExited("other")).toEqual([])
  })

  test("cleans up factory and assignment failures and terminates the Job first", async () => {
    const jobFailure = harness({ jobFactoryError: new Error("kernel32 unavailable") })
    await expect(jobFailure.store.create(base)).rejects.toThrow("kernel32 unavailable")

    const factoryFailure = harness({ factoryError: new Error("spawn unavailable") })
    await expect(factoryFailure.store.create(base)).rejects.toThrow("spawn unavailable")
    expect(factoryFailure.jobs[0]?.closeCount).toBe(1)

    const assignmentFailure = harness({ assignError: new Error("assignment denied") })
    await expect(assignmentFailure.store.create(base)).rejects.toThrow("assignment denied")
    const events = assignmentFailure.events
    expect(events.indexOf("job-0-terminate:1")).toBeLessThan(events.indexOf("process-kill"))
    expect(events.indexOf("job-0-terminate:1")).toBeLessThan(events.indexOf("terminal-close"))
    expect(assignmentFailure.sessions[0]?.process.killCount).toBe(1)
  })

  test("detach is idempotent and does not kill", async () => {
    const { store, jobs } = harness()
    const target = await store.create(base)
    const viewer = await store.attach(target.id, "viewer", () => {})
    viewer.close()
    viewer.close()
    await store.detach(target.id, "viewer")
    expect(jobs[0]?.terminateCount).toBe(0)
  })

  test("detaching removes cancellable target-exit handlers from long-lived targets", async () => {
    const { store, sessions } = harness()
    const target = await store.create(base)
    const viewer = await store.attach(target.id, "temporary", () => {})
    let exitCalls = 0
    viewer.onExit?.(() => { exitCalls++ })
    viewer.close()
    sessions[0]!.exit(0)
    await waitUntil(() => store.listExited().length === 1)
    expect(exitCalls).toBe(0)
  })

  test("late cancellable exit subscriptions observe an already-finalized target once", async () => {
    const { store, sessions } = harness()
    const target = await store.create(base)
    const viewer = await store.attach(target.id, "late-exit-subscription", () => {})
    sessions[0]!.exit(7)
    expect(await viewer.exited).toBe(7)
    const codes: number[] = []
    const unsubscribe = viewer.onExit?.(code => { codes.push(code) })
    unsubscribe?.()
    expect(codes).toEqual([7])
  })
})
