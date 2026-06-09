// tests/web-attachments-roundtrip.test.ts
import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { FileStore } from "../src/core/files/store"
import { WebChannel } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

let tmpDir: string
let port: number
let channel: WebChannel
let token: string
let fileStore: FileStore
let inboundReceived: any[] = []

beforeEach(async () => {
  inboundReceived = []
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-attach-rt-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  fileStore = new FileStore(db, join(tmpDir, "files"))

  port = 40100 + Math.floor(Math.random() * 500)
  const devicesFile = join(tmpDir, "devices.json")
  const ds = new DeviceStore(devicesFile)
  token = ds.mint("iphone").token

  channel = new WebChannel({
    port,
    devicesFile,
    publicUrl: `http://127.0.0.1:${port}`,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: (msg: any) => { inboundReceived.push(msg) },
    fileStore,
  } as any)
  await channel.start()
})
afterEach(async () => {
  await channel.stop()
  rmSync(tmpDir, { recursive: true, force: true })
})

function openWs(t: string): Promise<WebSocket> {
  return new Promise((resolve) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`, { headers: { Cookie: `cmux_token=${t}` } })
    ws.addEventListener("open", () => resolve(ws), { once: true })
  })
}

test("upload + send → inbound message carries attachments", async () => {
  // 1. Upload
  const fd = new FormData()
  fd.append("session", "ana")
  fd.append("file", new File([new Uint8Array([1, 2, 3, 4, 5])], "shot.png", { type: "image/png" }))
  const upRes = await fetch(`http://127.0.0.1:${port}/upload`, {
    method: "POST", body: fd, headers: { Cookie: `cmux_token=${token}` },
  })
  expect(upRes.status).toBe(200)
  const { file_id } = await upRes.json() as any

  // 2. WS send referencing the file_id
  const ws = await openWs(token)
  ws.send(JSON.stringify({
    type: "send", session: "ana", op: "reply",
    args: { text: "look at this", attachments: [file_id] },
  }))
  await new Promise((r) => setTimeout(r, 100))

  // 3. Assert the inbound message that hit onSendFromWeb has attachments[]
  expect(inboundReceived.length).toBe(1)
  expect(inboundReceived[0].text).toBe("look at this")
  expect(inboundReceived[0].attachments?.length).toBe(1)
  expect(inboundReceived[0].attachments?.[0]?.file_id).toBe(file_id)
  expect(inboundReceived[0].attachments?.[0]?.kind).toBe("photo")

  ws.close()
})

test("GET /files/:id serves uploaded bytes", async () => {
  const fd = new FormData()
  fd.append("session", "ana")
  fd.append("file", new File([new Uint8Array([10, 20, 30])], "x.bin", { type: "application/octet-stream" }))
  const upRes = await fetch(`http://127.0.0.1:${port}/upload`, {
    method: "POST", body: fd, headers: { Cookie: `cmux_token=${token}` },
  })
  const { file_id } = await upRes.json() as any

  const dl = await fetch(`http://127.0.0.1:${port}/files/${file_id}`, {
    headers: { Cookie: `cmux_token=${token}` },
  })
  expect(dl.status).toBe(200)
  const buf = new Uint8Array(await dl.arrayBuffer())
  expect(Array.from(buf)).toEqual([10, 20, 30])
})
