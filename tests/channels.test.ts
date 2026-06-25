import { test, expect } from "bun:test"
import { requireAtLeastOneChannel } from "../src/shared/channels"

test("no channel configured: returns error", () => {
  const r = requireAtLeastOneChannel(false, false, false)
  expect(r.error).toBeDefined()
  expect(r.error).toContain("MUX_TELEGRAM_BOT_TOKEN")
  expect(r.error).toContain("MUX_WEB_PORT")
  expect(r.error).toContain("MUX_WHATSAPP_GOWA_URL")
})

test("telegram only: ok", () => {
  const r = requireAtLeastOneChannel(true, false, false)
  expect(r.error).toBeUndefined()
})

test("web only: ok", () => {
  const r = requireAtLeastOneChannel(false, true, false)
  expect(r.error).toBeUndefined()
})

test("whatsapp only: ok", () => {
  const r = requireAtLeastOneChannel(false, false, true)
  expect(r.error).toBeUndefined()
})

test("all channels: ok", () => {
  const r = requireAtLeastOneChannel(true, true, true)
  expect(r.error).toBeUndefined()
})
