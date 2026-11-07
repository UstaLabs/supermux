// A stored picture or clip carries its displayed size and a clip its length, read from the file's
// own header when it is stored (or on the first request, for a file stored before this existed),
// and remembered: a file is never read twice. A phone's poster for a clip is linked to it.
import { describe, expect, test } from "bun:test"
import { mkdtempSync, readFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { FileStore } from "./store"
import { probeFileMeta } from "./media-meta"
import { openDb, runMigrations, type Db } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"

const FIX = join(import.meta.dir, "../../../tests/fixtures/media")
const sample = (name: string) => readFileSync(join(FIX, name))

function makeStore(): { store: FileStore; db: Db; probes: string[] } {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const probes: string[] = []
  const store = new FileStore(db, mkdtempSync(join(tmpdir(), "mux-filestore-meta-")), {
    probe: async (path) => {
      probes.push(path)
      return probeFileMeta(path)
    },
  })
  return { store, db, probes }
}

function streamOf(bytes: Uint8Array): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(c) {
      c.enqueue(bytes)
      c.close()
    },
  })
}

describe("FileStore media meta", () => {
  test("a stored picture carries its DISPLAYED size (EXIF orientation applied)", async () => {
    const { store } = makeStore()
    const { file_id } = await store.put({
      kind: "photo", mime: "image/jpeg", name: "p.jpg", origin: "web-upload", bytes: sample("jpeg-exif-orientation6-64x48.jpg"),
    })
    expect(await store.get(file_id)).toMatchObject({ width: 48, height: 64 })
  })

  test("a streamed clip carries its size and length", async () => {
    const { store } = makeStore()
    const { file_id } = await store.putStream(
      { kind: "video", mime: "video/mp4", name: "c.mp4", origin: "session-outbound", maxBytes: 1 << 20 },
      streamOf(new Uint8Array(sample("mp4-moov-at-end-64x48.mp4"))),
    )
    const meta = await store.get(file_id)
    expect(meta).toMatchObject({ width: 64, height: 48 })
    expect(Math.abs((meta?.duration_ms ?? 0) - 1500)).toBeLessThanOrEqual(20)
  })

  test("a chunked upload carries them once it is finalized", async () => {
    const { store } = makeStore()
    const bytes = new Uint8Array(sample("webm-vp9-64x48.webm"))
    const { upload_id } = await store.createPending({
      session: "s", kind: "video", mime: "video/webm", name: "c.webm", totalSize: bytes.length, device: "d", origin: "web-upload",
    })
    await store.appendChunk(upload_id, 0, bytes)
    const fin = await store.finalizePending(upload_id)
    expect(await store.get(fin.file_id)).toMatchObject({ width: 64, height: 48 })
  })

  test("a file that is not media is read once and remembered as unknown", async () => {
    const { store, probes } = makeStore()
    const { file_id } = await store.put({ kind: "document", mime: "application/pdf", name: "x.pdf", origin: "web-upload", bytes: Buffer.from("%PDF-1.7 hello") })
    const meta = await store.get(file_id)
    expect(meta?.width).toBeUndefined()
    await store.ensureMediaMeta([file_id])
    await store.ensureMediaMeta([file_id])
    expect(probes.length).toBe(1)
  })

  test("a file stored before sizes existed is read on its first request, then remembered", async () => {
    const { store, db, probes } = makeStore()
    const { file_id } = await store.put({ kind: "photo", mime: "image/png", name: "o.png", origin: "web-upload", bytes: sample("png-64x48.png") })
    // What a pre-045 row looks like after the migration: no size, never read.
    db.prepare("UPDATE attachments SET width = NULL, height = NULL, duration_ms = NULL, media_probed = 0 WHERE file_id = ?").run(file_id)
    probes.length = 0
    expect(store.withMediaMeta([{ file_id, kind: "photo" }])).toEqual([{ file_id, kind: "photo" }])
    await store.ensureMediaMeta([file_id])
    expect(store.withMediaMeta([{ file_id, kind: "photo" }])).toEqual([{ file_id, kind: "photo", width: 64, height: 48 }])
    await store.ensureMediaMeta([file_id])
    expect(probes.length).toBe(1)
  })

  test("withMediaMeta adds only what is known, and keeps every field it was given", async () => {
    const { store } = makeStore()
    const { file_id } = await store.put({ kind: "video", mime: "video/mp4", name: "c.mp4", origin: "web-upload", bytes: sample("mp4-faststart-64x48.mp4") })
    const [att] = store.withMediaMeta([{ file_id, kind: "video", mime: "video/mp4", size: 13699, name: "c.mp4" }])
    expect(att).toMatchObject({ file_id, kind: "video", mime: "video/mp4", size: 13699, name: "c.mp4", width: 64, height: 48 })
    expect(Math.abs((att!.duration_ms ?? 0) - 1500)).toBeLessThanOrEqual(20)
    expect(store.withMediaMeta([{ file_id: "nope", kind: "photo" }])).toEqual([{ file_id: "nope", kind: "photo" }])
  })

  test("a device's own picture becomes its own clip's poster; anything else is refused", async () => {
    const { store } = makeStore()
    const own = (kind: string, name: string, bytes: Buffer, device = "phone") =>
      store.put({ kind: kind as any, mime: kind === "video" ? "video/mp4" : "image/jpeg", name, origin: "web-upload", device, bytes })
    const clip = await own("video", "c.mp4", sample("mp4-faststart-64x48.mp4"))
    const poster = await own("photo", "p.jpg", sample("jpeg-64x48.jpg"))
    const otherDevices = await own("photo", "q.jpg", sample("jpeg-64x48.jpg"), "laptop")
    expect(await store.setPoster(clip.file_id, otherDevices.file_id, "phone")).toBe(false)
    expect(await store.setPoster(poster.file_id, clip.file_id, "phone")).toBe(false) // a clip is no poster
    expect(await store.setPoster(clip.file_id, poster.file_id, "laptop")).toBe(false) // not the laptop's clip
    expect(await store.setPoster(clip.file_id, poster.file_id, "phone")).toBe(true)
    expect(store.withMediaMeta([{ file_id: clip.file_id, kind: "video" }])[0]?.poster_file_id).toBe(poster.file_id)
  })
})
