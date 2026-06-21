import { expect, test } from "bun:test"
import { jwtClaims, readJson } from "../../src/core/agent-api/auth"

test("jwtClaims decodes the payload of a hand-built JWT (no verify)", () => {
  const payload = { "https://api.openai.com/auth": { chatgpt_account_id: "acc_1" } }
  const b64 = Buffer.from(JSON.stringify(payload), "utf8")
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "")
  const token = `header.${b64}.sig`
  const claims = jwtClaims(token)
  expect(claims["https://api.openai.com/auth"].chatgpt_account_id).toBe("acc_1")
})

test("jwtClaims returns null on malformed token", () => {
  expect(jwtClaims("notajwt")).toBeNull()
  expect(jwtClaims("a.!!!.c")).toBeNull()
})

test("readJson returns null when the read throws (bad path)", () => {
  const read = (_p: string): string => {
    throw new Error("ENOENT")
  }
  expect(readJson(read, "/does/not/exist.json")).toBeNull()
})

test("readJson returns null on invalid JSON and parses valid JSON", () => {
  expect(readJson(() => "{ not json", "x")).toBeNull()
  expect(readJson(() => '{"a":1}', "x")).toEqual({ a: 1 })
})
