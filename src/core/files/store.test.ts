import { describe, expect, test } from "bun:test"
import { mkdtempSync, readFileSync, existsSync, readdirSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { FileStore, PayloadTooLargeError } from "./store"
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
