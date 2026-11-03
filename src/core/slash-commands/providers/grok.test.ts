import { test, expect } from "bun:test"
import { GrokCommandProvider, mapGrokCommands } from "./grok"
import type { AgentCommandEntry } from "../types"

// Grok's ACP push (1.0.46 shapes; built-ins carry no _meta, skills carry _meta.scope + _meta.path → SKILL.md,
// workflows carry _meta.workflowSource).
const LIVE_PUSH: AgentCommandEntry[] = [
  { name: "compact", description: "Compress conversation history to save context window" },
  { name: "always-approve", description: "Toggle always-approve mode (skip all permission prompts)" },
  { name: "deep-research", description: "Research", _meta: { workflowSource: "builtin", workflowPath: "/home/x/.grok/bundled/workflows/deep-research.rhai" } },
  { name: "check-work", description: "Verify changes with a subagent", _meta: { scope: "user", path: "/home/x/.grok/skills/check-work/SKILL.md" } },
  { name: "probe-skill", description: "A probe skill.", _meta: { scope: "user", path: "/plugins/sp/skills/probe-skill/SKILL.md" } },
  { name: "build-with-ai", _meta: { scope: "bundled", path: "/opt/grok/bundled/build-with-ai/SKILL.md" } },
  { name: "repo-skill", _meta: { scope: "repo", path: "/w/proj/.grok/skills/repo-skill/SKILL.md" } },
]

test("mapGrokCommands keeps skill-backed entries and drops TUI built-ins and workflows", () => {
  const { commands } = mapGrokCommands(LIVE_PUSH)
  expect(commands.map((c) => c.name)).toEqual(["check-work", "probe-skill", "build-with-ai", "repo-skill"])
  const probe = commands.find((c) => c.name === "probe-skill")!
  expect(probe).toMatchObject({ family: "agent", sigil: "/", insertText: "/probe-skill ", description: "A probe skill." })
})

test("mapGrokCommands: skills outside the user and bundled scopes are the folder's own", () => {
  expect(mapGrokCommands(LIVE_PUSH).projectIds).toEqual(["agent:repo-skill"])
})

test("GrokCommandProvider reads the session's reported list; nothing before it reports", async () => {
  const p = new GrokCommandProvider()
  expect((await p.list({ sessionName: "s", workdir: "/w", agentContext: { commands: LIVE_PUSH } }))?.commands).toHaveLength(4)
  expect(await p.list({ sessionName: "s", workdir: "/w", agentContext: { commands: undefined } })).toBeUndefined()
})

test("GrokCommandProvider: the initialize-time list (built-ins only, no _meta at all) is not the session's report yet", async () => {
  // grok 1.0.46 lists 7 built-ins in initialize _meta.availableCommands; skills come with the session's push.
  // Stored as a report it would map to nothing and overwrite the folder's stored list.
  const initializeList: AgentCommandEntry[] = [{ name: "compact", description: "c" }, { name: "goal", description: "g" }]
  expect(await new GrokCommandProvider().list({ sessionName: "s", workdir: "/w", agentContext: { commands: initializeList } })).toBeUndefined()
})
