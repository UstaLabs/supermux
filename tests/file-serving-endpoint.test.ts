// tests/file-serving-endpoint.test.ts
import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, unlinkSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { FileStore } from "../src/core/files/store"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

let tmpDir: string
let port: number
let channel: WebChannel
let token: string
let store: FileStore

beforeEach(async () => {
  __resetAuthFailures()
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-files-ep-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  store = new FileStore(db, join(tmpDir, "files"))

  port = 38800 + Math.floor(Math.random() * 500)
  const devicesFile = join(tmpDir, "devices.json")
  const ds = new DeviceStore(devicesFile)
  const minted = ds.mint("iphone")
  token = minted.token

  channel = new WebChannel({
    port,
    devicesFile,
    publicUrl: `http://127.0.0.1:${port}`,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    fileStore: store,
  } as any)
  await channel.start()
})
afterEach(async () => {
  await channel.stop()
  rmSync(tmpDir, { recursive: true, force: true })
})

test("GET /files/:id returns 401 without bearer", async () => {
  const { file_id } = await store.put({ kind: "photo", mime: "image/png", origin: "web-upload", bytes: new Uint8Array([1, 2, 3]) })
  const res = await fetch(`http://127.0.0.1:${port}/files/${file_id}`)
  expect(res.status).toBe(401)
})

test("GET /files/:id returns bytes with correct Content-Type", async () => {
  const { file_id } = await store.put({ kind: "photo", mime: "image/png", origin: "web-upload", bytes: new Uint8Array([1, 2, 3, 4]) })
  const res = await fetch(`http://127.0.0.1:${port}/files/${file_id}`, { headers: { Cookie: `cmux_token=${token}` } })
  expect(res.status).toBe(200)
  expect(res.headers.get("content-type")).toBe("image/png")
  const buf = new Uint8Array(await res.arrayBuffer())
  expect(Array.from(buf)).toEqual([1, 2, 3, 4])
})

test("GET /files/:id honors Range request", async () => {
  const bytes = new Uint8Array([10, 20, 30, 40, 50])
  const { file_id } = await store.put({ kind: "document", mime: "application/octet-stream", origin: "web-upload", bytes })
  const res = await fetch(`http://127.0.0.1:${port}/files/${file_id}`, { headers: { Cookie: `cmux_token=${token}`, range: "bytes=1-3" } })
  expect(res.status).toBe(206)
  expect(res.headers.get("content-range")).toBe("bytes 1-3/5")
  const buf = new Uint8Array(await res.arrayBuffer())
  expect(Array.from(buf)).toEqual([20, 30, 40])
})

test("GET /files/:id returns 404 for unknown id", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/files/${"0".repeat(32)}`, { headers: { Cookie: `cmux_token=${token}` } })
  expect(res.status).toBe(404)
})

test("Content-Disposition includes name when set", async () => {
  const { file_id } = await store.put({ kind: "document", mime: "application/pdf", name: "report.pdf", origin: "session-outbound", bytes: new Uint8Array([0]) })
  const res = await fetch(`http://127.0.0.1:${port}/files/${file_id}`, { headers: { Cookie: `cmux_token=${token}` } })
  expect(res.headers.get("content-disposition")).toContain(`filename="report.pdf"`)
})

test("GET /files/:id honors open-ended Range request", async () => {
  const bytes = new Uint8Array([10, 20, 30, 40, 50])
  const { file_id } = await store.put({ kind: "document", mime: "application/octet-stream", origin: "web-upload", bytes })
  const res = await fetch(`http://127.0.0.1:${port}/files/${file_id}`, { headers: { Cookie: `cmux_token=${token}`, range: "bytes=2-" } })
  expect(res.status).toBe(206)
  expect(res.headers.get("content-range")).toBe("bytes 2-4/5")
  const buf = new Uint8Array(await res.arrayBuffer())
  expect(Array.from(buf)).toEqual([30, 40, 50])
})

test("GET /files/:id returns 404 when on-disk file is missing", async () => {
  // Row exists, but the file is gone (e.g. someone rm'd it, or a partial GC).
  // Without the eager existsSync check the handler returns a Bun.file() body
  // that errors at TCP-flush time — never produces an HTTP 404.
  const { file_id } = await store.put({ kind: "photo", mime: "image/png", origin: "web-upload", bytes: new Uint8Array([1, 2, 3]) })
  const meta = await store.get(file_id)
  unlinkSync(meta!.path)
  const res = await fetch(`http://127.0.0.1:${port}/files/${file_id}`, { headers: { Cookie: `cmux_token=${token}` } })
  expect(res.status).toBe(404)
})
