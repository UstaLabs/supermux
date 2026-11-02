// What a client learns about an attachment before it loads anything: its displayed size and a
// clip's length (an older file's are read on its first request, through `enrichAttachments`), and
// the poster a phone uploaded for a clip (POST /files/:clip/poster, its own uploads only).
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, readFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { FileStore } from "../../core/files/store"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"

let channel: WebChannel | undefined
afterEach(async () => {
  if (channel) { await channel.stop(); channel = undefined }
})

const fixture = (n: string) => readFileSync(join(import.meta.dir, "../../../tests/fixtures/media", n))

async function boot(log: any[] = []) {
  const dir = mkdtempSync(join(tmpdir(), "mux-attachment-media-"))
  const devicesFile = join(dir, "devices.json")
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new FileStore(db, join(dir, "files"))
  const enriched: unknown[][] = []
  const opts: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [{ id: "a", name: "alpha", workdir: "/w", mute: false, connected: true }] as any,
    getSessionLog: () => log.map((e) => ({ ...e, attachments: e.attachments?.map((x: any) => ({ ...x })) })),
    enrichAttachments: async (entries) => {
      enriched.push(entries)
      for (const e of entries as any[]) for (const a of e.attachments ?? []) a.width = 640
    },
    setMute: () => {},
    onSendFromWeb: () => {},
    fileStore: store,
    updateChecker: null,
  }
  channel = new WebChannel(opts)
  await channel.start()
  const devices = new DeviceStore(devicesFile)
  return { base: `http://127.0.0.1:${channel.boundPort}`, store, devices, enriched }
}

async function uploadAs(base: string, token: string, name: string, mime: string, bytes: Uint8Array): Promise<string> {
  const res = await fetch(`${base}/upload`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/octet-stream",
      "x-mux-session": "a",
      "x-mux-mime": mime,
      "x-mux-filename": name,
    },
    body: bytes,
  })
  return ((await res.json()) as any).file_id as string
}

test("GET /sessions/:id/messages hands the log to enrichAttachments before it answers", async () => {
  const { base, devices } = await boot([{ id: "m1", attachments: [{ file_id: "f1", kind: "photo" }] }])
  const token = devices.mint("phone").token
  const res = await fetch(`${base}/sessions/a/messages`, { headers: { authorization: `Bearer ${token}` } })
  const body = (await res.json()) as any[]
  expect(body[0].attachments[0]).toEqual({ file_id: "f1", kind: "photo", width: 640 })
})

test("the WS snapshot's logs go through enrichAttachments too", async () => {
  const { base, devices, enriched } = await boot([{ id: "m1", attachments: [{ file_id: "f1", kind: "photo" }] }])
  const token = devices.mint("phone").token
  const ws = new WebSocket(`${base.replace("http", "ws")}/ws`, { headers: { Cookie: `cmux_token=${token}` } } as any)
  await new Promise<void>((resolve, reject) => { ws.onopen = () => resolve(); ws.onerror = reject })
  const snap = new Promise<any>((resolve) => {
    ws.onmessage = (e) => {
      const f = JSON.parse(String(e.data))
      if (f.type === "snapshot") resolve(f)
    }
  })
  ws.send(JSON.stringify({ type: "subscribe" }))
  const frame = await snap
  ws.close()
  expect(frame.logs.a[0].attachments[0].width).toBe(640)
  expect(enriched.length).toBe(1)
})

test("an uploaded picture and clip carry their sizes, and the clip's length", async () => {
  const { base, store, devices } = await boot()
  const token = devices.mint("phone").token
  const pic = await uploadAs(base, token, "p.jpg", "image/jpeg", new Uint8Array(fixture("jpeg-exif-orientation6-64x48.jpg")))
  const clip = await uploadAs(base, token, "c.mov", "video/quicktime", new Uint8Array(fixture("mov-64x48.mov")))
  expect(await store.get(pic)).toMatchObject({ width: 48, height: 64 })
  const meta = await store.get(clip)
  expect(meta).toMatchObject({ width: 64, height: 48 })
  expect(Math.abs((meta?.duration_ms ?? 0) - 2500)).toBeLessThanOrEqual(20)
})

test("POST /files/:clip/poster links the phone's own poster to its own clip, and nothing else", async () => {
  const { base, store, devices } = await boot()
  const phone = devices.mint("phone").token
  const laptop = devices.mint("laptop").token
  const clip = await uploadAs(base, phone, "c.mp4", "video/mp4", new Uint8Array(fixture("mp4-faststart-64x48.mp4")))
  const poster = await uploadAs(base, phone, "p.jpg", "image/jpeg", new Uint8Array(fixture("jpeg-64x48.jpg")))
  const link = (token: string | null, target: string, body: unknown) =>
    fetch(`${base}/files/${target}/poster`, {
      method: "POST",
      headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), "content-type": "application/json" },
      body: JSON.stringify(body),
    })
  expect((await link(null, clip, { poster_file_id: poster })).status).toBe(401)
  expect((await link(laptop, clip, { poster_file_id: poster })).status).toBe(404)
  expect((await link(phone, clip, {})).status).toBe(400)
  expect((await link(phone, clip, { poster_file_id: poster })).status).toBe(204)
  expect((await store.get(clip))?.poster_file_id).toBe(poster)
})
