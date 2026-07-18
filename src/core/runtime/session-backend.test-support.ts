import { expect } from "bun:test"
import type { RuntimeTarget, RuntimeViewer, SessionBackend } from "./session-backend"

type MemoryTarget = RuntimeTarget & {
  group: string
  output: number[]
  sentKeys: string[][]
  viewers: Map<string, (data: Uint8Array) => void | Promise<void>>
}

type MemorySessionBackend = SessionBackend & {
  sentKeys(targetId: string): string[][]
}

export function createMemorySessionBackend(): MemorySessionBackend {
  const targets = new Map<string, MemoryTarget>()
  let nextId = 1

  const target = (targetId: string): MemoryTarget => {
    const found = targets.get(targetId)
    if (!found || !found.alive) throw new Error(`runtime target not found: ${targetId}`)
    return found
  }

  const publish = async (found: MemoryTarget, data: Uint8Array) => {
    found.output.push(...data)
    await Promise.all([...found.viewers.values()].map(onData => onData(data)))
  }

  return {
    async create(opts) {
      const id = `memory-${nextId++}`
      const created: MemoryTarget = {
        id,
        name: opts.name,
        pid: 10_000 + nextId,
        alive: true,
        group: opts.group,
        output: [],
        sentKeys: [],
        viewers: new Map(),
      }
      targets.set(id, created)
      return { id, name: created.name, pid: created.pid, alive: created.alive }
    },
    async list(group) {
      return [...targets.values()]
        .filter(found => found.alive && (group === undefined || found.group === group))
        .map(({ id, name, pid, alive }) => ({ id, name, pid, alive }))
    },
    async resolve(group, name) {
      return [...targets.values()].find(found => found.alive && found.group === group && found.name === name)?.id ?? null
    },
    async livePid(targetId) {
      const found = targets.get(targetId)
      return found?.alive ? found.pid : null
    },
    async write(targetId, data) {
      await publish(target(targetId), data)
    },
    async sendKeys(targetId, keys) {
      target(targetId).sentKeys.push([...keys])
    },
    async resize(targetId) {
      target(targetId)
    },
    async capture(targetId, raw = false) {
      const found = targets.get(targetId)
      if (!found?.alive) return null
      const text = new TextDecoder().decode(new Uint8Array(found.output))
      return raw ? `\u001b[32m${text}\u001b[0m` : text
    },
    async attach(targetId, viewerId, onData) {
      const found = target(targetId)
      found.viewers.set(viewerId, onData)
      const viewer: RuntimeViewer = {
        close() {
          found.viewers.delete(viewerId)
        },
        write(data) {
          void publish(found, data)
          return found.alive
        },
        resize() {
          return found.alive
        },
      }
      return viewer
    },
    async interrupt(targetId) {
      await publish(target(targetId), new Uint8Array([3]))
    },
    async kill(targetId) {
      const found = targets.get(targetId)
      if (!found) return
      found.alive = false
      found.pid = null
      found.viewers.clear()
    },
    sentKeys(targetId) {
      return target(targetId).sentKeys.map(keys => [...keys])
    },
  }
}

export async function verifySessionBackendContract(backend: MemorySessionBackend): Promise<void> {
  await import("./session-backend")
  const created = await backend.create({
    group: "contract",
    name: "worker",
    cwd: "/tmp",
    argv: ["sh", "-c", "printf ready"],
    env: { CONTRACT_VALUE: "safe value" },
    cols: 100,
    rows: 30,
  })
  expect(created.name).toBe("worker")
  expect(created.alive).toBe(true)
  expect(await backend.list("contract")).toEqual([created])
  expect(await backend.resolve("contract", "worker")).toBe(created.id)
  expect(await backend.livePid(created.id)).toBe(created.pid)

  const seen: string[] = []
  const viewer = await backend.attach(created.id, "viewer-1", data => {
    seen.push(new TextDecoder().decode(data))
  })
  await backend.write(created.id, new TextEncoder().encode("hello"))
  expect(viewer.write(new TextEncoder().encode(" viewer"))).toBe(true)
  expect(viewer.resize(120, 40)).toBe(true)
  await backend.resize(created.id, 120, 40)
  await Promise.resolve()
  expect(await backend.capture(created.id)).toBe("hello viewer")
  expect(await backend.capture(created.id, true)).toContain("\u001b[32mhello viewer")
  expect(seen).toEqual(["hello", " viewer"])
  await backend.sendKeys(created.id, ["Enter", "C-c"])
  expect(backend.sentKeys(created.id)).toEqual([["Enter", "C-c"]])

  viewer.close()
  await backend.write(created.id, new TextEncoder().encode(" detached"))
  expect(seen).toEqual(["hello", " viewer"])
  await backend.interrupt(created.id)
  expect(await backend.capture(created.id)).toBe("hello viewer detached\u0003")

  await backend.kill(created.id)
  expect(await backend.livePid(created.id)).toBe(null)
  expect(await backend.resolve("contract", "worker")).toBe(null)
  expect(await backend.list("contract")).toEqual([])
  expect(await backend.capture(created.id)).toBe(null)
}
