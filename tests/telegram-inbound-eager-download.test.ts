import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { FileStore } from "../src/core/files/store"
import { normalizeTelegramInbound } from "../src/channels/telegram/inbound"

let tmpDir: string
let store: FileStore

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-tg-inb-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  store = new FileStore(db, join(tmpDir, "files"))
})
afterEach(() => { rmSync(tmpDir, { recursive: true, force: true }) })

test("photo inbound: registers synthetic file_id; bytes survive", async () => {
  const fakeBytes = Buffer.from([10, 20, 30, 40])
  const fakeApi = {
    getFile: async (_file_id: string) => ({ file_path: "photos/abc.png", file_size: fakeBytes.length }),
    fetchFile: async (_: string) => fakeBytes,
    token: "test-token",
  }

  const inbound = await normalizeTelegramInbound({
    ctx: {
      message: {
        message_id: 5,
        date: 1700000000,
        from: { id: 1, username: "u" },
        photo: [{ file_id: "TG-PHOTO-ID", file_size: fakeBytes.length }],
      },
      chat: { id: 99, type: "private" },
    },
    api: fakeApi,
    fileStore: store,
  })

  expect(inbound.attachments?.length).toBe(1)
  const att = inbound.attachments![0]!
  expect(att.file_id).toMatch(/^[0-9a-f]{32}$/)
  expect(att.file_id).not.toBe("TG-PHOTO-ID")
  expect(att.kind).toBe("photo")
  const meta = await store.get(att.file_id)
  expect(meta).not.toBeNull()
  expect(readFileSync(meta!.path)).toEqual(fakeBytes)
})

test("download failure: drops the offending attachment, keeps the message", async () => {
  const fakeApi = {
    getFile: async (_: string) => { throw new Error("network gone") },
    token: "test-token",
  } as any

  const inbound = await normalizeTelegramInbound({
    ctx: {
      message: {
        message_id: 5,
        date: 1700000000,
        from: { id: 1 },
        text: "hello",
        photo: [{ file_id: "TG-DEAD" }],
      },
      chat: { id: 99, type: "private" },
    },
    api: fakeApi,
    fileStore: store,
  })

  expect(inbound.text).toBe("hello")
  expect(inbound.attachments).toBeUndefined()
  expect(inbound.chat_id).toBe("telegram:99")
})

test("document inbound: preserves mime/name; downloads bytes", async () => {
  const fakeBytes = Buffer.from("hello world", "utf8")
  const fakeApi = {
    getFile: async (_file_id: string) => ({ file_path: "documents/report.pdf", file_size: fakeBytes.length }),
    fetchFile: async (_: string) => fakeBytes,
    token: "test-token",
  }

  const inbound = await normalizeTelegramInbound({
    ctx: {
      message: {
        message_id: 7,
        date: 1700000000,
        from: { id: 2 },
        document: {
          file_id: "TG-DOC-ID",
          file_size: fakeBytes.length,
          mime_type: "application/pdf",
          file_name: "report.pdf",
        },
      },
      chat: { id: 99, type: "private" },
    },
    api: fakeApi,
    fileStore: store,
  })

  const att = inbound.attachments?.[0]
  expect(att?.kind).toBe("document")
  expect(att?.mime).toBe("application/pdf")
  expect(att?.name).toBe("report.pdf")
  expect(att?.file_id).toMatch(/^[0-9a-f]{32}$/)
})

test("text-only message: no attachments, no download", async () => {
  let getFileCalls = 0
  const fakeApi = {
    getFile: async (_: string) => { getFileCalls++; return { file_path: "x" } },
    token: "t",
  } as any

  const inbound = await normalizeTelegramInbound({
    ctx: {
      message: { message_id: 1, date: 1700000000, from: { id: 1 }, text: "just text" },
      chat: { id: 99, type: "private" },
    },
    api: fakeApi,
    fileStore: store,
  })

  expect(inbound.text).toBe("just text")
  expect(inbound.attachments).toBeUndefined()
  expect(getFileCalls).toBe(0)
})
