import { test, expect } from "bun:test"
import { requireAtLeastOneChannel } from "../src/shared/channels"

test("neither channel configured: returns error", () => {
  const r = requireAtLeastOneChannel(false, false)
  expect(r.error).toBeDefined()
  expect(r.error).toContain("MUX_TELEGRAM_BOT_TOKEN")
  expect(r.error).toContain("MUX_WEB_PORT")
})

test("telegram only: ok", () => {
  const r = requireAtLeastOneChannel(true, false)
  expect(r.error).toBeUndefined()
})

test("web only: ok", () => {
  const r = requireAtLeastOneChannel(false, true)
  expect(r.error).toBeUndefined()
})

test("both channels: ok", () => {
  const r = requireAtLeastOneChannel(true, true)
  expect(r.error).toBeUndefined()
})
