import { test, expect, mock } from "bun:test"
import { cursorModelsFromAcp, discoverClaudeModels, discoverCodexModels, discoverCursorModels, discoverWithAccounts } from "../src/core/models/discovery"
import { CLAUDE_MODEL_CATALOG_URL } from "../src/core/models/claude-catalog"
import { discoverGrokModels, mapGrokModels } from "../src/core/agents/grok/model-discovery"

// Verbatim `_meta.modelState.availableModels` from a live ACP `initialize`
// (grok 0.2.99). Reasoning efforts are per-model and gated on supportsReasoningEffort.
const GROK_MODEL_STATE = [
  {
    modelId: "grok-4.5",
    name: "Grok 4.5",
    description: "SpaceXAI's new frontier model",
    _meta: {
      totalContextTokens: 500000,
      agentType: "grok-build-plan",
      supportsReasoningEffort: true,
      reasoningEffort: "high",
      reasoningEfforts: [
        { id: "high", value: "high", label: "High Effort", description: "Highest implementation quality with extensive reasoning", default: true },
        { id: "medium", value: "medium", label: "Medium Effort", description: "Balanced effort with standard implementation and testing", default: false },
        { id: "low", value: "low", label: "Low Effort", description: "Quick, fast implementations", default: false },
      ],
    },
  },
]

/** Fake runner that answers `initialize` with the given modelState. */
function fakeGrokRunner(modelState: unknown) {
  return (opts: any) => {
    opts.client.setWrite((line: string) => {
      const msg = JSON.parse(line)
      if (msg.method === "initialize") {
        queueMicrotask(() => opts.client.feed(JSON.stringify({
          jsonrpc: "2.0", id: msg.id, result: { protocolVersion: 1, _meta: { modelState } },
        }) + "\n"))
      }
    })
    return { kill: () => {} }
  }
}

test("mapGrokModels surfaces the display name and per-model reasoning efforts", () => {
  expect(mapGrokModels(GROK_MODEL_STATE)).toEqual([
    {
      id: "grok-4.5",
      displayName: "Grok 4.5",
      agent: "grok",
      reasoningLevels: [
        { id: "high", description: "Highest implementation quality with extensive reasoning" },
        { id: "medium", description: "Balanced effort with standard implementation and testing" },
        { id: "low", description: "Quick, fast implementations" },
      ],
    },
  ])
})

test("mapGrokModels omits reasoning levels when the model doesn't support effort", () => {
  const models = mapGrokModels([
    { modelId: "grok-mini", name: "Grok Mini", _meta: { supportsReasoningEffort: false, reasoningEfforts: [{ id: "high" }] } },
  ])
  expect(models).toEqual([{ id: "grok-mini", displayName: "Grok Mini", agent: "grok" }])
})

test("mapGrokModels falls back to the model id when no display name is given", () => {
  expect(mapGrokModels([{ modelId: "grok-x" }])).toEqual([{ id: "grok-x", displayName: "grok-x", agent: "grok" }])
})

test("discoverGrokModels reads modelState from the ACP handshake", async () => {
  const models = await discoverGrokModels({ runner: fakeGrokRunner({ availableModels: GROK_MODEL_STATE }) as any })
  expect(models.map((m) => m.id)).toEqual(["grok-4.5"])
  expect(models[0]?.reasoningLevels?.map((r) => r.id)).toEqual(["high", "medium", "low"])
})

test("discoverGrokModels rejects Grok's unauthenticated fallback catalog", async () => {
  const models = await discoverGrokModels({
    runner: fakeGrokRunner({ availableModels: [{ modelId: "grok-build", name: "Grok Build" }] }) as any,
  })
  expect(models).toEqual([])
})

test("discoverGrokModels kills the probe child once the handshake completes", async () => {
  let killed = false
  const runner = (opts: any) => {
    const inner = fakeGrokRunner({ availableModels: GROK_MODEL_STATE })(opts)
    return { kill: () => { killed = true; inner.kill() } }
  }
  await discoverGrokModels({ runner: runner as any })
  expect(killed).toBe(true)
})

