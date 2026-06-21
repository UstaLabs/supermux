import { expect, test } from "bun:test"
import { buildCleanupPrompt } from "../../src/core/agent-api/prompt"

test("buildCleanupPrompt includes the draft, context, a skill name, and the output instruction", () => {
  const prompt = buildCleanupPrompt({
    draft: "helo wrld",
    recentMessages: [{ role: "user", text: "deploy the broker" }],
    skills: ["preview-broker", "watch-video"],
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
  const prompt = buildCleanupPrompt({ draft: "hi", recentMessages: [], skills: [] })
  expect(prompt).toContain(JSON.stringify("hi"))
  expect(prompt).not.toContain("Known commands/skills")
  expect(prompt).not.toContain("Conversation so far")
})
