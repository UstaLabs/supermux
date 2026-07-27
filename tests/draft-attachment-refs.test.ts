// tests/draft-attachment-refs.test.ts
// Draft payloads hold durable file_ids; FileStore ref_count must track them so
// the hourly GC sweep does not reap staged draft attachments.
import { test, expect } from "bun:test"
import { Database } from "bun:sqlite"
import { mkdtempSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { MIGRATIONS } from "../src/core/storage/migrations"
import { SessionStore } from "../src/core/session-manager/session-store"
import { FileStore } from "../src/core/files/store"

function migratedDb(): Database {
  const db = new Database(":memory:")
  db.exec("PRAGMA foreign_keys = ON")
  for (const m of MIGRATIONS) db.exec(m.sql)
  return db
}

function refCount(db: Database, file_id: string): number {
  const row = db.prepare("SELECT ref_count FROM attachments WHERE file_id = ?").get(file_id) as { ref_count: number } | null
  return row?.ref_count ?? -1
}

test("draft payload file_ids: bump on create, release on delete", async () => {
  const db = migratedDb()
  const root = mkdtempSync(join(tmpdir(), "mux-draft-att-"))
  try {
    const files = new FileStore(db, root)
    const sessions = new SessionStore(db)

    const put = await files.put({
      kind: "document",
      mime: "text/plain",
      name: "notes.txt",
      bytes: Buffer.from("hello draft"),
      origin: "web-upload",
      session: "draft",
    })
    expect(refCount(db, put.file_id)).toBe(0)

    const draft = sessions.register({
      name: "draft-1",
      agent: "claude",
      workdir: "/tmp",
      pid: 0,
      user_status: "draft",
      draft_payload: {
        text: "ship it",
        attachments: [{ file_id: put.file_id, name: "notes.txt", mime: "text/plain", size: put.size }],
      },
    })
    // Mimic createDraft: hold a ref while the draft exists.
    await files.bumpRef(put.file_id)
    expect(refCount(db, put.file_id)).toBe(1)

    // Mimic killSession(draft): release then hard-delete.
    await files.release(put.file_id)
    sessions.deleteById(draft.id)
    expect(sessions.getById(draft.id)).toBeUndefined()
    expect(refCount(db, put.file_id)).toBe(0)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("draft payload round-trips attachment metadata including size", () => {
  const db = migratedDb()
  const sessions = new SessionStore(db)
  const payload = {
    text: "with file",
    attachments: [{ file_id: "abc123", name: "a.png", mime: "image/png", size: 42 }],
  }
  const s = sessions.register({
    name: "d2", agent: "claude", workdir: "/tmp", pid: 0,
    user_status: "draft", draft_payload: payload,
  })
  expect(sessions.getById(s.id)!.draft_payload).toEqual(payload)
})
