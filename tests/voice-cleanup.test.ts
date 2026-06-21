import { test, expect } from "bun:test"
import { cleanupDraft } from "../src/core/transcription/voice-cleanup"

// NOTE: buildCleanupPrompt / CleanupInput are owned by src/core/agent-api/prompt.ts
// and their shape is asserted authoritatively in tests/agent-api/prompt.test.ts.
// This file only covers cleanupDraft's engine-selection/fallback behavior.

// A fake fetch that returns an OpenAI-shaped chat completion with the given content.
const okFetch = (content: string): typeof fetch =>
  (async () => new Response(JSON.stringify({ choices: [{ message: { content } }] }), { status: 200 })) as unknown as typeof fetch
const failFetch: typeof fetch = (async () => new Response("err", { status: 500 })) as unknown as typeof fetch

test("cleanupDraft uses the direct OpenCode Zen API and returns its content", async () => {
  const r = await cleanupDraft(
    { draft: "Clouds High-Q model", recentMessages: [], skills: [] },
    { readKey: () => "test-key", fetchFn: okFetch("Claude's Haiku model\n") },
  )
  expect(r.text).toBe("Claude's Haiku model")
  expect(r.engine).toBe("opencode-api")
})

test("cleanupDraft falls back to the cursor CLI when the API errors", async () => {
  const r = await cleanupDraft(
    { draft: "Clouds High-Q model", recentMessages: [], skills: [] },
    { readKey: () => "test-key", fetchFn: failFetch, run: async () => ({ code: 0, out: "Claude Haiku model" }) },
  )
  expect(r.text).toBe("Claude Haiku model")
  expect(r.engine).toBe("cursor")
})

test("cleanupDraft falls back to the CLI when there is no opencode key", async () => {
  const r = await cleanupDraft(
    { draft: "x", recentMessages: [], skills: [] },
    { readKey: () => null, run: async () => ({ code: 0, out: "fixed" }) },
  )
  expect(r.engine).toBe("cursor")
  expect(r.text).toBe("fixed")
})

test("cleanupDraft throws when BOTH the API and the CLI fail (caller keeps the raw draft)", async () => {
  await expect(
    cleanupDraft(
      { draft: "x", recentMessages: [], skills: [] },
      { readKey: () => "k", fetchFn: failFetch, run: async () => ({ code: 1, out: "" }) },
    ),
  ).rejects.toThrow()
})

test("cleanupDraft short-circuits an empty draft", async () => {
  const r = await cleanupDraft({ draft: "  ", recentMessages: [], skills: [] }, { readKey: () => "k", fetchFn: okFetch("x") })
  expect(r.text).toBe("")
  expect(r.engine).toBe("none")
})

test("prefer:cli skips the API entirely", async () => {
  let apiCalled = false
  const r = await cleanupDraft(
    { draft: "x", recentMessages: [], skills: [] },
    {
      prefer: "cli",
      fetchFn: (async () => { apiCalled = true; return new Response("{}", { status: 200 }) }) as unknown as typeof fetch,
      run: async () => ({ code: 0, out: "cli-out" }),
    },
  )
  expect(apiCalled).toBe(false)
  expect(r.engine).toBe("cursor")
  expect(r.text).toBe("cli-out")
})
