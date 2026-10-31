import { expect, test } from "bun:test"
import { readFileSync } from "fs"
import { join } from "path"

const env = readFileSync(join(import.meta.dir, "../../../prompts/environment.md"), "utf8")
const curator = readFileSync(join(import.meta.dir, "../../../prompts/knowledge-curator.md"), "utf8")

test("environment.md speaks of agents, not personal assistants or soul.md", () => {
  expect(env).toContain("Agents are long-lived, user-named sessions with their own memory")
  // spawn/kill/list are open to every session (session-tools plan); only these stay with agents.
  expect(env.replace(/\s+/g, " ")).toContain("Only agents may use `mute_session`, `set_active`, `get_active` and `create_agent`; every other tool is open to every session.")
  expect(env).not.toContain("Every session can use most of them")
  expect(env).not.toMatch(/personal.assistant|soul\.md|\bPA\b/i)
})

test("environment.md is shared with workers, so it never names personal/ or agent memory (spec §4)", () => {
  // The Agent-only lines (YOUR MEMORY, THE USER) come from the Agent header, not from this shared text.
  expect(env).not.toContain("personal/")
  expect(env).not.toContain("agent-memory")
})

test("the curator digests agent memory and never posts with a reply tool", () => {
  expect(curator).toContain("agent-memory/<id>.md")
  expect(curator).toContain("agent-memory/<id>.digest.md")
  expect(curator).not.toContain("`reply` tool")
})
