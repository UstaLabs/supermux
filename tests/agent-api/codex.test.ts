import { expect, test } from "bun:test"
import { codexAdapter } from "../../src/core/agent-api/adapters/codex"

// Build a real-shaped auth.json string with the given fields.
const authFile = (over: Record<string, any> = {}): string =>
  JSON.stringify({
    auth_mode: "chatgpt",
    OPENAI_API_KEY: null,
    tokens: { access_token: "tok_access", refresh_token: "tok_refresh", account_id: "acc_from_field", ...over },
    last_refresh: "2026-06-21T00:00:00Z",
  })

const okResponse = (text: string): Response =>
  new Response(
    JSON.stringify({
      output: [{ type: "message", role: "assistant", content: [{ type: "output_text", text }] }],
    }),
    { status: 200, headers: { "Content-Type": "application/json" } },
  )

test("isAvailable() is true when auth.json has tokens.access_token", () => {
  const a = codexAdapter({ readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  expect(a.isAvailable()).toBe(true)
})

test("isAvailable() is false when the file is missing", () => {
  const a = codexAdapter({
    readFileFn: () => {
      throw new Error("ENOENT")
    },
    authPath: "/fake/auth.json",
  })
  expect(a.isAvailable()).toBe(false)
})

test("isAvailable() is false when access_token is missing", () => {
  const a = codexAdapter({ readFileFn: () => authFile({ access_token: undefined }), authPath: "/fake/auth.json" })
  expect(a.isAvailable()).toBe(false)
})

test("complete() POSTs to the codex responses endpoint with the right headers and body, parses output", async () => {
  let seen: { url?: string; init?: RequestInit } = {}
  const fetchFn: typeof fetch = (async (url: any, init: any) => {
    seen = { url, init }
    return okResponse("hello world")
  }) as unknown as typeof fetch

  const a = codexAdapter({ fetchFn, readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  const out = await a.complete("Correct: helo wrld")

  expect(out).toBe("hello world")
  expect(seen.url).toBe("https://chatgpt.com/backend-api/codex/responses")
  expect(seen.init?.method).toBe("POST")

  const headers = seen.init?.headers as Record<string, string>
  expect(headers["Authorization"]).toBe("Bearer tok_access")
  expect(headers["chatgpt-account-id"]).toBe("acc_from_field")
  expect(headers["originator"]).toBe("codex_cli_rs")
  expect(headers["OpenAI-Beta"]).toBe("responses=experimental")
  expect(headers["Content-Type"]).toBe("application/json")
  expect(headers["x-api-key"]).toBeUndefined()

  const body = JSON.parse(String(seen.init?.body))
  expect(body.store).toBe(false)
  // The ChatGPT-account endpoint forces streaming; the adapter parses the SSE.
  expect(body.stream).toBe(true)
  // The endpoint also requires an `instructions` field.
  expect(typeof body.instructions).toBe("string")
  expect(body.instructions.length).toBeGreaterThan(0)
  expect(body.input).toEqual([{ role: "user", content: [{ type: "input_text", text: "Correct: helo wrld" }] }])
  expect(typeof body.model).toBe("string")
})

test("complete() derives chatgpt-account-id from the JWT claim when tokens.account_id is absent", async () => {
  // Build a JWT whose payload carries the chatgpt account id claim.
  const payload = { "https://api.openai.com/auth": { chatgpt_account_id: "acc_from_jwt" } }
  const b64 = Buffer.from(JSON.stringify(payload), "utf8")
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "")
  const jwt = `header.${b64}.sig`

  let seen: { init?: RequestInit } = {}
  const fetchFn: typeof fetch = (async (_url: any, init: any) => {
    seen = { init }
    return okResponse("ok")
  }) as unknown as typeof fetch

  const a = codexAdapter({
    fetchFn,
    readFileFn: () => authFile({ access_token: jwt, account_id: undefined }),
    authPath: "/fake/auth.json",
  })
  await a.complete("x")
  const headers = seen.init?.headers as Record<string, string>
  expect(headers["chatgpt-account-id"]).toBe("acc_from_jwt")
})

test("complete() accumulates SSE output_text.delta events (the live shape)", async () => {
  const sse = [
    `event: response.created\ndata: ${JSON.stringify({ type: "response.created" })}`,
    `event: response.output_text.delta\ndata: ${JSON.stringify({ type: "response.output_text.delta", delta: "hello" })}`,
    `event: response.output_text.delta\ndata: ${JSON.stringify({ type: "response.output_text.delta", delta: " world" })}`,
    `event: response.output_text.done\ndata: ${JSON.stringify({ type: "response.output_text.done", text: "hello world" })}`,
    `event: response.completed\ndata: ${JSON.stringify({ type: "response.completed", response: { output: [] } })}`,
  ].join("\n\n")
  const fetchFn: typeof fetch = (async () =>
    new Response(sse, { status: 200, headers: { "Content-Type": "text/event-stream" } })) as unknown as typeof fetch
  const a = codexAdapter({ fetchFn, readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  expect(await a.complete("x")).toBe("hello world")
})

test("complete() parses a top-level output_text field", async () => {
  const fetchFn: typeof fetch = (async () =>
    new Response(JSON.stringify({ output_text: "flat text" }), { status: 200 })) as unknown as typeof fetch
  const a = codexAdapter({ fetchFn, readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  expect(await a.complete("x")).toBe("flat text")
})

test("complete() refreshes once on 401, writes tokens back, and retries", async () => {
  let call = 0
  const writes: { path: string; data: string }[] = []
  const fetchFn: typeof fetch = (async (url: any, init: any) => {
    if (String(url).includes("/oauth/token")) {
      return new Response(JSON.stringify({ access_token: "tok_new", refresh_token: "ref_new" }), { status: 200 })
    }
    call++
    if (call === 1) return new Response("unauthorized", { status: 401 })
    // second responses call should use the refreshed token
    const headers = init?.headers as Record<string, string>
    expect(headers["Authorization"]).toBe("Bearer tok_new")
    return okResponse("after refresh")
  }) as unknown as typeof fetch

  const a = codexAdapter({
    fetchFn,
    readFileFn: () => authFile(),
    writeFileFn: (path: string, data: string) => {
      writes.push({ path, data })
    },
    authPath: "/fake/auth.json",
  })
  const out = await a.complete("x")
  expect(out).toBe("after refresh")
  expect(writes.length).toBe(1)
  const written = JSON.parse(writes[0]!.data)
  expect(written.tokens.access_token).toBe("tok_new")
  expect(written.tokens.refresh_token).toBe("ref_new")
})

test("complete() throws on empty assistant text", async () => {
  const fetchFn: typeof fetch = (async () => okResponse("   ")) as unknown as typeof fetch
  const a = codexAdapter({ fetchFn, readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  await expect(a.complete("x")).rejects.toThrow()
})

test("name is codex", () => {
  const a = codexAdapter({ readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  expect(a.name).toBe("codex")
})
