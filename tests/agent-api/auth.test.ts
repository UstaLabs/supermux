import { expect, test } from "bun:test"
import { jwtClaims, readJson, refreshOAuth } from "../../src/core/agent-api/auth"

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

test("refreshOAuth posts a form-urlencoded grant and returns the parsed JSON token", async () => {
  let seen: { url?: string; init?: RequestInit } = {}
  const f: typeof fetch = (async (url: any, init: any) => {
    seen = { url, init }
    return new Response(JSON.stringify({ access_token: "tok_new", refresh_token: "ref_new" }), { status: 200 })
  }) as unknown as typeof fetch

  const out = await refreshOAuth(f, "https://auth.example.com/token", {
    grant_type: "refresh_token",
    refresh_token: "ref_old",
    client_id: "cid",
  })

  expect(out).toEqual({ access_token: "tok_new", refresh_token: "ref_new" })
  expect(seen.url).toBe("https://auth.example.com/token")
  expect(seen.init?.method).toBe("POST")
  expect((seen.init?.headers as Record<string, string>)["Content-Type"]).toBe("application/x-www-form-urlencoded")
  // body is x-www-form-urlencoded, not JSON
  const body = String(seen.init?.body)
  expect(body).toContain("grant_type=refresh_token")
  expect(body).toContain("refresh_token=ref_old")
  expect(body).toContain("client_id=cid")
})

test("refreshOAuth throws on a non-ok status (surfacing the status code)", async () => {
  const f: typeof fetch = (async () => new Response("nope", { status: 401 })) as unknown as typeof fetch
  await expect(refreshOAuth(f, "https://auth.example.com/token", { grant_type: "refresh_token" })).rejects.toThrow("401")
})
