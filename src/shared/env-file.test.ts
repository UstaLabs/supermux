import { expect, test } from "bun:test"
import { envText, readEnvText } from "./env-file"

// Our five readers matched /^(\w+)=(.*)$/ per "\n"-split line: a line ending in "\r" never matched, so a .env saved
// by a Windows editor was ignored, and `supermux setup` or `connect` rewrote it without those keys.
test("a .env saved with CRLF line ends is read", () => {
  expect(readEnvText("MUX_WEB_PORT=8787\r\nMUX_RELAY_DOMAIN=relay.example\r\n")).toEqual(
    new Map([["MUX_WEB_PORT", "8787"], ["MUX_RELAY_DOMAIN", "relay.example"]]),
  )
})

test("quotes are taken off and a # outside them starts a comment, as everywhere else .env is read", () => {
  expect(readEnvText('A="https://x.example"\nB=on # the rest is a comment\n')).toEqual(new Map([["A", "https://x.example"], ["B", "on"]]))
})

test("what envText writes reads back exactly", () => {
  const values = ["8787", "https://x.example/p?q=1", "x#y", " padded ", "it's", 'say "hi"', "", "C:\\new\\x", "$HOME", "a b"]
  const entries = values.map((value, i) => [`K${i}`, value] as [string, string])
  expect(readEnvText(envText(entries))).toEqual(new Map(entries))
})

test("a value .env cannot hold is refused, not written wrong", () => {
  expect(() => envText([["K", "a'b`c\nd"]])).toThrow("K")
})
