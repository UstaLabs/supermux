import { describe, expect, test } from "bun:test"
import { mkdtempSync, writeFileSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WhatsAppChannel } from "./index"
import type { InboundMessage } from "../channel"

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

// End-to-end through the channel: GOWA's webhook handler hands onPayload the
// INNER payload object, so this is the seam that catches a payload-contract
// mismatch (a double-unwrap would normalize every real message to empty). It
// also exercises the allowlist gate via an injected temp access file.
function withAccess(json: string): string {
  const dir = mkdtempSync(join(tmpdir(), "wa-idx-access-"))
  const p = join(dir, "access.json")
  writeFileSync(p, json)
  return p
}

describe("WhatsAppChannel.onPayload (inbound integration)", () => {
  test("an allow-listed inner payload yields a fully-populated InboundMessage", async () => {
    const accessFile = withAccess(JSON.stringify({ whatsapp: { allowFrom: ["628123"] } }))
    const seen: InboundMessage[] = []
    const ch = new WhatsAppChannel({ gowaUrl: "http://127.0.0.1:3000", webhookPort: 0, webhookSecret: "x", fileStore: {} as any, accessFile })
    ch.on("inbound", (m) => seen.push(m))
    await (ch as any).onPayload({ id: "M1", chat_id: "628123@s.whatsapp.net", from: "628123@s.whatsapp.net", from_name: "Ada", timestamp: "2026-06-25T10:00:00Z", body: "hello", is_from_me: false })
    expect(seen).toHaveLength(1)
    expect(seen[0]).toMatchObject({ message_id: "M1", chat_id: "whatsapp:628123@s.whatsapp.net", text: "hello", user: "Ada", user_id: "628123@s.whatsapp.net" })
    expect(seen[0]!.text).not.toBe("")
    rmSync(accessFile, { force: true })
  })

  test("a sender not in the allowlist fires no inbound", async () => {
    const accessFile = withAccess(JSON.stringify({ whatsapp: { allowFrom: ["628123"] } }))
    const seen: InboundMessage[] = []
    const ch = new WhatsAppChannel({ gowaUrl: "http://127.0.0.1:3000", webhookPort: 0, webhookSecret: "x", fileStore: {} as any, accessFile })
    ch.on("inbound", (m) => seen.push(m))
    await (ch as any).onPayload({ id: "M2", chat_id: "447700900000@s.whatsapp.net", from: "447700900000@s.whatsapp.net", timestamp: "t", body: "intruder", is_from_me: false })
    expect(seen).toHaveLength(0)
    rmSync(accessFile, { force: true })
  })
})
