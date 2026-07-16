import { describe, expect, test } from "bun:test"
import { parseCodexDeviceAuth, parseCursorLoginUrl } from "./parse"

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
})
