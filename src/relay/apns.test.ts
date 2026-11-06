import { expect, test } from "bun:test"
import { createApnsAdapter } from "./apns"

const cfg = { keyP8: "-", keyId: "K", teamId: "T", bundleId: "dev.supermux.ios", sandbox: true }

test("maps 200 → ok", async () => {
  const a = createApnsAdapter(cfg, async () => ({ status: 200, body: "" }))
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: true })
})

test("maps 410 (Unregistered) → gone", async () => {
  const a = createApnsAdapter(cfg, async () => ({ status: 410, body: '{"reason":"Unregistered"}' }))
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: false, gone: true, reason: "apns_410_Unregistered" })
})

test("maps other 4xx/5xx → not gone", async () => {
  const a = createApnsAdapter(cfg, async () => ({ status: 503, body: "" }))
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: false, gone: false, reason: "apns_503" })
})

// Card #86: a sandbox token sent to production APNs is BadDeviceToken; the
// reason now travels back to the broker's log instead of a bare "gone".
test("names Apple's reason for a dropped token", async () => {
  const a = createApnsAdapter(cfg, async () => ({ status: 400, body: '{"reason":"BadDeviceToken"}' }))
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: false, gone: true, reason: "apns_400_BadDeviceToken" })
})

test("names Apple's reason for a refused provider token", async () => {
  const a = createApnsAdapter(cfg, async () => ({ status: 403, body: '{"reason":"InvalidProviderToken"}' }))
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: false, gone: false, reason: "apns_403_InvalidProviderToken" })
})

test("silent send uses background push-type with content-available, no alert", async () => {
  let capturedHeaders: Record<string, string> = {}
  let capturedBody: any = {}
  const a = createApnsAdapter(cfg, async (o) => {
    capturedHeaders = o.headers
    capturedBody = JSON.parse(o.body)
    return { status: 200, body: "" }
  })
  await a.send("tok", { ciphertext: "blob" } as any, { silent: true })
  expect(capturedHeaders["apns-push-type"]).toBe("background")
  expect(capturedBody.aps["content-available"]).toBe(1)
  expect(capturedBody.aps.alert).toBeUndefined()
  expect(capturedBody.aps["mutable-content"]).toBeUndefined()
  expect(capturedBody.data).toBe("blob")
})

test("non-silent send (default) still uses alert push-type with mutable-content", async () => {
  let capturedHeaders: Record<string, string> = {}
  let capturedBody: any = {}
  const a = createApnsAdapter(cfg, async (o) => {
    capturedHeaders = o.headers
    capturedBody = JSON.parse(o.body)
    return { status: 200, body: "" }
  })
  await a.send("tok", { ciphertext: "blob" } as any)
  expect(capturedHeaders["apns-push-type"]).toBe("alert")
  expect(capturedBody.aps.alert).toBeDefined()
  expect(capturedBody.aps["mutable-content"]).toBe(1)
})
