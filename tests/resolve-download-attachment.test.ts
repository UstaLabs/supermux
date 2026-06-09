import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { FileStore } from "../src/core/files/store"
import { resolveDownloadAttachment } from "../src/core/session-manager/download"

let tmpDir: string
let store: FileStore

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-rdl-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  store = new FileStore(db, join(tmpDir, "files"))
})
afterEach(() => { rmSync(tmpDir, { recursive: true, force: true }) })

test("synthetic 32-hex file_id resolves directly via FileStore (no network call)", async () => {
  const bytes = Buffer.from([7, 8, 9])
  const { file_id } = await store.put({ kind: "photo", mime: "image/png", origin: "web-upload", bytes })

  let getFileCalls = 0
  const r = await resolveDownloadAttachment({
    file_id,
    fileStore: store,
    telegramApi: { token: "t", getFile: async () => { getFileCalls++; throw new Error("should not be called") } },
    inboxDir: join(tmpDir, "inbox"),
  })
  expect(r.via).toBe("filestore")
  expect(readFileSync(r.path)).toEqual(bytes)
  expect(getFileCalls).toBe(0)
})

test("non-synthetic file_id falls through to telegram", async () => {
  const bytes = Buffer.from([1, 2, 3])
  let getFileCalls = 0
  const r = await resolveDownloadAttachment({
    file_id: "TG-XYZ-ID",
    fileStore: store,
    telegramApi: {
      token: "t",
      getFile: async () => { getFileCalls++; return { file_path: "photos/a.png", file_size: bytes.length } },
      fetchFile: async () => bytes,
    },
    inboxDir: join(tmpDir, "inbox"),
  })
  expect(r.via).toBe("telegram")
  expect(getFileCalls).toBe(1)
  expect(readFileSync(r.path)).toEqual(bytes)
})

test("32-hex file_id that isn't in the store falls through to telegram", async () => {
  // The shape matches synthetic but the row is absent. Verify we don't
  // short-circuit with a misleading 404 — we let telegram speak.
  const bogus = "0".repeat(32)
  const bytes = Buffer.from([42])
  let getFileCalls = 0
  const r = await resolveDownloadAttachment({
    file_id: bogus,
    fileStore: store,
    telegramApi: {
      token: "t",
      getFile: async () => { getFileCalls++; return { file_path: "photos/x.png", file_size: bytes.length } },
      fetchFile: async () => bytes,
    },
    inboxDir: join(tmpDir, "inbox"),
  })
  expect(r.via).toBe("telegram")
  expect(getFileCalls).toBe(1)
})
