import { expect, test } from "bun:test"
import { buildCleanupPrompt } from "../../src/core/agent-api/prompt"

// AUTHORITATIVE tests for the shared buildCleanupPrompt / CleanupInput. The prompt
// builder lives in src/core/agent-api/prompt.ts; voice-cleanup.ts only re-exports it.

test("buildCleanupPrompt includes the draft, context, a skill name, and the output instruction", () => {
  const prompt = buildCleanupPrompt({
    draft: "helo wrld",
    recentMessages: [{ role: "user", text: "deploy the broker" }],
    skills: ["preview-broker", "watch-video"],
    glossary: [],
  })
  // draft (JSON-encoded)
  expect(prompt).toContain(JSON.stringify("helo wrld"))
  // a context line from recent messages
  expect(prompt).toContain("user: deploy the broker")
  // a skill name
  expect(prompt).toContain("preview-broker")
  // the load-bearing output instruction
  expect(prompt).toContain("Output ONLY the corrected text")
})

test("buildCleanupPrompt omits skills/context sections when empty", () => {
  const prompt = buildCleanupPrompt({ draft: "hi", recentMessages: [], skills: [], glossary: [] })
  expect(prompt).toContain(JSON.stringify("hi"))
  expect(prompt).not.toContain("Known commands/skills")
  expect(prompt).not.toContain("Conversation so far")
  expect(prompt).not.toContain("Known terms")
})

test("buildCleanupPrompt includes the glossary line and a sample term when non-empty", () => {
  const prompt = buildCleanupPrompt({
    draft: "super max",
    recentMessages: [],
    skills: [],
    glossary: ["Supermux", "Codex", "Whisper"],
  })
  // the load-bearing glossary line + a sample term
  expect(prompt).toContain("Known terms")
  expect(prompt).toContain("Supermux")
  expect(prompt).toContain("Codex, Whisper")
})
