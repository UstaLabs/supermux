import { describe, expect, test } from "bun:test"
import { WhatsAppChannel } from "./index"

function makeChannel(captured: any[]) {
  const ch = new WhatsAppChannel({ gowaUrl: "http://127.0.0.1:3000", webhookPort: 0, webhookSecret: "x", fileStore: {} as any })
  // swap the gowa client for a capturing fake (private field, test seam)
  ;(ch as any).gowa = {
    sendText: async (phone: string, message: string, replyTo?: string) => { captured.push({ kind: "text", phone, message, replyTo }); return { message_id: "T1" } },
    sendMedia: async (kind: string, phone: string, path: string, o?: any) => { captured.push({ kind, phone, path, o }); return { message_id: "M1" } },
  }
  return ch
}

describe("WhatsAppChannel.send", () => {
  test("text reply → sendText with JID derived from chat_id; returns message_id", async () => {
    const cap: any[] = []
    const r = await makeChannel(cap).send({ op: "reply", chat_id: "whatsapp:628@s.whatsapp.net", text: "hi" } as any)
    expect(r).toEqual({ ok: true, value: { message_id: "T1" } })
    expect(cap[0]).toMatchObject({ kind: "text", phone: "628@s.whatsapp.net", message: "hi" })
  })
  test("bare number chat_id gets @s.whatsapp.net suffix", async () => {
    const cap: any[] = []
    await makeChannel(cap).send({ op: "reply", chat_id: "whatsapp:628999", text: "x" } as any)
    expect(cap[0].phone).toBe("628999@s.whatsapp.net")
  })
  test("image file → sendMedia('image', ...) with caption from text", async () => {
    const cap: any[] = []
    await makeChannel(cap).send({ op: "reply", chat_id: "whatsapp:c@s.whatsapp.net", text: "cap", files: ["/tmp/p.jpg"] } as any)
    expect(cap[0]).toMatchObject({ kind: "image", path: "/tmp/p.jpg", o: { caption: "cap", replyTo: undefined } })
  })
  test("non-reply op is rejected", async () => {
    const r = await makeChannel([]).send({ op: "react", chat_id: "whatsapp:c", message_id: "1", emoji: "👍" } as any)
    expect(r.ok).toBe(false)
  })
})
