// tests/file-store.test.ts
import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, existsSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { FileStore } from "../src/core/files/store"

let tmpDir: string
let store: FileStore

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-fs-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  store = new FileStore(db, join(tmpDir, "files"))
})
afterEach(() => { rmSync(tmpDir, { recursive: true, force: true }) })

test("put writes the file, returns a 32-char hex file_id, and registers a row", async () => {
  const { file_id, size } = await store.put({
    kind: "photo",
    mime: "image/png",
    name: "shot.png",
    session: "ana",
    origin: "web-upload",
    device: "iphone",
    bytes: new Uint8Array([1, 2, 3, 4]),
  })
  expect(file_id).toMatch(/^[0-9a-f]{32}$/)
  expect(size).toBe(4)

  const meta = await store.get(file_id)
  expect(meta).not.toBeNull()
  expect(meta!.size).toBe(4)
  expect(meta!.mime).toBe("image/png")
  expect(meta!.name).toBe("shot.png")
  expect(existsSync(meta!.path)).toBe(true)
  expect(readFileSync(meta!.path)).toEqual(Buffer.from([1, 2, 3, 4]))
})

test("put shards files by first 2 hex chars", async () => {
  const { file_id } = await store.put({ kind: "document", origin: "web-upload", bytes: new Uint8Array([0]) })
  const meta = await store.get(file_id)
  expect(meta!.path).toContain(`/${file_id.slice(0, 2)}/`)
})

test("get returns null for unknown file_id", async () => {
  expect(await store.get("0".repeat(32))).toBeNull()
})

test("ext is derived from mime", async () => {
  const { file_id: a } = await store.put({ kind: "photo", mime: "image/png", origin: "web-upload", bytes: new Uint8Array([0]) })
  const { file_id: b } = await store.put({ kind: "document", mime: undefined, origin: "web-upload", bytes: new Uint8Array([0]) })
  expect((await store.get(a))!.path.endsWith(".png")).toBe(true)
  expect((await store.get(b))!.path.endsWith(".bin")).toBe(true)
})

test("release decrements ref_count", async () => {
  const { file_id } = await store.put({ kind: "photo", origin: "web-upload", bytes: new Uint8Array([0]) })
  // Manually bump ref_count to simulate a referencing messages row
  ;(store as any).db.prepare("UPDATE attachments SET ref_count = 1 WHERE file_id = ?").run(file_id)
  const ok = await store.release(file_id)
  expect(ok).toBe(true)
  const row = (store as any).db.prepare("SELECT ref_count FROM attachments WHERE file_id = ?").get(file_id) as { ref_count: number }
  expect(row.ref_count).toBe(0)
})

test("release returns false for unknown file_id", async () => {
  const ok = await store.release("0".repeat(32))
  expect(ok).toBe(false)
})

test("bumpRef returns true for known file_id and false for unknown", async () => {
  const { file_id } = await store.put({ kind: "photo", origin: "web-upload", bytes: new Uint8Array([0]) })
  expect(await store.bumpRef(file_id)).toBe(true)
  expect(await store.bumpRef("0".repeat(32))).toBe(false)
})

test("gc deletes orphaned (ref_count=0) rows older than grace period", async () => {
  const { file_id } = await store.put({ kind: "photo", origin: "web-upload", bytes: new Uint8Array([0]) })
  // Backdate created_at to before grace
  ;(store as any).db.prepare("UPDATE attachments SET created_at = datetime('now', '-2 days') WHERE file_id = ?").run(file_id)
  const meta = await store.get(file_id)
  await store.gcOnce({ graceHours: 24 })
  expect(await store.get(file_id)).toBeNull()
  expect(existsSync(meta!.path)).toBe(false)
})

test("gc does not delete recent orphans within the grace period", async () => {
  const { file_id } = await store.put({ kind: "photo", origin: "web-upload", bytes: new Uint8Array([0]) })
  await store.gcOnce({ graceHours: 24 })
  expect(await store.get(file_id)).not.toBeNull()
})

test("gc does not delete referenced (ref_count>0) rows even if old", async () => {
  const { file_id } = await store.put({ kind: "photo", origin: "web-upload", bytes: new Uint8Array([0]) })
  ;(store as any).db.prepare("UPDATE attachments SET ref_count = 1, created_at = datetime('now', '-30 days') WHERE file_id = ?").run(file_id)
  await store.gcOnce({ graceHours: 24 })
  expect(await store.get(file_id)).not.toBeNull()
})

test("resolveOwnedWebUpload returns null for wrong device", async () => {
  const { file_id } = await store.put({ kind: "photo", origin: "web-upload", device: "iphone", bytes: new Uint8Array([0]) })
  expect(await store.resolveOwnedWebUpload(file_id, "iphone")).not.toBeNull()
  expect(await store.resolveOwnedWebUpload(file_id, "laptop")).toBeNull()
  expect(await store.resolveOwnedWebUpload(file_id, "iphone")).not.toBeNull()
})

test("resolveOwnedWebUpload returns null for non-web-upload origin", async () => {
  const { file_id } = await store.put({ kind: "photo", origin: "session-outbound", device: "iphone", bytes: new Uint8Array([0]) })
  expect(await store.resolveOwnedWebUpload(file_id, "iphone")).toBeNull()
})
