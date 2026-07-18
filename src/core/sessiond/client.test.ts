import { afterEach, describe, expect, test } from "bun:test"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createConnection, createServer, type Socket } from "node:net"
import { createMemorySessionBackendHarness, verifySessionBackendContract } from "../runtime/session-backend.test-support"
import { decodeFrames, encodeFrame } from "../../shared/frame-codec"
import { SessiondBackend } from "./client"
import { SESSIOND_MAX_FRAME_BYTES, startSessiondServer, type SessiondServer } from "./server"
import type { RuntimeViewer } from "../runtime/session-backend"

const cleanup: Array<() => void | Promise<void>> = []
afterEach(async () => { for (const close of cleanup.splice(0).reverse()) await close() })

async function harness() {
  const dir = await mkdtemp(join(tmpdir(), "sessiond-client-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
  const endpoint = join(dir, "rpc.sock"), secret = Buffer.alloc(32, 2).toString("base64")
  const memory = createMemorySessionBackendHarness()
  const server = await startSessiondServer({ endpoint, secret, backend: memory.backend }); cleanup.push(() => server.close())
  const client = new SessiondBackend({ endpoint, secret, stateDir: dir, platform: "linux" }); cleanup.push(() => client.close())
  return { dir, endpoint, secret, memory, server, client }
}

describe("SessiondBackend", () => {
  test("satisfies the reusable backend contract over a real framed socket", async () => {
    const { client, memory } = await harness()
    await verifySessionBackendContract(client, memory.observation)
  })

  test("sends a fresh complete broker environment on every create request", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-env-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock"), secret = Buffer.alloc(32, 14).toString("base64")
    const memory = createMemorySessionBackendHarness()
    const requests: Array<Parameters<typeof memory.backend.create>[0]> = []
    const backend = {
      ...memory.backend,
      async create(options: Parameters<typeof memory.backend.create>[0]) {
        requests.push({ ...options, argv: [...options.argv], env: { ...options.env } })
        return memory.backend.create(options)
      },
    }
    const server = await startSessiondServer({ endpoint, secret, backend }); cleanup.push(() => server.close())
    const client = new SessiondBackend({ endpoint, secret, stateDir: dir, platform: "linux" }); cleanup.push(() => client.close())
    const saved = {
      PATH: process.env.PATH,
      CLAUDE_CODE_OAUTH_TOKEN: process.env.CLAUDE_CODE_OAUTH_TOKEN,
      ANTHROPIC_API_KEY: process.env.ANTHROPIC_API_KEY,
    }
    const restore = (key: keyof typeof saved) => {
      const value = saved[key]
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
    const snapshot = (overrides: Record<string, string>) => {
      const environment: Record<string, string> = {}
      for (const [key, value] of Object.entries(process.env)) if (typeof value === "string") environment[key] = value
      return { ...environment, ...overrides }
    }

    try {
      process.env.PATH = "broker-path-one"
      process.env.CLAUDE_CODE_OAUTH_TOKEN = "oauth-one"
      process.env.ANTHROPIC_API_KEY = "api-one"
      const firstOverrides = { MUX_SESSION_ID: "first" }
      const firstExpected = snapshot(firstOverrides)
      await client.create({ group: "g", name: "first", cwd: dir, argv: ["fake"], env: firstOverrides })

      process.env.PATH = "broker-path-two"
      process.env.CLAUDE_CODE_OAUTH_TOKEN = "oauth-two"
      delete process.env.ANTHROPIC_API_KEY
      const secondOverrides = { PATH: "session-path", MUX_SESSION_ID: "second" }
      const secondExpected = snapshot(secondOverrides)
      await client.create({ group: "g", name: "second", cwd: dir, argv: ["fake"], env: secondOverrides })

      expect(requests[0]?.env).toEqual(firstExpected)
      expect(requests[1]?.env).toEqual(secondExpected)
      expect(requests[1]?.env.CLAUDE_CODE_OAUTH_TOKEN).toBe("oauth-two")
      expect(requests[1]?.env).not.toHaveProperty("ANTHROPIC_API_KEY")
      expect(requests[1]?.env.PATH).toBe("session-path")
    } finally {
      restore("PATH")
      restore("CLAUDE_CODE_OAUTH_TOKEN")
      restore("ANTHROPIC_API_KEY")
    }
  })

  test("reconnects after the server forcibly closes broker-side sockets", async () => {
    const { client, server } = await harness()
    expect((await client.hello()).version).toBe(1)
    server.closeConnections()
    await new Promise(resolve => setTimeout(resolve, 20))
    expect((await client.hello()).version).toBe(1)
  })

  test("destroys a timed-out hung RPC connection and reconnects for later calls", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-timeout-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock"), secret = Buffer.alloc(32, 8).toString("base64")
    const memory = createMemorySessionBackendHarness().backend
    let captureCalls = 0
    const backend = { ...memory, capture: async (...args: Parameters<typeof memory.capture>) => {
      if (captureCalls++ === 0) return await new Promise<string | null>(() => {})
      return memory.capture(...args)
    } }
    const server = await startSessiondServer({ endpoint, secret, backend }); cleanup.push(() => server.close())
    const client = new SessiondBackend({ endpoint, secret, stateDir: dir, platform: "linux", requestTimeoutMs: 25 }); cleanup.push(() => client.close())
    const created = await client.create({ group: "g", name: "hung", cwd: dir, argv: ["fake"], env: {} })
    await expect(client.capture(created.id)).rejects.toThrow("timed out")
    expect((await client.hello()).healthy).toBe(true)
    expect(await client.capture(created.id)).toBe("")
  })

  test("viewer write reports accepted even when socket signals backpressure and executes once", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-backpressure-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock"), secret = Buffer.alloc(32, 9).toString("base64")
    const memory = createMemorySessionBackendHarness()
    const server = await startSessiondServer({ endpoint, secret, backend: memory.backend }); cleanup.push(() => server.close())
    let writes = 0
    const client = new SessiondBackend({
      endpoint, secret, stateDir: dir, platform: "linux",
      writeUntracked: (socket, frame) => { writes++; socket.write(frame); return false },
    }); cleanup.push(() => client.close())
    const created = await client.create({ group: "g", name: "bp", cwd: dir, argv: ["fake"], env: {} })
    const viewer = await client.attach(created.id, "v", () => {})
    expect(viewer.write(new TextEncoder().encode("once"))).toBe(true)
    expect(await client.capture(created.id)).toBe("once")
    expect(writes).toBe(1)
  })

  test("forwards target exit codes to the attached viewer", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-viewer-exit-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock"), secret = Buffer.alloc(32, 15).toString("base64")
    const memory = createMemorySessionBackendHarness().backend
    let resolveExit!: (code: number) => void
    const exited = new Promise<number>(resolve => { resolveExit = resolve })
    const backend = {
      ...memory,
      async attach(...args: Parameters<typeof memory.attach>): Promise<RuntimeViewer> {
        const viewer = await memory.attach(...args)
        return { ...viewer, exited } as RuntimeViewer
      },
    }
    const server = await startSessiondServer({ endpoint, secret, backend }); cleanup.push(() => server.close())
    const client = new SessiondBackend({ endpoint, secret, stateDir: dir, platform: "linux" }); cleanup.push(() => client.close())
    const created = await client.create({ group: "g", name: "exit", cwd: dir, argv: ["fake"], env: {} })
    const viewer = await client.attach(created.id, "v", () => {}) as RuntimeViewer & { exited: Promise<number> }
    expect(viewer.exited).toBeInstanceOf(Promise)
    resolveExit(7)
    expect(await viewer.exited).toBe(7)
  })

  test("exact exit closes one viewer and target-level exit closes every remaining target viewer", async () => {
    const { client } = await harness()
    const target = await client.create({ group: "g", name: "fanout", cwd: "/tmp", argv: ["fake"], env: {} })
    const first = await client.attach(target.id, "first", () => {})
    const second = await client.attach(target.id, "second", () => {})
    const accept = (client as unknown as { acceptMessage(input: unknown): void }).acceptMessage.bind(client)
    let secondExited = false
    void second.exited?.then(() => { secondExited = true })

    accept({ event: "exit", targetId: target.id, viewerId: "first", code: 3 })
    expect(await first.exited).toBe(3)
    await Bun.sleep(0)
    expect(secondExited).toBe(false)

    accept({ event: "exit", targetId: target.id, code: 4 })
    const targetExit = await Promise.race([
      second.exited!,
      Bun.sleep(30).then(() => "timeout" as const),
    ])
    expect(targetExit).toBe(4)
  })

  test("shares concurrent adoption and spawns at most once only for missing endpoint", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-adopt-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "missing.sock"), secret = Buffer.alloc(32, 3).toString("base64")
    let spawns = 0; let server: SessiondServer | undefined
    const client = new SessiondBackend({
      endpoint, secret, stateDir: dir, platform: "win32", adoptionPollMs: 5, adoptionTimeoutMs: 500,
      spawnSessiond: async () => { spawns++; server = await startSessiondServer({ endpoint, secret, backend: createMemorySessionBackendHarness().backend }) },
    })
    cleanup.push(async () => { client.close(); await server?.close() })
    await Promise.all([client.ensureConnected(), client.ensureConnected(), client.ensureConnected()])
    expect(spawns).toBe(1)
  })

  test("adopts after an initial connection-refused failure", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-refused-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock"), secret = Buffer.alloc(32, 7).toString("base64")
    let attempts = 0; let spawns = 0; let server: SessiondServer | undefined
    const connectSocket = async (): Promise<Socket> => {
      if (attempts++ === 0) throw Object.assign(new Error("refused"), { code: "ECONNREFUSED" })
      return await new Promise<Socket>((resolveConnect, reject) => {
        const socket = createConnection(endpoint); socket.once("connect", () => resolveConnect(socket)); socket.once("error", reject)
      })
    }
    const client = new SessiondBackend({
      endpoint, secret, stateDir: dir, platform: "win32", connectSocket, adoptionPollMs: 5, adoptionTimeoutMs: 500,
      spawnSessiond: async () => { spawns++; server = await startSessiondServer({ endpoint, secret, backend: createMemorySessionBackendHarness().backend }) },
    })
    cleanup.push(async () => { client.close(); await server?.close() })
    await client.ensureConnected()
    expect(spawns).toBe(1)
  })

  test("bad credentials fail without spawning", async () => {
    const { endpoint, dir } = await harness(); let spawns = 0
    const client = new SessiondBackend({ endpoint, secret: "wrong", stateDir: dir, platform: "win32", spawnSessiond: async () => { spawns++ } })
    cleanup.push(() => client.close())
    await expect(client.ensureConnected()).rejects.toThrow("authentication failed")
    expect(spawns).toBe(0)
  })

  test("does not adopt on an initial EPIPE transport failure", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-epipe-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    let spawns = 0
    const failure = Object.assign(new Error("broken pipe during hello"), { code: "EPIPE" })
    const client = new SessiondBackend({
      endpoint: join(dir, "rpc.sock"), secret: Buffer.alloc(32, 6).toString("base64"), stateDir: dir, platform: "win32",
      connectSocket: async () => { throw failure },
      spawnSessiond: async () => { spawns++ },
    })
    cleanup.push(() => client.close())
    await expect(client.ensureConnected()).rejects.toBe(failure)
    expect(spawns).toBe(0)
  })

  test("bounds viewers and closes connection-owned viewer handles", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-viewers-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock"), secret = Buffer.alloc(32, 4).toString("base64")
    const memory = createMemorySessionBackendHarness().backend
    let closedViewers = 0
    const backend = {
      ...memory,
      async attach(targetId: string, viewerId: string, onData: (data: Uint8Array) => void | Promise<void>) {
        const viewer = await memory.attach(targetId, viewerId, onData)
        return { ...viewer, close() { closedViewers++; viewer.close() } }
      },
    }
    const server = await startSessiondServer({ endpoint, secret, backend, maxViewersPerConnection: 1 }); cleanup.push(() => server.close())
    const client = new SessiondBackend({ endpoint, secret, stateDir: dir, platform: "linux" }); cleanup.push(() => client.close())
    const created = await client.create({ group: "g", name: "n", cwd: dir, argv: ["fake"], env: {} })
    const viewer = await client.attach(created.id, "viewer-1", () => {})
    await expect(client.attach(created.id, "viewer-2", () => {})).rejects.toThrow("viewer limit")
    server.closeConnections()
    for (let attempt = 0; attempt < 50 && closedViewers === 0; attempt++) await new Promise(resolve => setTimeout(resolve, 2))
    expect(closedViewers).toBe(1)
    expect(viewer.write(new Uint8Array([1]))).toBe(false)
    expect(viewer.resize(80, 24)).toBe(false)
    await client.hello()
    expect(viewer.write(new Uint8Array([1]))).toBe(false)
    expect(viewer.resize(80, 24)).toBe(false)
  })

  test("enforces per-target viewer limits globally across connections and releases reservations", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-global-viewers-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock"), secret = Buffer.alloc(32, 11).toString("base64")
    const server = await startSessiondServer({ endpoint, secret, backend: createMemorySessionBackendHarness().backend, maxViewersPerTarget: 1 })
    cleanup.push(() => server.close())
    const first = new SessiondBackend({ endpoint, secret, stateDir: dir, platform: "linux" })
    const second = new SessiondBackend({ endpoint, secret, stateDir: dir, platform: "linux" })
    cleanup.push(() => first.close()); cleanup.push(() => second.close())
    const created = await first.create({ group: "g", name: "global", cwd: dir, argv: ["fake"], env: {} })
    const viewer = await first.attach(created.id, "same-wire-id", () => {})
    await expect(second.attach(created.id, "same-wire-id", () => {})).rejects.toThrow("target viewer limit")
    viewer.close()
    await first.hello()
    const replacement = await second.attach(created.id, "same-wire-id", () => {})
    replacement.close()
  })

  test("rolls back a global viewer reservation when attach hangs and its connection closes", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-viewer-reservation-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock"), secret = Buffer.alloc(32, 13).toString("base64")
    const memory = createMemorySessionBackendHarness().backend
    let attaches = 0
    const backend = { ...memory, attach: async (...args: Parameters<typeof memory.attach>) => {
      if (attaches++ === 0) return await new Promise<Awaited<ReturnType<typeof memory.attach>>>(() => {})
      return memory.attach(...args)
    } }
    const server = await startSessiondServer({ endpoint, secret, backend, maxViewersPerTarget: 1 }); cleanup.push(() => server.close())
    const first = new SessiondBackend({ endpoint, secret, stateDir: dir, platform: "linux", requestTimeoutMs: 25 })
    const second = new SessiondBackend({ endpoint, secret, stateDir: dir, platform: "linux" })
    cleanup.push(() => first.close()); cleanup.push(() => second.close())
    const created = await first.create({ group: "g", name: "reservation", cwd: dir, argv: ["fake"], env: {} })
    await expect(first.attach(created.id, "hung", () => {})).rejects.toThrow("timed out")
    const replacement = await second.attach(created.id, "healthy", () => {})
    replacement.close()
  })

  test("tolerates split and coalesced frames while ignoring malformed events", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-framing-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock"), secret = Buffer.alloc(32, 5).toString("base64")
    let responseCount = 0
    const rawServer = createServer(socket => {
      let buffer: Buffer = Buffer.alloc(0)
      socket.on("data", chunk => {
        buffer = Buffer.concat([buffer, Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)])
        const decoded = decodeFrames(buffer); buffer = decoded.rest
        for (const input of decoded.messages) {
          const id = (input as { id: string }).id
          const reply = encodeFrame({ id, ok: true, value: { version: 1, healthy: true } })
          responseCount++
          if (responseCount === 1) {
            socket.write(reply.subarray(0, 2))
            setTimeout(() => socket.write(reply.subarray(2)), 2)
          } else {
            const largeBase = { id: "unmatched-large", ok: true }
            const overhead = Buffer.byteLength(JSON.stringify({ ...largeBase, padding: "" }))
            const large = encodeFrame({ ...largeBase, padding: "x".repeat(SESSIOND_MAX_FRAME_BYTES - overhead) })
            socket.write(Buffer.concat([large, encodeFrame({ event: "data", targetId: 7, viewerId: null, dataBase64: "%%%" }), reply]))
          }
        }
      })
    })
    await new Promise<void>((resolveListen, reject) => {
      rawServer.once("error", reject); rawServer.listen(endpoint, resolveListen)
    })
    cleanup.push(() => new Promise<void>(resolveClose => rawServer.close(() => resolveClose())))
    const client = new SessiondBackend({ endpoint, secret, stateDir: dir, platform: "linux" }); cleanup.push(() => client.close())
    expect((await client.hello()).healthy).toBe(true)
    expect(responseCount).toBe(2)
  })

  test("supports large valid captures and bounds oversized inbound and outbound RPC frames", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-large-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock"), secret = Buffer.alloc(32, 10).toString("base64")
    const memory = createMemorySessionBackendHarness().backend
    const valid = "x".repeat(1_200_000)
    let oversized = false
    const backend = { ...memory, capture: async () => oversized ? "y".repeat(SESSIOND_MAX_FRAME_BYTES + 1024) : valid }
    const server = await startSessiondServer({ endpoint, secret, backend }); cleanup.push(() => server.close())
    const client = new SessiondBackend({ endpoint, secret, stateDir: dir, platform: "linux" }); cleanup.push(() => client.close())
    const captured = await client.capture("target")
    expect(captured).not.toBeNull()
    expect(captured!.length).toBe(valid.length)
    oversized = true
    await expect(client.capture("target")).rejects.toThrow("frame")
    expect((await client.hello()).healthy).toBe(true)
    await expect(client.write("target", Buffer.alloc(SESSIOND_MAX_FRAME_BYTES))).rejects.toThrow("frame")
    expect((await client.hello()).healthy).toBe(true)
  })

  test("rejects invalid PIDs, inconsistent targets, and non-void operation responses", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-invalid-values-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock"), secret = Buffer.alloc(32, 12).toString("base64")
    const rawServer = createServer(socket => {
      let buffer: Buffer = Buffer.alloc(0)
      socket.on("data", chunk => {
        buffer = Buffer.concat([buffer, Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)])
        const decoded = decodeFrames(buffer); buffer = decoded.rest
        for (const input of decoded.messages) {
          const request = input as { id: string; op: string }
          const value = request.op === "hello" ? { version: 1, healthy: true }
            : request.op === "livePid" ? -1
            : request.op === "create" ? { id: "bad", name: "bad", pid: null, alive: true }
            : "unexpected-value"
          socket.write(encodeFrame({ id: request.id, ok: true, value }))
        }
      })
    })
    await new Promise<void>((resolveListen, reject) => { rawServer.once("error", reject); rawServer.listen(endpoint, resolveListen) })
    cleanup.push(() => new Promise<void>(resolveClose => rawServer.close(() => resolveClose())))
    const client = new SessiondBackend({ endpoint, secret, stateDir: dir, platform: "linux" }); cleanup.push(() => client.close())
    await expect(client.livePid("bad")).rejects.toThrow("invalid PID")
    await expect(client.create({ group: "g", name: "n", cwd: dir, argv: ["x"], env: {} })).rejects.toThrow("invalid runtime target")
    await expect(client.write("bad", new Uint8Array())).rejects.toThrow("invalid write response")
  })
})
