// #122 phase 4: a Browser tab sends its comments to a session through the broker, which stores them (one per comment)
// and tells every client (the `browser_comment` frame), so the pins follow what the agent does with each.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"
import { BrowserCommentStore } from "../../core/browser-comments/store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function boot() {
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-browser-comments-")), "devices.json")
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new BrowserCommentStore(db)
  const token = new DeviceStore(devicesFile).mint("test-device").token
  const frames: object[] = []
  const opts: WebChannelOpts = {
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    // As main.ts wires it: store, then tell every client.
    browserCommentsAdd: (sessionId, batch) => {
      const added = store.addBatch(sessionId, batch, new Date().toISOString())
      for (const comment of added) frames.push({ type: "browser_comment", sessionId, comment })
      return added
    },
    browserCommentsList: (sessionId) => store.list(sessionId),
  }
  channel = new WebChannel(opts)
  await channel.start()
  const call = (method: string, path: string, body?: unknown) => fetch(`http://127.0.0.1:${channel!.boundPort}${path}`, {
    method,
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return { store, frames, call }
}

const batch = {
  url: "http://localhost:5173/checkout",
  viewedAs: "iPhone 16 · 393×852",
  comments: [{ number: 1, comment: "the Pay button is cut off", selector: "button.buy", text: "Pay €42", picture: "comment-1.png" }],
}

test("POST stores the batch in the session, answers its comments with their ids, and tells every client", async () => {
  const { frames, call } = await boot()
  const res = await call("POST", "/sessions/s1/browser/comments", batch)
  expect(res.status).toBe(200)
  const { comments } = await res.json() as { comments: { id: string; status: string; selector: string }[] }
  expect(comments).toHaveLength(1)
  expect(comments[0]).toMatchObject({ status: "sent", selector: "button.buy" })
  expect(frames).toEqual([{ type: "browser_comment", sessionId: "s1", comment: expect.objectContaining({ id: comments[0]!.id }) }])
  const listed = await (await call("GET", "/sessions/s1/browser/comments")).json() as { comments: { id: string }[] }
  expect(listed.comments.map((c) => c.id)).toEqual([comments[0]!.id])
})

test("POST refuses a batch with no comments or a comment without its selector and words", async () => {
  const { call } = await boot()
  expect((await call("POST", "/sessions/s1/browser/comments", { comments: [] })).status).toBe(400)
  expect((await call("POST", "/sessions/s1/browser/comments", { comments: [{ number: 1, comment: "", selector: "a" }] })).status).toBe(400)
  expect((await call("POST", "/sessions/s1/browser/comments", { comments: [{ number: 1, comment: "x" }] })).status).toBe(400)
})
