import { afterEach, describe, expect, test } from "bun:test"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createConnection, createServer, type Socket } from "node:net"
import { createMemorySessionBackendHarness, verifySessionBackendContract } from "../runtime/session-backend.test-support"
import { decodeFrames, encodeFrame } from "../../shared/frame-codec"
import { SessiondBackend } from "./client"
import { startSessiondServer, type SessiondServer } from "./server"

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

  test("reconnects after the server forcibly closes broker-side sockets", async () => {
    const { client, server } = await harness()
    expect((await client.hello()).version).toBe(1)
    server.closeConnections()
    await new Promise(resolve => setTimeout(resolve, 20))
    expect((await client.hello()).version).toBe(1)
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
            socket.write(Buffer.concat([encodeFrame({ event: "data", targetId: 7, viewerId: null, dataBase64: "%%%" }), reply]))
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
})
