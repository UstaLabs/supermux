// Exercises normalizeTelegramInbound end-to-end with a mocked DownloadableApi
// (getFile + fetchFile) and a fake FileStore that records the kind passed to
// put(). This drives the real pickRawAttachment mapping. downloadAttachment
// stages the bytes into /tmp and normalizeTelegramInbound cleans it up in its
// finally block.
import { describe, expect, test } from "bun:test"
import { normalizeTelegramInbound } from "./inbound"

function makeApi(bytes: Uint8Array) {
  return {
    getFile: async (_id: string) => ({ file_path: "videos/clip.mp4", file_size: bytes.length }),
    fetchFile: async (_fp: string) => Buffer.from(bytes),
  }
}

function fakeStore(onKind?: (k: string) => void) {
  return {
    put: async (input: any) => {
      onKind?.(input.kind)
      return { file_id: "0".repeat(32), size: (input.bytes as Uint8Array).length }
    },
  } as any
}

function ctxWith(message: any) {
  return {
    chat: { id: 42 },
    message: { message_id: 7, date: 1_700_000_000, from: { id: 99, username: "ada" }, ...message },
  }
}

describe("normalizeTelegramInbound attachment kinds", () => {
  test("m.video → kind 'video'", async () => {
    let kind = ""
    const ctx = ctxWith({ video: { file_id: "TG_VID", mime_type: "video/mp4", file_size: 123, file_name: "clip.mp4" } })
    const msg = await normalizeTelegramInbound({ ctx, api: makeApi(new Uint8Array([1, 2, 3])), fileStore: fakeStore((k) => (kind = k)) })
    expect(kind).toBe("video")
    expect(msg.attachments?.[0]).toMatchObject({ kind: "video", mime: "video/mp4", name: "clip.mp4" })
  })

  test("m.video_note still → kind 'video_note'", async () => {
    let kind = ""
    const ctx = ctxWith({ video_note: { file_id: "TG_VN", mime_type: "video/mp4", file_size: 55 } })
    const msg = await normalizeTelegramInbound({ ctx, api: makeApi(new Uint8Array([4, 5])), fileStore: fakeStore((k) => (kind = k)) })
    expect(kind).toBe("video_note")
    expect(msg.attachments?.[0]).toMatchObject({ kind: "video_note" })
  })
})
