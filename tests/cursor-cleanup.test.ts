import { test, expect } from "bun:test"
import { buildCleanupPrompt, cleanupViaCursor } from "../src/core/transcription/cursor-cleanup"

test("buildCleanupPrompt includes the draft, context, skills, and the only-text instruction", () => {
  const p = buildCleanupPrompt({
    draft: "Clouds High-Q model",
    recentMessages: [{ role: "user", text: "tell me about Claude's Haiku" }],
    skills: ["code-review", "brainstorming"],
  })
  expect(p).toContain("Clouds High-Q model")
  expect(p).toContain("Claude's Haiku")
  expect(p).toContain("code-review")
  expect(p).toContain("Output ONLY the corrected text")
})

test("cleanupViaCursor returns the trimmed cursor output", async () => {
  const r = await cleanupViaCursor(
    { draft: "Clouds High-Q model", recentMessages: [], skills: [] },
    { run: async () => ({ code: 0, out: "Claude's Haiku model\n" }) },
  )
  expect(r.text).toBe("Claude's Haiku model")
})

test("cleanupViaCursor passes the model through to the runner", async () => {
  let seen = ""
  await cleanupViaCursor(
    { draft: "x", recentMessages: [], skills: [] },
    { model: "composer-2.5-fast", run: async (_p, m) => { seen = m; return { code: 0, out: "x" } } },
  )
  expect(seen).toBe("composer-2.5-fast")
})

test("cleanupViaCursor throws on non-zero exit (so the caller degrades to the raw draft)", async () => {
  await expect(
    cleanupViaCursor({ draft: "x", recentMessages: [], skills: [] }, { run: async () => ({ code: 1, out: "" }) }),
  ).rejects.toThrow()
})

test("cleanupViaCursor throws on empty output", async () => {
  await expect(
    cleanupViaCursor({ draft: "x", recentMessages: [], skills: [] }, { run: async () => ({ code: 0, out: "   " }) }),
  ).rejects.toThrow()
})

test("cleanupViaCursor short-circuits an empty draft without invoking cursor", async () => {
  let called = false
  const r = await cleanupViaCursor(
    { draft: "  ", recentMessages: [], skills: [] },
    { run: async () => { called = true; return { code: 0, out: "x" } } },
  )
  expect(r.text).toBe("")
  expect(called).toBe(false)
})
