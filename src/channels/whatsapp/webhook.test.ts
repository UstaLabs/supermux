import { describe, expect, test } from "bun:test"
import { createHmac } from "crypto"
import { createWebhookHandler } from "./webhook"

const SECRET = "s3cr3t"
function signed(bodyObj: any): Request {
  const body = JSON.stringify(bodyObj)
  const sig = "sha256=" + createHmac("sha256", SECRET).update(body, "utf8").digest("hex")
  return new Request("http://127.0.0.1/webhook", { method: "POST", body, headers: { "X-Hub-Signature-256": sig } })
}

describe("createWebhookHandler", () => {
  test("invokes onMessage for a signed inbound message event", async () => {
    const seen: any[] = []
    const h = createWebhookHandler({ secret: SECRET, onMessage: (p) => seen.push(p) })
    const res = await h(signed({ event: "message", payload: { id: "M1", is_from_me: false, body: "hi" } }))
    expect(res.status).toBe(200)
    expect(seen).toHaveLength(1)
    expect(seen[0].id).toBe("M1")
  })
  test("rejects a bad signature with 401 and does not call onMessage", async () => {
    const seen: any[] = []
    const h = createWebhookHandler({ secret: SECRET, onMessage: (p) => seen.push(p) })
    const bad = new Request("http://127.0.0.1/webhook", { method: "POST", body: JSON.stringify({ event: "message", payload: {} }), headers: { "X-Hub-Signature-256": "sha256=deadbeef" } })
    const res = await h(bad)
    expect(res.status).toBe(401)
    expect(seen).toHaveLength(0)
  })
  test("ignores our own outbound (is_from_me) and non-message events", async () => {
    const seen: any[] = []
    const h = createWebhookHandler({ secret: SECRET, onMessage: (p) => seen.push(p) })
    await h(signed({ event: "message", payload: { id: "M2", is_from_me: true } }))
    await h(signed({ event: "message.ack", payload: { id: "M3" } }))
    expect(seen).toHaveLength(0)
  })
})
