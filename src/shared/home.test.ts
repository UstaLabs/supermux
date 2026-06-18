import { afterEach, beforeEach, expect, test } from "bun:test"
import { username } from "./home"

// username() must never resolve to "" — printing `loginctl enable-linger $USER`
// (or an empty arg) is the rough edge we're fixing. Env first, OS user as the
// always-available fallback (e.g. a WSL shell where $USER/$LOGNAME are unset).

let saved: Record<string, string | undefined>
const KEYS = ["USER", "LOGNAME"] as const

beforeEach(() => {
  saved = {}
  for (const k of KEYS) saved[k] = process.env[k]
})
afterEach(() => {
  for (const k of KEYS) {
    if (saved[k] === undefined) delete process.env[k]
    else process.env[k] = saved[k]
  }
})

test("username prefers $USER when set", () => {
  process.env.USER = "alice"
  process.env.LOGNAME = "ignored"
  expect(username()).toBe("alice")
})

test("username falls back to $LOGNAME when $USER is empty", () => {
  process.env.USER = ""
  process.env.LOGNAME = "bob"
  expect(username()).toBe("bob")
})

test("username falls back to the OS user (never empty) when no env var is set", () => {
  delete process.env.USER
  delete process.env.LOGNAME
  // os.userInfo().username is always available on a real host.
  expect(username().length).toBeGreaterThan(0)
})
