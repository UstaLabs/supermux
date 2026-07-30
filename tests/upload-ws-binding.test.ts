// tests/upload-ws-binding.test.ts
import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { FileStore } from "../src/core/files/store"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"
import type { InboundMessage } from "../src/channels/channel"

let tmpDir: string
let port: number
let channel: WebChannel
let tokenA: string, tokenB: string
let store: FileStore
let received: InboundMessage[] = []

beforeEach(async () => {
  __resetAuthFailures()
  received = []
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-upload-ws-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  store = new FileStore(db, join(tmpDir, "files"))

  const devicesFile = join(tmpDir, "devices.json")
  const ds = new DeviceStore(devicesFile)
  tokenA = ds.mint("iphone").token
  tokenB = ds.mint("laptop").token

  // port 0 = let the OS pick a free one, then read it back off the channel.
  // A random pick from a fixed 500-wide range collides under a full `bun test`
  // run and fails with EADDRINUSE for reasons unrelated to the code under test —
  // which is exactly how a gate earns a reputation for being flaky.
  channel = new WebChannel({
    port: 0, devicesFile,
    publicUrl: "http://127.0.0.1",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: (m: InboundMessage) => { received.push(m) },
    fileStore: store,
  } as any)
  await channel.start()
  port = channel.boundPort
})
afterEach(async () => {
  await channel.stop()
  rmSync(tmpDir, { recursive: true, force: true })
})

async function uploadFor(token: string): Promise<string> {
  const fd = new FormData()
  fd.append("session", "ana")
  fd.append("file", new File([new Uint8Array([1,2,3])], "x.png", { type: "image/png" }))
  const res = await fetch(`http://127.0.0.1:${port}/upload`, { method: "POST", body: fd, headers: { Cookie: `cmux_token=${token}` } })
  const { file_id } = await res.json() as any
  return file_id
}

function openWs(token: string): Promise<WebSocket> {
  return new Promise((resolve) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`, { headers: { Cookie: `cmux_token=${token}` } })
    ws.addEventListener("open", () => resolve(ws), { once: true })
  })
}

test("send frame with this-device's file_id is accepted", async () => {
  const file_id = await uploadFor(tokenA)
  const ws = await openWs(tokenA)
  ws.send(JSON.stringify({ type: "send", session: "ana", op: "reply", args: { text: "look", attachments: [file_id] } }))
  await new Promise((r) => setTimeout(r, 150))
  expect(received.length).toBe(1)
  expect(received[0]!.attachments?.[0]?.file_id).toBe(file_id)
  expect(received[0]!.attachments?.[0]?.kind).toBe("photo")
  ws.close()
})

test("send frame with another device's file_id is rejected", async () => {
  const file_id = await uploadFor(tokenA)
  const ws = await openWs(tokenB)
  let err: any = null
  ws.addEventListener("message", (e) => {
    const frame = JSON.parse(String(e.data))
    if (frame.type === "error") err = frame
  })
  ws.send(JSON.stringify({ type: "send", session: "ana", op: "reply", args: { text: "snoop", attachments: [file_id] } }))
  await new Promise((r) => setTimeout(r, 200))
  expect(received.length).toBe(0)
  expect(err?.reason).toBe("invalid attachment reference")
  ws.close()
})

test("send frame with unknown file_id is rejected", async () => {
  const ws = await openWs(tokenA)
  let err: any = null
  ws.addEventListener("message", (e) => {
    const frame = JSON.parse(String(e.data))
    if (frame.type === "error") err = frame
  })
  ws.send(JSON.stringify({ type: "send", session: "ana", op: "reply", args: { text: "x", attachments: ["0".repeat(32)] } }))
  await new Promise((r) => setTimeout(r, 200))
  expect(received.length).toBe(0)
  expect(err?.reason).toBe("invalid attachment reference")
  ws.close()
})
