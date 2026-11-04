import { expect, test } from "bun:test"
import { Unsupported } from "@supermux/platform"

// What a gap answers where the OS (or the runtime) has no way to do it: typed, so a caller can fall back on purpose
// (a link becomes a copy, a credential probe reads as "no") instead of matching a message.
test("Unsupported says what is unsupported, with a code callers can test", () => {
  const e = new Unsupported("credentials.hasGenericCredential", "no bun:ffi in this runtime")
  expect(e).toBeInstanceOf(Error)
  expect(e.code).toBe("unsupported")
  expect(e.what).toBe("credentials.hasGenericCredential")
  expect(e.message).toBe("credentials.hasGenericCredential is not supported here: no bun:ffi in this runtime")
})
