import { afterEach, describe, expect, test } from "bun:test"
import { mkdtemp, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { connect } from "node:net"
import { createMemorySessionBackend } from "../runtime/session-backend.test-support"
import { encodeFrame } from "../../shared/frame-codec"
import { PROTOCOL_VERSION } from "./protocol"
import { SESSIOND_MAX_FRAME_BYTES, startSessiondServer, type SessiondServer } from "./server"

const cleanup: Array<() => void | Promise<void>> = []
afterEach(async () => { for (const close of cleanup.splice(0).reverse()) await close() })

describe("sessiond server", () => {
  test("rejects bad authentication, versions, malformed and oversized frames without dispatch", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-server-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock")
    const server = await startSessiondServer({ endpoint, secret: Buffer.alloc(32, 1).toString("base64"), backend: createMemorySessionBackend() })
    cleanup.push(() => server.close())

    const exchange = (frame: Buffer) => new Promise<Buffer>((resolve, reject) => {
      const socket = connect(endpoint, () => socket.write(frame)); const chunks: Buffer[] = []
      socket.on("data", chunk => chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))); socket.on("close", () => resolve(Buffer.concat(chunks))); socket.on("error", reject)
    })
    const badSecret = await exchange(encodeFrame({ id: "x", version: PROTOCOL_VERSION, secret: "wrong", op: "hello", args: {} }))
    expect(badSecret.toString()).not.toContain("wrong")
    expect(badSecret.toString()).toContain("authentication failed")
    const badVersion = await exchange(encodeFrame({ id: "x", version: 99, secret: Buffer.alloc(32, 1).toString("base64"), op: "hello", args: {} }))
    expect(badVersion.toString()).toContain("unsupported protocol version")
    const malformed = await exchange(encodeFrame({ nope: true }))
    expect(malformed.toString()).toContain("invalid request")
    const unknown = await exchange(encodeFrame({ id: "u", version: PROTOCOL_VERSION, secret: Buffer.alloc(32, 1).toString("base64"), op: "unknown", args: {} }))
    expect(unknown.toString()).toContain("invalid request")
    const invalidJson = Buffer.from([0, 0, 0, 1, 0xff])
    expect((await exchange(invalidJson)).toString()).toContain("malformed frame")
    const oversized = Buffer.alloc(4); oversized.writeUInt32BE(SESSIOND_MAX_FRAME_BYTES + 1)
    expect((await exchange(oversized)).toString()).toContain("frame too large")
  })

  test("does not unlink a live endpoint", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-live-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock")
    const first = await startSessiondServer({ endpoint, secret: "a", backend: createMemorySessionBackend() }); cleanup.push(() => first.close())
    await expect(startSessiondServer({ endpoint, secret: "a", backend: createMemorySessionBackend() })).rejects.toThrow()
  })

  test("refuses to replace a non-socket stale endpoint", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-file-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock")
    await writeFile(endpoint, "keep")
    await expect(startSessiondServer({ endpoint, secret: "a", backend: createMemorySessionBackend() })).rejects.toThrow("non-socket")
    expect(await Bun.file(endpoint).text()).toBe("keep")
  })

  test("closes and removes its own socket when post-listen setup fails", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-postlisten-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock")
    await expect(startSessiondServer({
      endpoint, secret: "a", backend: createMemorySessionBackend(),
      postListenSetup: async () => { throw new Error("injected post-listen failure") },
    })).rejects.toThrow("injected post-listen failure")
    const successor = await startSessiondServer({ endpoint, secret: "a", backend: createMemorySessionBackend() })
    await successor.close()
  })

  test("bounds unauthenticated connections and expires the pre-auth handshake", async () => {
    const dir = await mkdtemp(join(tmpdir(), "sessiond-handshake-")); cleanup.push(() => rm(dir, { recursive: true, force: true }))
    const endpoint = join(dir, "rpc.sock")
    const server = await startSessiondServer({ endpoint, secret: "a", backend: createMemorySessionBackend(), maxConnections: 1, handshakeTimeoutMs: 30 })
    cleanup.push(() => server.close())
    const first = connect(endpoint)
    await new Promise<void>((resolveConnect, reject) => { first.once("connect", resolveConnect); first.once("error", reject) })
    const second = connect(endpoint)
    await new Promise<void>(resolveClose => second.once("close", () => resolveClose()))
    await new Promise<void>(resolveClose => first.once("close", () => resolveClose()))
  })
})
