import { describe, expect, test } from "bun:test"
import { requireAtLeastOneChannel } from "./channels"

describe("requireAtLeastOneChannel", () => {
  test("ok when any single channel is enabled", () => {
    expect(requireAtLeastOneChannel(true, false, false)).toEqual({})
    expect(requireAtLeastOneChannel(false, true, false)).toEqual({})
    expect(requireAtLeastOneChannel(false, false, true)).toEqual({})
  })
  test("error when none enabled, and the message mentions WhatsApp", () => {
    const r = requireAtLeastOneChannel(false, false, false)
    expect(r.error).toBeTruthy()
    expect(r.error).toContain("WhatsApp")
  })
})
