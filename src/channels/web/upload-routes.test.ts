// HTTP tests for the dual-mode POST /upload handler. Boots a real WebChannel on
// an ephemeral port with a real FileStore (in-memory sqlite + temp files root)
// and drives it over fetch — mirroring update-routes.test.ts. Bearer auth
// (native-client style) bypasses the same-origin CSRF guard so POSTs need no
// Origin header.
import { afterEach, describe, expect, test } from "bun:test"
import { mkdtempSync, readdirSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { FileStore } from "../../core/files/store"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"

function makeChannel(): { channel: WebChannel; devicesFile: string; store: FileStore; filesRoot: string } {
  const dir = mkdtempSync(join(tmpdir(), "mux-upload-routes-"))
  const devicesFile = join(dir, "devices.json")
  const filesRoot = join(dir, "files")
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new FileStore(db, filesRoot)
  const opts: WebChannelOpts = {
    port: 0, // ephemeral; real port via channel.boundPort after start()
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    fileStore: store,
    updateChecker: null,
  }
  return { channel: new WebChannel(opts), devicesFile, store, filesRoot }
}

function mintToken(devicesFile: string): string {
  return new DeviceStore(devicesFile).mint("test-device").token
}

function allFiles(dir: string): string[] {
  const out: string[] = []
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name)
    if (e.isDirectory()) out.push(...allFiles(p))
    else out.push(p)
  }
  return out
}

let channel: WebChannel | undefined
const savedEnv: Record<string, string | undefined> = {}
function setEnv(k: string, v: string | undefined): void {
  if (!(k in savedEnv)) savedEnv[k] = process.env[k]
  if (v === undefined) delete process.env[k]
  else process.env[k] = v
}

afterEach(async () => {
  for (const k of Object.keys(savedEnv)) {
    if (savedEnv[k] === undefined) delete process.env[k]
    else process.env[k] = savedEnv[k]
    delete savedEnv[k]
  }
  if (channel) {
    await channel.stop()
    channel = undefined
  }
})

function base(): string {
  return `http://127.0.0.1:${channel!.boundPort}`
}

