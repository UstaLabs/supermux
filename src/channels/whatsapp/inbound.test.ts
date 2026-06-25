import { describe, expect, test } from "bun:test"
import { normalizeWhatsAppInbound } from "./inbound"

function deps(opts?: { media?: Uint8Array; putKindOut?: (k: string) => void }) {
  return {
    gowa: {
      fetchMedia: async (_p: string) => opts?.media ?? new Uint8Array([9, 9]),
      downloadMedia: async (_id: string, _phone: string) => "http://h:3000/statics/media/dl.ogg",
    },
    fileStore: {
      put: async (input: any) => { opts?.putKindOut?.(input.kind); return { file_id: "fid-" + input.kind, size: (input.bytes as Uint8Array).length } },
    } as any,
  }
}

describe("normalizeWhatsAppInbound", () => {
  test("text message", async () => {
    const msg = await normalizeWhatsAppInbound({ payload: { id: "M1", chat_id: "628@s.whatsapp.net", from: "628@s.whatsapp.net", from_name: "Ada", timestamp: "2026-06-25T10:00:00Z", body: "hello", is_from_me: false } }, deps())
    expect(msg).toMatchObject({ channel: "whatsapp", chat_id: "whatsapp:628@s.whatsapp.net", message_id: "M1", user: "Ada", user_id: "628@s.whatsapp.net", ts: "2026-06-25T10:00:00Z", text: "hello" })
    expect(msg.attachments).toBeUndefined()
  })

  test("image as bare-string path → photo attachment", async () => {
    let kind = ""
    const msg = await normalizeWhatsAppInbound({ payload: { id: "M2", chat_id: "c@s.whatsapp.net", from: "c@s.whatsapp.net", timestamp: "2026-06-25T10:00:00Z", image: "statics/media/x.jpeg" } }, deps({ putKindOut: (k) => (kind = k) }))
    expect(kind).toBe("photo")
    expect(msg.attachments?.[0]).toMatchObject({ kind: "photo", file_id: "fid-photo" })
  })

  test("audio .ogg → voice attachment (Telegram parity)", async () => {
    let kind = ""
    await normalizeWhatsAppInbound({ payload: { id: "M3", chat_id: "c@s.whatsapp.net", from: "c@s.whatsapp.net", timestamp: "t", audio: "statics/media/v.ogg" } }, deps({ putKindOut: (k) => (kind = k) }))
    expect(kind).toBe("voice")
  })

  test("document {url,filename} (auto-download off) → resolves via downloadMedia, kind document, carries name", async () => {
    const msg = await normalizeWhatsAppInbound({ payload: { id: "M4", chat_id: "c@s.whatsapp.net", from: "c@s.whatsapp.net", timestamp: "t", document: { url: "https://mmg.whatsapp.net/enc", filename: "report.pdf" } } }, deps())
    expect(msg.attachments?.[0]).toMatchObject({ kind: "document", name: "report.pdf" })
  })

  test("quoted reply maps replied_to_id", async () => {
    const msg = await normalizeWhatsAppInbound({ payload: { id: "M5", chat_id: "c@s.whatsapp.net", from: "c@s.whatsapp.net", timestamp: "t", body: "re", replied_to_id: "M1" } }, deps())
    expect(msg.reply_to_message_id).toBe("M1")
  })

  test("media fetch failure drops attachment but keeps the message", async () => {
    const badDeps = { gowa: { fetchMedia: async () => { throw new Error("boom") }, downloadMedia: async () => "x" }, fileStore: { put: async () => ({ file_id: "x", size: 0 }) } as any }
    const msg = await normalizeWhatsAppInbound({ payload: { id: "M6", chat_id: "c@s.whatsapp.net", from: "c@s.whatsapp.net", timestamp: "t", body: "cap", image: "statics/media/x.jpeg" } }, badDeps)
    expect(msg.attachments).toBeUndefined()
    expect(msg.text).toBe("cap")
  })
})
