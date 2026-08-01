import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { FileStore } from "../src/core/files/store"
import { transformOutbound } from "../src/core/routing/transform-outbound"
import type { ChannelCapabilities, OutboundAction } from "../src/channels/channel"

const WEB_CAPS: ChannelCapabilities = {
  multiplexesSessions: false,
  supportsReactions: false,
  supportsEdit: false,
  supportsAttachments: true,
}

const WEB_NO_ATT_CAPS: ChannelCapabilities = {
  multiplexesSessions: false,
  supportsReactions: false,
  supportsEdit: false,
  supportsAttachments: false,
}

const TG_CAPS: ChannelCapabilities = {
  multiplexesSessions: true,
  supportsReactions: true,
  supportsEdit: true,
  supportsAttachments: true,
}

let tmpDir: string
let store: FileStore

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-xform-"))
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  store = new FileStore(db, join(tmpDir, "files"))
})
afterEach(() => { rmSync(tmpDir, { recursive: true, force: true }) })

test("when channel supportsAttachments, files[] are registered + rewritten to attachments[]", async () => {
  const fp = join(tmpDir, "shot.png")
  writeFileSync(fp, Buffer.from([1, 2, 3, 4]))

  const action: OutboundAction = { op: "reply", chat_id: "web:iphone", text: "look", files: [fp] }
  const out = await transformOutbound(action, "ana", WEB_CAPS, store)
  expect(out.op).toBe("reply")
  if (out.op !== "reply") throw new Error()
  expect(out.attachments?.length).toBe(1)
  expect(out.attachments?.[0]?.file_id).toMatch(/^[0-9a-f]{32}$/)
  expect(out.attachments?.[0]?.kind).toBe("photo")
  expect(out.attachments?.[0]?.mime).toBe("image/png")
  expect(out.attachments?.[0]?.size).toBe(4)
  expect(out.attachments?.[0]?.name).toBe("shot.png")
  expect(out.files).toBeUndefined()
})

test("bare 'web' chat_id (single-channel collapse) still rewrites files[] to attachments[]", async () => {
  // Regression: the web channel collapsed chat_id from `web:<device>` to the
  // constant `web` (commit 6d6f02e), but transform-outbound kept keying the
  // attachment-rewrite branch on the old `web:` prefix — so every outbound file
  // (image/audio/video) was silently dropped on web. A video reply was the
  // symptom that surfaced it.
  const fp = join(tmpDir, "clip.mp4")
  writeFileSync(fp, Buffer.from([0, 0, 0, 1]))

  const action: OutboundAction = { op: "reply", chat_id: "web", text: "watch", files: [fp] }
  const out = await transformOutbound(action, "ana", WEB_CAPS, store)
  expect(out.op).toBe("reply")
  if (out.op !== "reply") throw new Error()
  expect(out.attachments?.length).toBe(1)
  // `video/*` classifies as "video" since edab47e added the dedicated video kind;
  // before that it fell through to "video_note".
  expect(out.attachments?.[0]?.kind).toBe("video")
  expect(out.attachments?.[0]?.mime).toBe("video/mp4")
  expect(out.attachments?.[0]?.name).toBe("clip.mp4")
  expect(out.files).toBeUndefined()
})

test("when channel supportsAttachments, ALL files[] entries are rewritten", async () => {
  const fpA = join(tmpDir, "a.png")
  const fpB = join(tmpDir, "b.png")
  writeFileSync(fpA, Buffer.from([1, 2, 3]))
  writeFileSync(fpB, Buffer.from([4, 5, 6, 7]))

  const action: OutboundAction = { op: "reply", chat_id: "web:iphone", text: "two", files: [fpA, fpB] }
  const out = await transformOutbound(action, "ana", WEB_CAPS, store)
  expect(out.op).toBe("reply")
  if (out.op !== "reply") throw new Error()
  expect(out.attachments?.length).toBe(2)
  expect(out.attachments?.[0]?.file_id).toMatch(/^[0-9a-f]{32}$/)
  expect(out.attachments?.[1]?.file_id).toMatch(/^[0-9a-f]{32}$/)
  expect(out.attachments?.[0]?.file_id).not.toBe(out.attachments?.[1]?.file_id)
  expect(out.attachments?.[0]?.size).toBe(3)
  expect(out.attachments?.[1]?.size).toBe(4)
  expect(out.files).toBeUndefined()
})

test("when channel !supportsAttachments, files[] is stripped, no attachments synthesized", async () => {
  const fp = join(tmpDir, "shot.png")
  writeFileSync(fp, Buffer.from([1, 2, 3, 4]))

  const action: OutboundAction = { op: "reply", chat_id: "web:iphone", text: "look", files: [fp] }
  const out = await transformOutbound(action, "ana", WEB_NO_ATT_CAPS, store)
  expect(out.op).toBe("reply")
  if (out.op !== "reply") throw new Error()
  expect(out.attachments).toBeUndefined()
  expect(out.files).toBeUndefined()
})

test("telegram chat_id keeps files[] untouched (no rewrite, no strip)", async () => {
  const fp = join(tmpDir, "shot.png")
  writeFileSync(fp, Buffer.from([1, 2, 3, 4]))

  const action: OutboundAction = { op: "reply", chat_id: "telegram:1234", text: "look", files: [fp] }
  const out = await transformOutbound(action, "ana", TG_CAPS, store)
  expect(out.op).toBe("reply")
  if (out.op !== "reply") throw new Error()
  expect(out.files).toEqual([fp])
  expect(out.attachments).toBeUndefined()
})
