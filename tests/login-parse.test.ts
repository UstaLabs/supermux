import { test, expect } from "bun:test"
import { parseCodexDeviceAuth, parseCursorLoginUrl } from "../src/core/agents/login/parse"

test("parseCodexDeviceAuth extracts the device URL and one-time code", () => {
  const out = [
    "Starting device authorization...",
    "Open this URL to sign in: https://auth.openai.com/codex/device",
    "Enter the code: ABCD-EFGH",
    "(this code expires in 15 minutes)",
  ].join("\n")
  expect(parseCodexDeviceAuth(out)).toEqual({ url: "https://auth.openai.com/codex/device", code: "ABCD-EFGH" })
})

test("parseCodexDeviceAuth returns null until both url and code are present", () => {
  expect(parseCodexDeviceAuth("Starting device authorization...")).toBeNull()
  expect(parseCodexDeviceAuth("https://auth.openai.com/codex/device only")).toBeNull()
})

test("parseCursorLoginUrl extracts the first http(s) URL", () => {
  const out = "To authenticate, open: https://cursor.com/loginDeepControl?token=abc123 in your browser"
  expect(parseCursorLoginUrl(out)).toBe("https://cursor.com/loginDeepControl?token=abc123")
})

test("parseCursorLoginUrl returns null when no URL present yet", () => {
  expect(parseCursorLoginUrl("Authenticating...")).toBeNull()
})

test("parseCodexDeviceAuth strips ANSI color codes (real codex 0.136 output)", () => {
  // Codex colorizes the URL + code; the raw escapes previously broke parsing.
  const out =
    "Follow these steps to sign in with ChatGPT:\n" +
    "1. Open this link in your browser and sign in\n" +
    "   \x1b[94mhttps://auth.openai.com/codex/device\x1b[0m\n" +
    "2. Enter this one-time code \x1b[90m(expires in 15 minutes)\x1b[0m\n" +
    "   \x1b[94m1IAY-K6W9X\x1b[0m\n"
  expect(parseCodexDeviceAuth(out)).toEqual({ url: "https://auth.openai.com/codex/device", code: "1IAY-K6W9X" })
})

test("parseCursorLoginUrl strips ANSI color codes", () => {
  expect(parseCursorLoginUrl("open: \x1b[94mhttps://cursor.com/loginDeepControl?x=1\x1b[0m now")).toBe(
    "https://cursor.com/loginDeepControl?x=1",
  )
})