test("discoverGrokModels returns empty (and does not hang) when the agent never answers", async () => {
  const runner = (opts: any) => { opts.client.setWrite(() => {}); return { kill: () => {} } }
  const models = await discoverGrokModels({ runner: runner as any, timeoutMs: 50 })
  expect(models).toEqual([])
})

test("discoverGrokModels returns empty when the CLI is missing", async () => {
  const runner = () => { throw new Error("spawn grok ENOENT") }
  expect(await discoverGrokModels({ runner: runner as any })).toEqual([])
})

const CLAUDE_CATALOG = {
  surfaces: {
    cc: {
      model_selector_config: [{
        models: [
          {
            id: "claude-opus-5-5",
            name: "Opus 5.5",
            min_claude_code_version: "2.1.280",
            thinking: { type: "effort", effort_options: ["low", "medium", "high", "xhigh", "max"].map((id) => ({ id })) },
            fast_mode: { type: "toggle" },
          },
          {
            id: "claude-opus-4-6",
            name: "Opus 4.6",
            thinking: { type: "effort", effort_options: ["low", "medium", "high", "max"].map((id) => ({ id })) },
          },
          { id: "claude-haiku-4-5-20251001", name: "Haiku 4.5", thinking: { type: "none" } },
          { id: "claude-future", name: "Future", min_claude_code_version: "9.0.0", thinking: { type: "none" } },
        ],
      }],
    },
  },
}

test("discoverClaudeModels reads Claude Code's catalog, with each model's own efforts", async () => {
  let seenUrl: string | undefined
  const mockFetch = mock((input: unknown) => {
    seenUrl = String(input)
    return Promise.resolve({ ok: true, json: () => Promise.resolve(CLAUDE_CATALOG) })
  })
  const asked: string[] = []
  const models = await discoverClaudeModels({
    fetch: mockFetch as any,
    claudeVersion: "2.1.319",
    env: { CLAUDE_CODE_OAUTH_TOKEN: "oauth-token" },
    readCredentialFile: () => JSON.stringify({ claudeAiOauth: { accessToken: "file-token" } }),
    readStoredLogin: async () => { asked.push("store"); return "stored" },
  })
  expect(seenUrl).toBe(CLAUDE_MODEL_CATALOG_URL)
  expect(asked).toEqual([])
  expect(models).toEqual([
    {
      id: "claude-opus-5-5",
      displayName: "Claude Opus 5.5",
      agent: "claude",
      reasoningLevels: ["low", "medium", "high", "xhigh", "max"].map((id) => ({ id })),
      supportsFast: true,
    },
    {
      id: "claude-opus-4-6",
      displayName: "Claude Opus 4.6",
      agent: "claude",
      reasoningLevels: ["low", "medium", "high", "max"].map((id) => ({ id })),
    },
    { id: "claude-haiku-4-5-20251001", displayName: "Claude Haiku 4.5", agent: "claude" },
  ])
})

test("discoverClaudeModels returns empty when the catalog cannot be fetched", async () => {
  const denied = mock(() => Promise.resolve({ ok: false, status: 503 }))
  expect(await discoverClaudeModels({ fetch: denied as any, claudeVersion: null })).toEqual([])
  const failing = mock(() => Promise.reject(new Error("network")))
  expect(await discoverClaudeModels({ fetch: failing as any, claudeVersion: null })).toEqual([])
})

test("discoverCodexModels parses codex debug models JSON", async () => {
  const output = JSON.stringify({
    models: [
      { slug: "gpt-5.5", display_name: "GPT-5.5", visibility: "list", service_tiers: [{ id: "priority", name: "Fast" }] },
      { slug: "gpt-5.4", display_name: "gpt-5.4", visibility: "list", service_tiers: [] },
      { slug: "hidden-model", display_name: "Hidden", visibility: "hidden" },
    ],
  })
  const mockRun = mock(() => Promise.resolve(output))
  const models = await discoverCodexModels({ run: mockRun })
  expect(models).toEqual([
    { id: "gpt-5.5", displayName: "GPT-5.5", agent: "codex", reasoningLevels: [], supportsFast: true },
    { id: "gpt-5.4", displayName: "gpt-5.4", agent: "codex", reasoningLevels: [] },
  ])
})

