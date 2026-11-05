import { expect, test } from "bun:test"
import { envText, readEnvText } from "./env-file"

// Our five readers matched /^(\w+)=(.*)$/ per "\n"-split line: a line ending in "\r" never matched, so a .env saved
// by a Windows editor was ignored, and `supermux setup` or `connect` rewrote it without those keys.
test("a .env saved with CRLF line ends is read", () => {
  expect(readEnvText("MUX_WEB_PORT=8787\r\nMUX_RELAY_DOMAIN=relay.example\r\n")).toEqual(
    new Map([["MUX_WEB_PORT", "8787"], ["MUX_RELAY_DOMAIN", "relay.example"]]),
  )
})

// Every broker before this branch wrote `KEY=value` raw and read the value verbatim (/^(\w+)=(.*)$/), so an existing
// .env holds unquoted values with a # or spaces in them. util.parseEnv alone cut "pa#ss" to "pa", and a URL at its #:
// an update would have broken a password or a link that worked the day before.
test("a value an older broker wrote is read as it was: a #, spaces or quotes inside stay part of it", () => {
  expect(readEnvText("A=pa#ss\nB=https://h.example/#/frag\nC=on # kept\nD= padded \nE=it's\nF='open\n")).toEqual(new Map([
    ["A", "pa#ss"], ["B", "https://h.example/#/frag"], ["C", "on # kept"], ["D", " padded "], ["E", "it's"], ["F", "'open"],
  ]))
})

test("a value in matching quotes, as envText writes one that needs them, is unquoted", () => {
  expect(readEnvText("A='x#y'\nB=\" padded \"\nC=`it's`\n")).toEqual(new Map([["A", "x#y"], ["B", " padded "], ["C", "it's"]]))
})

test("comment lines are skipped, an export prefix is read, and of two lines with one key the first wins", () => {
  expect(readEnvText("# a comment\nexport K=first\nK=second\n")).toEqual(new Map([["K", "first"]]))
})

// An older broker (the one bundled in an older desktop app, say) reads every value verbatim: a value written bare is
// one it reads right. Only a value readEnvText would unquote gets quotes.
test("envText writes a value bare, as older brokers wrote and read it, unless it is wrapped in quotes", () => {
  expect(envText([["A", "x#y"], ["B", " padded "], ["C", "it's"], ["D", '"q"']])).toBe("A=x#y\nB= padded \nC=it's\nD='\"q\"'\n")
})

test("what envText writes reads back exactly", () => {
  const values = ["8787", "https://x.example/p?q=1", "x#y", " padded ", "it's", 'say "hi"', "", "C:\\new\\x", "$HOME", "a b", '"q"', "'s'", "`b`"]
  const entries = values.map((value, i) => [`K${i}`, value] as [string, string])
  expect(readEnvText(envText(entries))).toEqual(new Map(entries))
})

test("a value .env cannot hold is refused, not written wrong", () => {
  expect(() => envText([["K", "a'b`c\nd"]])).toThrow("K")
})
