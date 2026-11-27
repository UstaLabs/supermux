// #167: POST /sessions/:id/send — a share from another app, as a normal user message with the device's uploads.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel } from "./index"
import { DeviceStore } from "./device-store"
import { FileStore } from "../../core/files/store"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"
import type { InboundMessage } from "../channel"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function start(opts: { known?: Set<string> } = {}) {
  const dir = mkdtempSync(join(tmpdir(), "mux-share-send-"))
  const devicesFile = join(dir, "devices.json")
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const files = new FileStore(db, join(dir, "files"))
  const sent: InboundMessage[] = []
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {},
    onSendFromWeb: (m: InboundMessage) => { sent.push(m) },
    getSessionAgent: (id: string) => (id === "s1" ? { agent: "claude" } : undefined),
    hasInboundMessage: (s: string, c: string, m: string) => opts.known?.has(`${s}/${c}/${m}`) ?? false,
    fileStore: files, updateChecker: null,
  } as any)
  await channel.start()
  const base = `http://127.0.0.1:${channel.boundPort}`
  const devices = new DeviceStore(devicesFile)
  const phone = devices.mint("phone").token
  const laptop = devices.mint("laptop").token
  const post = (session: string, body: unknown, token: string | null = phone) =>
    fetch(`${base}/sessions/${session}/send`, {
      method: "POST",
      headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), "content-type": "application/json", origin: "http://localhost" },
      body: JSON.stringify(body),
    })
  const upload = (device: string) => files.put({ kind: "photo", mime: "image/jpeg", name: "a.jpg", device, origin: "web-upload", bytes: new Uint8Array([1, 2, 3]) })
  return { post, sent, upload, laptop }
}

test("a share with text and the device's own upload becomes one user message", async () => {
  const { post, sent, upload } = await start()
  const { file_id } = await upload("phone")
  const r = await post("s1", { text: " look at this ", attachments: [file_id], client_message_id: "web-share-1" })
  expect(r.status).toBe(200)
  expect(await r.json()).toEqual({ ok: true })
  expect(sent).toHaveLength(1)
  expect(sent[0]).toMatchObject({ channel: "web", chat_id: "web", message_id: "web-share-1", user: "phone", text: "look at this", target_session_id: "s1" })
  expect(sent[0]!.attachments).toEqual([expect.objectContaining({ file_id, kind: "photo", mime: "image/jpeg", name: "a.jpg" })])
})

test("the same client_message_id twice is accepted once", async () => {
  const { post, sent } = await start()
  const body = { text: "hi", client_message_id: "web-share-2" }
  expect(await (await post("s1", body)).json()).toEqual({ ok: true })
  expect(await (await post("s1", body)).json()).toEqual({ ok: true, duplicate: true })
  expect(sent).toHaveLength(1)
})

test("a message the log already has (after a broker restart) is a duplicate", async () => {
  const { post, sent } = await start({ known: new Set(["s1/web/web-share-3"]) })
  expect(await (await post("s1", { text: "hi", client_message_id: "web-share-3" })).json()).toEqual({ ok: true, duplicate: true })
  expect(sent).toHaveLength(0)
})

test("another device's upload is refused, and the id can be retried", async () => {
  const { post, sent, upload } = await start()
  const { file_id } = await upload("laptop")
  const r = await post("s1", { attachments: [file_id], client_message_id: "web-share-4" })
  expect(r.status).toBe(400)
  expect(await r.json()).toEqual({ error: "invalid attachment reference" })
  expect((await post("s1", { text: "hi", client_message_id: "web-share-4" })).status).toBe(200)
  expect(sent).toHaveLength(1)
})

test("refusals: no auth, no client id, nothing to send, unknown session", async () => {
  const { post } = await start()
  expect((await post("s1", { text: "hi", client_message_id: "web-x" }, null)).status).toBe(401)
  expect((await post("s1", { text: "hi" })).status).toBe(400)
  expect((await post("s1", { text: "  ", client_message_id: "web-x" })).status).toBe(400)
  expect((await post("nope", { text: "hi", client_message_id: "web-x" })).status).toBe(404)
  expect((await post("s1", { attachments: [7], client_message_id: "web-x" })).status).toBe(400)
})
