import { describe, expect, test } from "bun:test"
import { mkdtempSync, readFileSync, existsSync, readdirSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { FileStore, PayloadTooLargeError, EmptyUploadError, OffsetConflictError, UploadOverflowError, UploadNotFoundError } from "./store"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"

function makeStore(): { store: FileStore; root: string } {
  const root = mkdtempSync(join(tmpdir(), "mux-filestore-"))
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return { store: new FileStore(db, root), root }
}

function streamOf(chunks: Uint8Array[]): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const c of chunks) controller.enqueue(c)
      controller.close()
    },
  })
}

// Recursively list every regular file under `dir` (proves no orphan .part or
// final file survives a failed putStream).
function allFiles(dir: string): string[] {
  const out: string[] = []
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name)
    if (e.isDirectory()) out.push(...allFiles(p))
    else out.push(p)
  }
  return out
}

describe("FileStore.putStream", () => {
  test("happy path: concatenates chunks, stores observed size, leaves no .part", async () => {
    const { store, root } = makeStore()
    const stream = streamOf([Buffer.from("hello "), Buffer.from("world")])
    const { file_id, size } = await store.putStream(
      { kind: "video", mime: "video/mp4", name: "clip.mp4", session: "s1", device: "d1", origin: "web-upload", maxBytes: 1024 },
      stream,
    )
    expect(file_id).toMatch(/^[0-9a-f]{32}$/)
    expect(size).toBe(11)

    const meta = await store.get(file_id)
    expect(meta).toMatchObject({ kind: "video", mime: "video/mp4", name: "clip.mp4", size: 11 })
    expect(readFileSync(meta!.path).toString()).toBe("hello world")
    expect(existsSync(`${meta!.path}.part`)).toBe(false)
    expect(allFiles(root).length).toBe(1)
  })

  test("cap abort: total over maxBytes throws PayloadTooLargeError and leaves no file", async () => {
    const { store, root } = makeStore()
    const stream = streamOf([new Uint8Array(60), new Uint8Array(60)]) // 120 > 100
    let thrown: any
    try {
      await store.putStream({ kind: "video", mime: "video/mp4", origin: "web-upload", maxBytes: 100 }, stream)
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(PayloadTooLargeError)
    expect(thrown.code).toBe("PAYLOAD_TOO_LARGE")
    expect(allFiles(root)).toEqual([])
  })

  test("empty stream: zero bytes throws EmptyUploadError and leaves no file", async () => {
    const { store, root } = makeStore()
    let thrown: any
    try {
      await store.putStream({ kind: "video", mime: "video/mp4", origin: "web-upload", maxBytes: 1024 }, streamOf([]))
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(EmptyUploadError)
    expect(thrown.code).toBe("EMPTY_UPLOAD")
    expect(allFiles(root)).toEqual([])
  })

  test("insert-failure cleanup: unlinks the final file and rethrows", async () => {
    const root = mkdtempSync(join(tmpdir(), "mux-filestore-"))
    // A db stub whose INSERT throws — proves the post-rename cleanup path.
    const failingDb = { prepare: () => ({ run: () => { throw new Error("insert boom") } }) } as any
    const store = new FileStore(failingDb, root)
    let thrown: any
    try {
      await store.putStream(
        { kind: "video", mime: "video/mp4", origin: "web-upload", maxBytes: 1024 },
        streamOf([Buffer.from("data")]),
      )
    } catch (e) {
      thrown = e
    }
    expect(String(thrown?.message)).toContain("insert boom")
    expect(allFiles(root)).toEqual([])
  })
})

describe("pending_uploads migration", () => {
  test("creates the pending_uploads table", () => {
    const db = openDb(":memory:")
    runMigrations(db, MIGRATIONS)
    const row = db.prepare(
      "SELECT name FROM sqlite_master WHERE type='table' AND name='pending_uploads'",
    ).get() as { name: string } | undefined
    expect(row?.name).toBe("pending_uploads")
  })
})

describe("FileStore.createPending", () => {
  test("creates an empty .part file, a pending row, and returns id + chunk_size", async () => {
    const { store, root } = makeStore()
    const { upload_id, chunk_size } = await store.createPending({
      session: "s1", kind: "video", mime: "video/mp4", name: "clip.mp4",
      totalSize: 1000, device: "d1", origin: "web-upload",
    })
    expect(upload_id).toMatch(/^[0-9a-f]{32}$/)
    expect(chunk_size).toBe(5 * 1024 * 1024)
    expect(await store.pendingOffset(upload_id)).toBe(0)
    expect(allFiles(root).length).toBe(1)
    expect(allFiles(root).some((f) => f.endsWith(".part"))).toBe(true)
  })
})

describe("FileStore.appendChunk", () => {
  async function begin(totalSize: number) {
    const made = makeStore()
    const { upload_id } = await made.store.createPending({
      session: "s1", kind: "video", mime: "video/mp4", totalSize, origin: "web-upload",
    })
    return { ...made, upload_id }
  }

  test("appends in order, reports received + done on the last chunk", async () => {
    const { store, upload_id } = await begin(6)
    const r1 = await store.appendChunk(upload_id, 0, Buffer.from("abc"))
    expect(r1).toEqual({ received: 3, done: false })
    const r2 = await store.appendChunk(upload_id, 3, Buffer.from("def"))
    expect(r2).toEqual({ received: 6, done: true })
    expect(await store.pendingOffset(upload_id)).toBe(6)
  })

  test("wrong offset throws OffsetConflictError carrying the true offset", async () => {
    const { store, upload_id } = await begin(6)
    await store.appendChunk(upload_id, 0, Buffer.from("abc"))
    let thrown: any
    try { await store.appendChunk(upload_id, 0, Buffer.from("xxx")) } catch (e) { thrown = e }
    expect(thrown).toBeInstanceOf(OffsetConflictError)
    expect(thrown.offset).toBe(3)
  })

  test("overflow past total_size throws UploadOverflowError and stores nothing extra", async () => {
    const { store, upload_id } = await begin(4)
    await store.appendChunk(upload_id, 0, Buffer.from("ab"))
    let thrown: any
    try { await store.appendChunk(upload_id, 2, Buffer.from("cde")) } catch (e) { thrown = e } // 2+3 > 4
    expect(thrown).toBeInstanceOf(UploadOverflowError)
    expect(await store.pendingOffset(upload_id)).toBe(2)
  })

  test("unknown upload_id throws UploadNotFoundError", async () => {
    const { store } = await begin(4)
    let thrown: any
    try { await store.appendChunk("deadbeef".repeat(4), 0, Buffer.from("x")) } catch (e) { thrown = e }
    expect(thrown).toBeInstanceOf(UploadNotFoundError)
  })
})

describe("FileStore.pendingOffset", () => {
  test("returns the byte count for an in-flight upload, null for unknown", async () => {
    const { store } = makeStore()
    const { upload_id } = await store.createPending({
      session: "s1", kind: "video", mime: "video/mp4", totalSize: 10, origin: "web-upload",
    })
    expect(await store.pendingOffset(upload_id)).toBe(0)
    await store.appendChunk(upload_id, 0, Buffer.from("hello"))
    expect(await store.pendingOffset(upload_id)).toBe(5)
    expect(await store.pendingOffset("00000000000000000000000000000000")).toBeNull()
  })
})

describe("FileStore.finalizePending", () => {
  test("renames .part to final, inserts attachments row keyed by upload_id, drops pending row", async () => {
    const { store, root } = makeStore()
    const { upload_id } = await store.createPending({
      session: "s1", kind: "video", mime: "video/mp4", name: "clip.mp4",
      totalSize: 11, device: "d1", origin: "web-upload",
    })
    await store.appendChunk(upload_id, 0, Buffer.from("hello "))
    await store.appendChunk(upload_id, 6, Buffer.from("world"))
    const fin = await store.finalizePending(upload_id)
    expect(fin).toMatchObject({ file_id: upload_id, size: 11, mime: "video/mp4", name: "clip.mp4", kind: "video" })

    const meta = await store.get(upload_id)
    expect(meta).toMatchObject({ kind: "video", size: 11 })
    expect(readFileSync(meta!.path).toString()).toBe("hello world")
    expect(allFiles(root).length).toBe(1)
    expect(allFiles(root).some((f) => f.endsWith(".part"))).toBe(false)
    expect(await store.pendingOffset(upload_id)).toBeNull()

    // finalized file carries the device→file_id binding so a web send frame
    // can validate ownership (parity with the single-POST path).
    expect(await store.resolveOwnedWebUpload(upload_id, "d1")).toMatchObject({ file_id: upload_id, kind: "video" })
    expect(await store.resolveOwnedWebUpload(upload_id, "other-device")).toBeNull()
  })

  test("unknown upload_id throws UploadNotFoundError", async () => {
    const { store } = makeStore()
    let thrown: any
    try { await store.finalizePending("00000000000000000000000000000000") } catch (e) { thrown = e }
    expect(thrown).toBeInstanceOf(UploadNotFoundError)
  })
})

describe("FileStore.gcPendingOnce", () => {
  test("reaps a partial older than the TTL and leaves a fresh one", async () => {
    const { store, root } = makeStore()
    const stale = await store.createPending({ session: "s1", kind: "video", mime: "video/mp4", totalSize: 10, origin: "web-upload" })
    const fresh = await store.createPending({ session: "s1", kind: "video", mime: "video/mp4", totalSize: 10, origin: "web-upload" })
    ;(store as any).db.prepare(
      "UPDATE pending_uploads SET created_at = datetime('now', '-48 hours') WHERE upload_id = ?",
    ).run(stale.upload_id)

    const reaped = await store.gcPendingOnce({ ttlHours: 24 })
    expect(reaped).toBe(1)
    expect(await store.pendingOffset(stale.upload_id)).toBeNull()
    expect(await store.pendingOffset(fresh.upload_id)).toBe(0)
    expect(allFiles(root).length).toBe(1)
  })
})