describe("POST /upload — streaming path", () => {
  test("octet-stream body → stores file, infers video kind from mime, returns {file_id,size,mime,name}", async () => {
    const made = makeChannel()
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const bytes = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8])
    const res = await fetch(`${base()}/upload`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${token}`,
        "content-type": "application/octet-stream",
        "x-mux-session": "sess-1",
        "x-mux-mime": "video/mp4",
        "x-mux-filename": encodeURIComponent("my clip.mp4"),
      },
      body: bytes,
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as Record<string, unknown>
    expect(body.file_id as string).toMatch(/^[0-9a-f]{32}$/)
    expect(body.size).toBe(8)
    expect(body.mime).toBe("video/mp4")
    expect(body.name).toBe("my clip.mp4")

    // stored under the new "video" kind and retrievable byte-for-byte
    const meta = await made.store.get(body.file_id as string)
    expect(meta?.kind).toBe("video")
    const dl = await fetch(`${base()}/files/${body.file_id}`, { headers: { authorization: `Bearer ${token}` } })
    expect(dl.status).toBe(200)
    expect(new Uint8Array(await dl.arrayBuffer())).toEqual(bytes)
  })

  test("missing X-Mux-Session → 400", async () => {
    const made = makeChannel()
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/upload`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/octet-stream", "x-mux-mime": "video/mp4" },
      body: new Uint8Array([1, 2, 3]),
    })
    expect(res.status).toBe(400)
  })

  test("Content-Length over cap → 413 up front", async () => {
    setEnv("MUX_WEB_UPLOAD_MAX_MB", "0.0001") // ~104 bytes
    const made = makeChannel()
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/upload`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/octet-stream", "x-mux-session": "s1", "x-mux-mime": "video/mp4" },
      body: new Uint8Array(500), // 500 > ~104
    })
    expect(res.status).toBe(413)
  })

  test("oversized body → 413 and leaves no stored file (putStream aborts)", async () => {
    setEnv("MUX_WEB_UPLOAD_MAX_MB", "0.0001") // ~104 bytes
    const made = makeChannel()
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    // A chunked (no Content-Length) body forces the authoritative in-stream cap;
    // if the runtime buffers it and sets Content-Length instead, the up-front
    // check still returns 413. Either way: 413 and nothing stored.
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new Uint8Array(80))
        controller.enqueue(new Uint8Array(80)) // 160 > ~104
        controller.close()
      },
    })
    const res = await fetch(`${base()}/upload`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/octet-stream", "x-mux-session": "s1", "x-mux-mime": "video/mp4" },
      body: stream,
      duplex: "half",
    } as any)
    expect(res.status).toBe(413)
    expect(allFiles(made.filesRoot)).toEqual([])
  })

  test("empty streamed body → 400 and leaves no stored file (parity with multipart)", async () => {
    const made = makeChannel()
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    // A chunked (no Content-Length) body that closes with zero bytes reaches
    // putStream, which rejects it as EmptyUploadError → 400 — matching the
    // multipart path's file.size===0 guard. Nothing is stored.
    const stream = new ReadableStream<Uint8Array>({ start(controller) { controller.close() } })
    const res = await fetch(`${base()}/upload`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/octet-stream", "x-mux-session": "sess-1", "x-mux-mime": "video/mp4" },
      body: stream,
      duplex: "half",
    } as any)
    expect(res.status).toBe(400)
    expect(allFiles(made.filesRoot)).toEqual([])
  })

  test("no auth → 401", async () => {
    const made = makeChannel()
    channel = made.channel
    await channel.start()

    const res = await fetch(`${base()}/upload`, {
      method: "POST",
      headers: { "content-type": "application/octet-stream", "x-mux-session": "s1", "x-mux-mime": "video/mp4" },
      body: new Uint8Array([1, 2, 3]),
    })
    expect(res.status).toBe(401)
  })
})

describe("POST /upload — legacy multipart path", () => {
  test("multipart/form-data still stores the file", async () => {
    const made = makeChannel()
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const fd = new FormData()
    fd.append("file", new Blob([new Uint8Array([9, 8, 7])], { type: "image/png" }), "pic.png")
    fd.append("session", "sess-1")
    const res = await fetch(`${base()}/upload`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}` },
      body: fd,
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as Record<string, unknown>
    expect(body.file_id as string).toMatch(/^[0-9a-f]{32}$/)
    expect(body.size).toBe(3)
    expect(body.mime).toBe("image/png")
    expect(body.name).toBe("pic.png")

    const meta = await made.store.get(body.file_id as string)
    expect(meta?.kind).toBe("photo")
  })

  test("multipart over its own smaller cap → 413", async () => {
    setEnv("MUX_WEB_UPLOAD_MULTIPART_MAX_MB", "0.0001") // ~104 bytes
    const made = makeChannel()
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const fd = new FormData()
    fd.append("file", new Blob([new Uint8Array(500)], { type: "image/png" }), "big.png")
    fd.append("session", "sess-1")
    const res = await fetch(`${base()}/upload`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}` },
      body: fd,
    })
    expect(res.status).toBe(413)
  })
})

describe("POST /upload/init", () => {
  test("returns {upload_id, offset:0, chunk_size} and creates a pending upload", async () => {
    const made = makeChannel()
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/upload/init`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ session: "s1", mime: "video/mp4", name: "clip.mp4", total_size: 12345 }),
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as Record<string, unknown>
    expect(body.upload_id as string).toMatch(/^[0-9a-f]{32}$/)
    expect(body.offset).toBe(0)
    expect(body.chunk_size).toBe(5 * 1024 * 1024)
    expect(await made.store.pendingOffset(body.upload_id as string)).toBe(0)
  })

  test("total_size over cap → 413", async () => {
    setEnv("MUX_WEB_UPLOAD_MAX_MB", "0.0001") // ~104 bytes
    const made = makeChannel()
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)
    const res = await fetch(`${base()}/upload/init`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ session: "s1", mime: "video/mp4", total_size: 500 }),
    })
    expect(res.status).toBe(413)
  })

  test("missing session → 400; no auth → 401", async () => {
    const made = makeChannel()
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)
    const bad = await fetch(`${base()}/upload/init`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ mime: "video/mp4", total_size: 10 }),
    })
    expect(bad.status).toBe(400)
    const noauth = await fetch(`${base()}/upload/init`, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ session: "s1", total_size: 10 }),
    })
    expect(noauth.status).toBe(401)
  })
})

