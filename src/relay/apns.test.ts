import { expect, test } from "bun:test"
import { createApnsAdapter } from "./apns"

const cfg = { keyP8: "-", keyId: "K", teamId: "T", bundleId: "dev.supermux.ios", sandbox: true }

test("maps 200 → ok", async () => {
  const a = createApnsAdapter(cfg, async () => ({ status: 200, body: "" }))
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: true })
})

test("maps 410 (Unregistered) → gone", async () => {
  const a = createApnsAdapter(cfg, async () => ({ status: 410, body: '{"reason":"Unregistered"}' }))
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: false, gone: true })
})

test("maps other 4xx/5xx → not gone", async () => {
  const a = createApnsAdapter(cfg, async () => ({ status: 503, body: "" }))
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: false, gone: false })
})
