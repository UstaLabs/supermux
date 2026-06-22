import { expect, test } from "bun:test"
import { opencodeAdapter } from "../../src/core/agent-api/adapters/opencode"

// Build an opencode auth.json string with the given key fields.
const authFile = (over: Record<string, any> = {}): string =>
  JSON.stringify({
    opencode: { type: "api", key: "zen_key" },
    "opencode-go": { type: "api", key: "go_key" },
    ...over,
  })

const okResponse = (text: string): Response =>
  new Response(
    JSON.stringify({ choices: [{ message: { role: "assistant", content: text } }] }),
    { status: 200, headers: { "Content-Type": "application/json" } },
  )

test("name reflects the variant", () => {
  const zen = opencodeAdapter("zen", { readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  const go = opencodeAdapter("go", { readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  expect(zen.name).toBe("opencode-zen")
  expect(go.name).toBe("opencode-go")
})

test("isAvailable() reflects the zen key presence", () => {
  const present = opencodeAdapter("zen", { readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  expect(present.isAvailable()).toBe(true)

  const missing = opencodeAdapter("zen", {
    readFileFn: () => authFile({ opencode: { type: "api" } }),
    authPath: "/fake/auth.json",
  })
  expect(missing.isAvailable()).toBe(false)

  const noFile = opencodeAdapter("zen", {
    readFileFn: () => {
      throw new Error("ENOENT")
    },
    authPath: "/fake/auth.json",
  })
  expect(noFile.isAvailable()).toBe(false)
})

test("isAvailable() reflects the go key presence", () => {
  const present = opencodeAdapter("go", { readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  expect(present.isAvailable()).toBe(true)

  const missing = opencodeAdapter("go", {
    readFileFn: () => authFile({ "opencode-go": { type: "api" } }),
    authPath: "/fake/auth.json",
  })
  expect(missing.isAvailable()).toBe(false)
})

test("zen complete() POSTs the zen endpoint with the zen key and OpenAI body, parses content", async () => {
  let seen: { url?: string; init?: RequestInit } = {}
  const fetchFn: typeof fetch = (async (url: any, init: any) => {
    seen = { url, init }
    return okResponse("hello world")
  }) as unknown as typeof fetch

  const a = opencodeAdapter("zen", { fetchFn, readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  const out = await a.complete("Correct: helo wrld")

  expect(out).toBe("hello world")
  expect(seen.url).toBe("https://opencode.ai/zen/v1/chat/completions")
  expect(seen.init?.method).toBe("POST")

  const headers = seen.init?.headers as Record<string, string>
  expect(headers["Authorization"]).toBe("Bearer zen_key")
  expect(headers["Content-Type"]).toBe("application/json")

  const body = JSON.parse(String(seen.init?.body))
  expect(typeof body.model).toBe("string")
  expect(body.messages).toEqual([{ role: "user", content: "Correct: helo wrld" }])
})

test("go complete() POSTs the go endpoint with the go key", async () => {
  let seen: { url?: string; init?: RequestInit } = {}
  const fetchFn: typeof fetch = (async (url: any, init: any) => {
    seen = { url, init }
    return okResponse("ok")
  }) as unknown as typeof fetch

  const a = opencodeAdapter("go", { fetchFn, readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  await a.complete("x")

  expect(seen.url).toBe("https://api.opencode.ai/go/v1/chat/completions")
  const headers = seen.init?.headers as Record<string, string>
  expect(headers["Authorization"]).toBe("Bearer go_key")
})

test("complete() honors opts.model", async () => {
  let seen: { init?: RequestInit } = {}
  const fetchFn: typeof fetch = (async (_url: any, init: any) => {
    seen = { init }
    return okResponse("ok")
  }) as unknown as typeof fetch

  const a = opencodeAdapter("zen", { fetchFn, readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  await a.complete("x", { model: "custom-model" })
  const body = JSON.parse(String(seen.init?.body))
  expect(body.model).toBe("custom-model")
})

test("complete() throws on non-2xx", async () => {
  const fetchFn: typeof fetch = (async () =>
    new Response("nope", { status: 402 })) as unknown as typeof fetch
  const a = opencodeAdapter("zen", { fetchFn, readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  await expect(a.complete("x")).rejects.toThrow()
})

test("complete() throws on empty assistant text", async () => {
  const fetchFn: typeof fetch = (async () => okResponse("   ")) as unknown as typeof fetch
  const a = opencodeAdapter("zen", { fetchFn, readFileFn: () => authFile(), authPath: "/fake/auth.json" })
  await expect(a.complete("x")).rejects.toThrow()
})

test("complete() throws when the key is missing", async () => {
  const fetchFn: typeof fetch = (async () => okResponse("ok")) as unknown as typeof fetch
  const a = opencodeAdapter("zen", {
    fetchFn,
    readFileFn: () => authFile({ opencode: { type: "api" } }),
    authPath: "/fake/auth.json",
  })
  await expect(a.complete("x")).rejects.toThrow()
})