describe("PATCH /upload/<id>", () => {
  async function init(made: ReturnType<typeof makeChannel>, token: string, total: number, mime = "video/mp4") {
    const res = await fetch(`${base()}/upload/init`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ session: "s1", mime, name: "clip.mp4", total_size: total }),
    })
    return ((await res.json()) as Record<string, unknown>).upload_id as string
  }
  function patch(token: string, id: string, offset: number, chunk: Uint8Array) {
    return fetch(`${base()}/upload/${id}`, {
      method: "PATCH",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/offset+octet-stream", "upload-offset": String(offset) },
      body: chunk,
    })
  }

  test("two chunks: first → {offset}, last → finalized {file_id,size,mime,name}", async () => {
    const made = makeChannel(); channel = made.channel; await channel.start()
    const token = mintToken(made.devicesFile)
    const id = await init(made, token, 6)

    const r1 = await patch(token, id, 0, new Uint8Array([1, 2, 3]))
    expect(r1.status).toBe(200)
    expect(((await r1.json()) as Record<string, unknown>).offset).toBe(3)

    const r2 = await patch(token, id, 3, new Uint8Array([4, 5, 6]))
    expect(r2.status).toBe(200)
    const fin = await r2.json() as Record<string, unknown>
    expect(fin.file_id).toBe(id)
    expect(fin.size).toBe(6)
    expect(fin.mime).toBe("video/mp4")
    expect(fin.name).toBe("clip.mp4")

    const dl = await fetch(`${base()}/files/${id}`, { headers: { authorization: `Bearer ${token}` } })
    expect(new Uint8Array(await dl.arrayBuffer())).toEqual(new Uint8Array([1, 2, 3, 4, 5, 6]))
  })

  test("wrong offset → 409 with Upload-Offset header (resume path)", async () => {
    const made = makeChannel(); channel = made.channel; await channel.start()
    const token = mintToken(made.devicesFile)
    const id = await init(made, token, 6)
    await patch(token, id, 0, new Uint8Array([1, 2, 3]))
    const conflict = await patch(token, id, 0, new Uint8Array([9, 9, 9]))
    expect(conflict.status).toBe(409)
    expect(conflict.headers.get("upload-offset")).toBe("3")
  })

  test("overflow past total_size → 400", async () => {
    const made = makeChannel(); channel = made.channel; await channel.start()
    const token = mintToken(made.devicesFile)
    const id = await init(made, token, 4)
    const over = await patch(token, id, 0, new Uint8Array([1, 2, 3, 4, 5]))
    expect(over.status).toBe(400)
  })

  test("unknown upload_id → 404; no auth → 401", async () => {
    const made = makeChannel(); channel = made.channel; await channel.start()
    const token = mintToken(made.devicesFile)
    const missing = await patch(token, "00000000000000000000000000000000", 0, new Uint8Array([1]))
    expect(missing.status).toBe(404)
    const noauth = await fetch(`${base()}/upload/whatever`, {
      method: "PATCH", headers: { "upload-offset": "0" }, body: new Uint8Array([1]),
    })
    expect(noauth.status).toBe(401)
  })
})

describe("HEAD /upload/<id>", () => {
  test("returns 200 + Upload-Offset for an in-flight upload, 404 for unknown", async () => {
    const made = makeChannel(); channel = made.channel; await channel.start()
    const token = mintToken(made.devicesFile)
    const initRes = await fetch(`${base()}/upload/init`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ session: "s1", mime: "video/mp4", total_size: 10 }),
    })
    const id = ((await initRes.json()) as Record<string, unknown>).upload_id as string
    await fetch(`${base()}/upload/${id}`, {
      method: "PATCH",
      headers: { authorization: `Bearer ${token}`, "upload-offset": "0" },
      body: new Uint8Array([1, 2, 3, 4]),
    })

    const head = await fetch(`${base()}/upload/${id}`, { method: "HEAD", headers: { authorization: `Bearer ${token}` } })
    expect(head.status).toBe(200)
    expect(head.headers.get("upload-offset")).toBe("4")

    const missing = await fetch(`${base()}/upload/00000000000000000000000000000000`, {
      method: "HEAD", headers: { authorization: `Bearer ${token}` },
    })
    expect(missing.status).toBe(404)
  })
})
