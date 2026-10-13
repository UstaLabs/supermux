import { describe, expect, test } from "bun:test"
import { parseAntigravityLoginUrl, parseCodexDeviceAuth, parseCursorLoginUrl } from "./parse"

describe("login output parsers", () => {
  test("extracts Claude's visible URL without OSC 8 hyperlink escapes", () => {
    const url = "https://claude.com/cai/oauth/authorize?code=true&state=abc123"
    const output = `If the browser didn't open, visit: \x1b]8;;${url}\x07${url}\x1b]8;;\x07\r\nPaste code here if prompted > `

    expect(parseCursorLoginUrl(output)).toBe(url)
  })

  test("extracts colorized Codex device auth", () => {
    expect(parseCodexDeviceAuth("\x1b[94mhttps://auth.example/device\x1b[0m code ABCD-EFGH")).toEqual({
      url: "https://auth.example/device",
      code: "ABCD-EFGH",
    })
  })

  // Captured from agy 1.3.4 print mode under `script` with an empty HOME (state shortened).
  const AGY_URL = "https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com&code_challenge=H8Tt&code_challenge_method=S256&prompt=consent&redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback&response_type=code&scope=openid&state=QQnI"

  test("extracts agy's Google OAuth URL from its print-mode login prompt", () => {
    const output = `Authentication required. Please visit the URL to log in:\r\n  ${AGY_URL}\r\n\r\nWaiting for authentication (timeout 60s)...\r\nOr, paste the authorization code here and press Enter:\r\n`
    expect(parseAntigravityLoginUrl(output)).toBe(AGY_URL)
  })

  test("agy: only an accounts.google.com URL counts, colorized or not", () => {
    expect(parseAntigravityLoginUrl("see https://antigravity.google/docs for help")).toBeNull()
    expect(parseAntigravityLoginUrl("Authentication required")).toBeNull()
    expect(parseAntigravityLoginUrl(`docs https://antigravity.google/docs then \x1b[36m${AGY_URL}\x1b[0m`)).toBe(AGY_URL)
  })
})
