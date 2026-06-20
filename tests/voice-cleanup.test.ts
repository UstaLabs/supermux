import { test, expect } from "bun:test"
import { buildCleanupPrompt, cleanupArgv, cleanupDraft } from "../src/core/transcription/voice-cleanup"

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

test("cleanupArgv builds an opencode one-shot run (positional message, --pure)", () => {
  const argv = cleanupArgv("opencode", "opencode/deepseek-v4-flash-free", "PROMPT")
  expect(argv).toEqual(["opencode", "run", "--pure", "-m", "opencode/deepseek-v4-flash-free", "PROMPT"])
})

test("cleanupArgv builds a cursor one-shot (-p, text output, --force)", () => {
  const argv = cleanupArgv("cursor", "composer-2.5-fast", "PROMPT")
  expect(argv).toContain("-p")
  expect(argv).toContain("--force")
  expect(argv[argv.indexOf("--model") + 1]).toBe("composer-2.5-fast")
})

test("cleanupDraft returns the trimmed engine output", async () => {
  const r = await cleanupDraft(
    { draft: "Clouds High-Q model", recentMessages: [], skills: [] },
    { run: async () => ({ code: 0, out: "Claude's Haiku model\n" }) },
  )
  expect(r.text).toBe("Claude's Haiku model")
})

test("cleanupDraft passes the engine+model into the argv handed to the runner", async () => {
  let seen: string[] = []
  await cleanupDraft(
    { draft: "x", recentMessages: [], skills: [] },
    { engine: "opencode", model: "opencode/deepseek-v4-flash-free", run: async (argv) => { seen = argv; return { code: 0, out: "x" } } },
  )
  expect(seen[0]).toBe("opencode")
  expect(seen).toContain("opencode/deepseek-v4-flash-free")
})

test("cleanupDraft throws on non-zero exit (so the caller degrades to the raw draft)", async () => {
  await expect(
    cleanupDraft({ draft: "x", recentMessages: [], skills: [] }, { run: async () => ({ code: 1, out: "" }) }),
  ).rejects.toThrow()
})

test("cleanupDraft throws on empty output", async () => {
  await expect(
    cleanupDraft({ draft: "x", recentMessages: [], skills: [] }, { run: async () => ({ code: 0, out: "  " }) }),
  ).rejects.toThrow()
})

test("cleanupDraft short-circuits an empty draft without invoking the engine", async () => {
  let called = false
  const r = await cleanupDraft(
    { draft: " ", recentMessages: [], skills: [] },
    { run: async () => { called = true; return { code: 0, out: "x" } } },
  )
  expect(r.text).toBe("")
  expect(called).toBe(false)
})
