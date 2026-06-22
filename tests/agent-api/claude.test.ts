import { afterEach, beforeEach, expect, test } from "bun:test"
import { claudeAdapter } from "../../src/core/agent-api/adapters/claude"

// Build a ~/.claude/.credentials.json string with the given oauth fields.
const credsFile = (over: Record<string, any> = {}): string =>
  JSON.stringify({
    claudeAiOauth: {
      accessToken: "sk-ant-oauth-tok",
      refreshToken: "refresh-tok",
      expiresAt: Date.now() + 3_600_000,
      ...over,
    },
  })

const okResponse = (text: string): Response =>
  new Response(
    JSON.stringify({ content: [{ type: "text", text }] }),
    { status: 200, headers: { "Content-Type": "application/json" } },
  )

let savedFlag: string | undefined
beforeEach(() => {
  savedFlag = process.env.MUX_VOICE_CLEANUP_ALLOW_CLAUDE
  delete process.env.MUX_VOICE_CLEANUP_ALLOW_CLAUDE
})
afterEach(() => {
  if (savedFlag === undefined) delete process.env.MUX_VOICE_CLEANUP_ALLOW_CLAUDE
  else process.env.MUX_VOICE_CLEANUP_ALLOW_CLAUDE = savedFlag
})

test("name is claude", () => {
  const a = claudeAdapter({ readFileFn: () => credsFile(), credsPath: "/fake/creds.json" })
  expect(a.name).toBe("claude")
})

test("isAvailable() is FALSE without the opt-in flag even with creds present", () => {
  const a = claudeAdapter({ readFileFn: () => credsFile(), credsPath: "/fake/creds.json" })
  expect(a.isAvailable()).toBe(false)
})

test("isAvailable() is TRUE with the flag + creds", () => {
  process.env.MUX_VOICE_CLEANUP_ALLOW_CLAUDE = "1"
  const a = claudeAdapter({ readFileFn: () => credsFile(), credsPath: "/fake/creds.json" })
  expect(a.isAvailable()).toBe(true)
})

test("isAvailable() is FALSE with the flag but missing creds", () => {
  process.env.MUX_VOICE_CLEANUP_ALLOW_CLAUDE = "1"
  const a = claudeAdapter({
    readFileFn: () => credsFile({ accessToken: undefined }),
    credsPath: "/fake/creds.json",
  })
  expect(a.isAvailable()).toBe(false)

  const noFile = claudeAdapter({
    readFileFn: () => {
      throw new Error("ENOENT")
    },
    credsPath: "/fake/creds.json",
  })
  expect(noFile.isAvailable()).toBe(false)
})

test("complete() POSTs the anthropic messages endpoint with oauth headers + body, parses content[0].text", async () => {
  process.env.MUX_VOICE_CLEANUP_ALLOW_CLAUDE = "1"
  let seen: { url?: string; init?: RequestInit } = {}
  const fetchFn: typeof fetch = (async (url: any, init: any) => {
    seen = { url, init }
    return okResponse("hello world")
  }) as unknown as typeof fetch

  const a = claudeAdapter({ fetchFn, readFileFn: () => credsFile(), credsPath: "/fake/creds.json" })
  const out = await a.complete("Correct: helo wrld")

  expect(out).toBe("hello world")
  expect(seen.url).toBe("https://api.anthropic.com/v1/messages")
  expect(seen.init?.method).toBe("POST")

  const headers = seen.init?.headers as Record<string, string>
  expect(headers["Authorization"]).toBe("Bearer sk-ant-oauth-tok")
  expect(headers["anthropic-beta"]).toBe("claude-code-20250219,oauth-2025-04-20")
  expect(headers["anthropic-version"]).toBe("2023-06-01")
  expect(headers["Content-Type"]).toBe("application/json")
  expect(headers["x-api-key"]).toBeUndefined()

  const body = JSON.parse(String(seen.init?.body))
  expect(typeof body.model).toBe("string")
  expect(typeof body.max_tokens).toBe("number")
  expect(body.system).toBe("You are Claude Code, Anthropic's official CLI for Claude.")
  expect(body.messages).toEqual([{ role: "user", content: "Correct: helo wrld" }])
})

test("complete() honors opts.model", async () => {
  process.env.MUX_VOICE_CLEANUP_ALLOW_CLAUDE = "1"
  let seen: { init?: RequestInit } = {}
  const fetchFn: typeof fetch = (async (_url: any, init: any) => {
    seen = { init }
    return okResponse("ok")
  }) as unknown as typeof fetch

  const a = claudeAdapter({ fetchFn, readFileFn: () => credsFile(), credsPath: "/fake/creds.json" })
  await a.complete("x", { model: "claude-custom" })
  const body = JSON.parse(String(seen.init?.body))
  expect(body.model).toBe("claude-custom")
})

test("complete() throws when the gate is off", async () => {
  const fetchFn: typeof fetch = (async () => okResponse("ok")) as unknown as typeof fetch
  const a = claudeAdapter({ fetchFn, readFileFn: () => credsFile(), credsPath: "/fake/creds.json" })
  await expect(a.complete("x")).rejects.toThrow()
})

test("complete() throws on 401 (no refresh)", async () => {
  process.env.MUX_VOICE_CLEANUP_ALLOW_CLAUDE = "1"
  let calls = 0
  const fetchFn: typeof fetch = (async () => {
    calls++
    return new Response("unauthorized", { status: 401 })
  }) as unknown as typeof fetch
  const a = claudeAdapter({ fetchFn, readFileFn: () => credsFile(), credsPath: "/fake/creds.json" })
  await expect(a.complete("x")).rejects.toThrow()
  expect(calls).toBe(1) // no refresh retry
})

test("complete() throws on empty assistant text", async () => {
  process.env.MUX_VOICE_CLEANUP_ALLOW_CLAUDE = "1"
  const fetchFn: typeof fetch = (async () =>
    new Response(JSON.stringify({ content: [{ type: "text", text: "   " }] }), {
      status: 200,
    })) as unknown as typeof fetch
  const a = claudeAdapter({ fetchFn, readFileFn: () => credsFile(), credsPath: "/fake/creds.json" })
  await expect(a.complete("x")).rejects.toThrow()
})

test("complete() throws when creds are missing", async () => {
  process.env.MUX_VOICE_CLEANUP_ALLOW_CLAUDE = "1"
  const fetchFn: typeof fetch = (async () => okResponse("ok")) as unknown as typeof fetch
  const a = claudeAdapter({
    fetchFn,
    readFileFn: () => credsFile({ accessToken: undefined }),
    credsPath: "/fake/creds.json",
  })
  await expect(a.complete("x")).rejects.toThrow()
})
