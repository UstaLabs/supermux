import { describe, expect, test } from "bun:test"
import { parseAddress, channelOf, isWebChat } from "./address"

describe("parseAddress", () => {
  test("the bare web id — the single logical web channel", () => {
    expect(parseAddress("web")).toEqual({ channel: "web", chatId: "web" })
  })

  // Long-lived sessions from before the single-channel collapse still carry
  // `web:<device>`. Matching only one of the two spellings has already caused
  // an outage (see transform-outbound's comment), so both must resolve here.
  test("the legacy per-device web id", () => {
    expect(parseAddress("web:iphone")).toEqual({ channel: "web", chatId: "web:iphone" })
  })

  test("a namespaced id keeps its own prefix", () => {
    expect(parseAddress("telegram:8264224268")).toEqual({ channel: "telegram", chatId: "telegram:8264224268" })
    expect(parseAddress("whatsapp:905551234567@s.whatsapp.net")).toEqual({
      channel: "whatsapp", chatId: "whatsapp:905551234567@s.whatsapp.net",
    })
  })

  // Pre-namespacing shim sessions hold a raw telegram chat id across a broker
  // upgrade. A bare value that is not "web" is one of those.
  test("a bare id is a legacy telegram chat", () => {
    expect(parseAddress("8264224268")).toEqual({ channel: "telegram", chatId: "telegram:8264224268" })
    expect(parseAddress("-1001234567890")).toEqual({ channel: "telegram", chatId: "telegram:-1001234567890" })
  })

  // A new channel must need no change here: its own prefix is its channel name.
  test("an unknown prefix names its own channel", () => {
    expect(parseAddress("signal:abc")).toEqual({ channel: "signal", chatId: "signal:abc" })
  })

  test("nothing usable returns null", () => {
    expect(parseAddress("")).toBeNull()
    expect(parseAddress("   ")).toBeNull()
    expect(parseAddress(undefined)).toBeNull()
    expect(parseAddress(null)).toBeNull()
    expect(parseAddress(42 as unknown as string)).toBeNull()
    expect(parseAddress(":no-prefix")).toBeNull()
  })
})

describe("channelOf", () => {
  test("returns just the channel name", () => {
    expect(channelOf("web")).toBe("web")
    expect(channelOf("web:iphone")).toBe("web")
    expect(channelOf("telegram:1")).toBe("telegram")
    expect(channelOf("1")).toBe("telegram")
    expect(channelOf("")).toBeUndefined()
  })
})

describe("isWebChat", () => {
  test("true for both web spellings, false otherwise", () => {
    expect(isWebChat("web")).toBe(true)
    expect(isWebChat("web:iphone")).toBe(true)
    expect(isWebChat("telegram:1")).toBe(false)
    expect(isWebChat("1")).toBe(false)
    expect(isWebChat(undefined)).toBe(false)
  })
})