test("discoverCodexModels returns empty when CLI fails", async () => {
  const mockRun = mock(() => Promise.reject(new Error("not found")))
  const models = await discoverCodexModels({ run: mockRun })
  expect(models).toEqual([])
})

test("discoverCursorModels parses cursor-agent --list-models output", async () => {
  const output = [
    "Available models",
    "",
    "auto - Auto",
    "gpt-5.3-codex - Codex 5.3",
    "sonnet-4 - Sonnet 4",
  ].join("\n")
  const mockRun = mock(() => Promise.resolve(output))
  const models = await discoverCursorModels({ run: mockRun })
  expect(models).toEqual([
    { id: "auto", displayName: "Auto", agent: "cursor" },
    { id: "gpt-5.3-codex", displayName: "Codex 5.3", agent: "cursor" },
    { id: "sonnet-4", displayName: "Sonnet 4", agent: "cursor" },
  ])
})

test("discoverCursorModels prefers the list Cursor's ACP gave at a session start; the CLI is only the first-run fallback (#102)", async () => {
  const mockRun = mock(() => Promise.resolve("auto - Auto\ngpt-5.6-sol-high - GPT-5.6 Sol High"))
  const learned = [{ id: "auto", displayName: "Auto", agent: "cursor" as const }, { id: "gpt-5.6-sol", displayName: "gpt-5.6-sol", agent: "cursor" as const }]
  expect(await discoverCursorModels({ run: mockRun, learned: () => learned })).toEqual(learned)
  expect(mockRun).not.toHaveBeenCalled()
  expect(await discoverCursorModels({ run: mockRun, learned: () => undefined })).toEqual([
    { id: "auto", displayName: "Auto", agent: "cursor" },
    { id: "gpt-5.6-sol-high", displayName: "GPT-5.6 Sol High", agent: "cursor" },
  ])
})

test("cursorModelsFromAcp: Cursor's own names, and its Auto keeps the id every client stores (#102)", () => {
  expect(cursorModelsFromAcp([
    { value: "default[]", name: "Auto" },
    { value: "gpt-5.6-sol[context=272k,reasoning=medium,fast=false]", name: "gpt-5.6-sol" },
  ])).toEqual([
    { id: "auto", displayName: "Auto", agent: "cursor" },
    { id: "gpt-5.6-sol", displayName: "gpt-5.6-sol", agent: "cursor" },
  ])
})

test("discoverCursorModels returns empty when CLI fails", async () => {
  const mockRun = mock(() => Promise.reject(new Error("not found")))
  const models = await discoverCursorModels({ run: mockRun })
  expect(models).toEqual([])
})

test("discoverClaudeModels hides catalog rows the installed Claude Code is too old to run", async () => {
  const mockFetch = mock(() => Promise.resolve({ ok: true, json: () => Promise.resolve(CLAUDE_CATALOG) }))
  const models = await discoverClaudeModels({
    fetch: mockFetch as any,
    claudeVersion: "1.0.0",
    launch: { env: { CLAUDE_CODE_OAUTH_TOKEN: "account-token" } },
  })
  expect(models.map((m) => m.id)).toEqual(["claude-opus-4-6", "claude-haiku-4-5-20251001"])
})

test("discoverWithAccounts keeps the system login's models when they work", async () => {
  let materialized = 0
  const models = await discoverWithAccounts(
    async (launch) => launch ? [] : [{ id: "sys", displayName: "Sys", agent: "claude" }],
    async () => [async () => { materialized++; return { env: {} } }],
  )
  expect(models.map((m) => m.id)).toEqual(["sys"])
  expect(materialized).toBe(0)
})

test("discoverWithAccounts falls back to the first account that returns models", async () => {
  const tried: string[] = []
  const models = await discoverWithAccounts(
    async (launch) => {
      const who = launch?.env.WHO ?? "system"
      tried.push(who)
      return who === "b" ? [{ id: "m", displayName: "M", agent: "claude" }] : []
    },
    async () => [
      async () => { throw new Error("account_expired") },
      async () => ({ env: { WHO: "a" } }),
      async () => ({ env: { WHO: "b" } }),
      async () => ({ env: { WHO: "c" } }),
    ],
  )
  expect(models.map((m) => m.id)).toEqual(["m"])
  expect(tried).toEqual(["system", "a", "b"])
})
