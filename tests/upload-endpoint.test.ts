// tests/upload-endpoint.test.ts
import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
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
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-upload-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  store = new FileStore(db, join(tmpDir, "files"))

  port = 0
  const devicesFile = join(tmpDir, "devices.json")
  const ds = new DeviceStore(devicesFile)
  token = ds.mint("iphone").token

  channel = new WebChannel({
    port, devicesFile,
    publicUrl: `http://127.0.0.1:${port}`,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    fileStore: store,
  } as any)
  await channel.start()
  port = channel.boundPort
})
afterEach(async () => {
  await channel.stop()
  rmSync(tmpDir, { recursive: true, force: true })
})

function multipart(fields: Record<string, string | Blob>): { body: FormData } {
  const fd = new FormData()
  for (const [k, v] of Object.entries(fields)) fd.append(k, v as any)
  return { body: fd }
}

test("POST /upload returns 401 without bearer", async () => {
  const { body } = multipart({ session: "ana", file: new Blob([new Uint8Array([1,2,3])], { type: "image/png" }) })
  const res = await fetch(`http://127.0.0.1:${port}/upload`, { method: "POST", body })
  expect(res.status).toBe(401)
})

test("POST /upload stores bytes; returns file_id + size + mime", async () => {
  // Browsers send <input type=file> as a File; Bun's req.formData() preserves
  // the part's Content-Type for File parts but drops it for raw Blob parts.
  // Use File here to mirror real client behavior and exercise the mime path.
  const fd = new FormData()
  fd.append("session", "ana")
  fd.append("kind", "photo")
  fd.append("file", new File([new Uint8Array([1,2,3,4,5])], "shot.png", { type: "image/png" }))
  const res = await fetch(`http://127.0.0.1:${port}/upload`, { method: "POST", body: fd, headers: { Cookie: `cmux_token=${token}` } })
  expect(res.status).toBe(200)
  const out = await res.json() as any
  expect(out.file_id).toMatch(/^[0-9a-f]{32}$/)
  expect(out.size).toBe(5)
  expect(out.mime).toBe("image/png")
  expect(out.name).toBe("shot.png")

  const meta = await store.get(out.file_id)
  expect(meta).not.toBeNull()
  expect(meta!.size).toBe(5)
})

test("POST /upload rejects missing session field", async () => {
  const { body } = multipart({ file: new Blob([new Uint8Array([1])], { type: "image/png" }) })
  const res = await fetch(`http://127.0.0.1:${port}/upload`, { method: "POST", body, headers: { Cookie: `cmux_token=${token}` } })
  expect(res.status).toBe(400)
})

test("POST /upload enforces the 25MB cap", async () => {
  const big = new Uint8Array(26 * 1024 * 1024)
  const { body } = multipart({ session: "ana", file: new Blob([big], { type: "application/octet-stream" }) })
  const res = await fetch(`http://127.0.0.1:${port}/upload`, { method: "POST", body, headers: { Cookie: `cmux_token=${token}` } })
  expect(res.status).toBe(413)
})

test("POST /upload ignores an invalid kind hint and falls back to mime-derived kind", async () => {
  const fd = new FormData()
  fd.append("session", "ana")
  fd.append("kind", "banana") // not in AttachmentKind union
  fd.append("file", new File([new Uint8Array([1,2,3])], "shot.png", { type: "image/png" }))
  const res = await fetch(`http://127.0.0.1:${port}/upload`, { method: "POST", body: fd, headers: { Cookie: `cmux_token=${token}` } })
  expect(res.status).toBe(200)
  const out = await res.json() as any
  const row = ((store as any).db).prepare("SELECT kind FROM attachments WHERE file_id = ?").get(out.file_id) as { kind: string }
  expect(row.kind).toBe("photo") // derived from image/png, not "banana"
})

test("POST /upload rejects empty files with 400", async () => {
  const fd = new FormData()
  fd.append("session", "ana")
  fd.append("file", new File([new Uint8Array(0)], "empty.png", { type: "image/png" }))
  const res = await fetch(`http://127.0.0.1:${port}/upload`, { method: "POST", body: fd, headers: { Cookie: `cmux_token=${token}` } })
  expect(res.status).toBe(400)
})

test("POST /upload binds device on the attachments row", async () => {
  const { body } = multipart({
    session: "ana",
    file: new Blob([new Uint8Array([7])], { type: "application/pdf" }),
  })
  const res = await fetch(`http://127.0.0.1:${port}/upload`, { method: "POST", body, headers: { Cookie: `cmux_token=${token}` } })
  const out = await res.json() as any
  const row = ((store as any).db).prepare("SELECT device, origin FROM attachments WHERE file_id = ?").get(out.file_id) as { device: string; origin: string }
  expect(row.device).toBe("iphone")
  expect(row.origin).toBe("web-upload")
})
