import { test, expect } from "bun:test"
import { cleanupDraft } from "../src/core/transcription/voice-cleanup"

// NOTE: buildCleanupPrompt / CleanupInput are owned by src/core/agent-api/prompt.ts
// and their shape is asserted authoritatively in tests/agent-api/prompt.test.ts.
// Adapter-specific wire details live in tests/agent-api/*.test.ts. This file only
// covers cleanupDraft's engine-selection + cursor-cli fallback behavior.

const INPUT = { draft: "Clouds High-Q model", recentMessages: [], skills: [], glossary: [] }

// A fake fetch returning an OpenAI-shaped chat completion (used by opencode adapters).
const okFetch = (content: string): typeof fetch =>
  (async () => new Response(JSON.stringify({ choices: [{ message: { content } }] }), { status: 200 })) as unknown as typeof fetch
const failFetch: typeof fetch = (async () => new Response("err", { status: 500 })) as unknown as typeof fetch

// Auth file with an opencode zen key, so the opencode-zen adapter is available.
const zenAuth = () => JSON.stringify({ opencode: { key: "test-key" } })

test("cleanupDraft uses the selected engine and returns its text + name", async () => {
  const r = await cleanupDraft(INPUT, {
    engine: "opencode-zen",
    readFileFn: zenAuth,
    fetchFn: okFetch("Claude's Haiku model\n"),
  })
  expect(r.text).toBe("Claude's Haiku model")
  expect(r.engine).toBe("opencode-zen")
})

test("cleanupDraft falls back to cursor-cli when the primary engine throws", async () => {
  const r = await cleanupDraft(INPUT, {
    engine: "opencode-zen",
    readFileFn: zenAuth,
    fetchFn: failFetch,
    run: async () => ({ code: 0, out: "Claude Haiku model" }),
  })
  expect(r.text).toBe("Claude Haiku model")
  expect(r.engine).toBe("cursor-cli")
})

test("cleanupDraft falls back to cursor-cli when the primary engine is unavailable", async () => {
  // No opencode key on disk → opencode-zen.isAvailable() is false → straight to fallback.
  const r = await cleanupDraft(INPUT, {
    engine: "opencode-zen",
    readFileFn: () => {
      throw new Error("missing")
    },
    run: async () => ({ code: 0, out: "fixed" }),
  })
  expect(r.engine).toBe("cursor-cli")
  expect(r.text).toBe("fixed")
})

test("cleanupDraft throws when BOTH the engine and cursor-cli fail (caller keeps the raw draft)", async () => {
  await expect(
    cleanupDraft(INPUT, {
      engine: "opencode-zen",
      readFileFn: zenAuth,
      fetchFn: failFetch,
      run: async () => ({ code: 1, out: "" }),
    }),
  ).rejects.toThrow()
})

test("cleanupDraft short-circuits an empty draft", async () => {
  const r = await cleanupDraft({ draft: "  ", recentMessages: [], skills: [], glossary: [] }, { engine: "opencode-zen", readFileFn: zenAuth })
  expect(r.text).toBe("")
  expect(r.engine).toBe("none")
})

test("cleanupDraft uses cursor-cli directly when that engine is selected", async () => {
  let argv: string[] | undefined
  const r = await cleanupDraft(INPUT, {
    engine: "cursor-cli",
    run: async (a) => {
      argv = a
      return { code: 0, out: "cli-out" }
    },
  })
  expect(r.engine).toBe("cursor-cli")
  expect(r.text).toBe("cli-out")
  expect(argv?.[0]).toBe("cursor-agent")
})
