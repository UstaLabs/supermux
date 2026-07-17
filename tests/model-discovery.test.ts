import { test, expect, mock } from "bun:test"
import { discoverClaudeModels, discoverCodexModels, discoverCursorModels } from "../src/core/models/discovery"
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

test("discoverClaudeModels parses Anthropic API response", async () => {
  let seenHeaders: Record<string, string> | undefined
  const mockFetch = mock((_input: unknown, init?: { headers?: Record<string, string> }) => {
    seenHeaders = init?.headers
    return Promise.resolve({
      ok: true,
      json: () => Promise.resolve({
        data: [
          { id: "claude-opus-4-7", display_name: "Claude Opus 4.7" },
          { id: "claude-sonnet-4-6", display_name: "Claude Sonnet 4.6" },
        ],
      }),
    })
  })
  const models = await discoverClaudeModels({
    fetch: mockFetch as any,
    env: { CLAUDE_CODE_OAUTH_TOKEN: "oauth-token" },
    readCredentialFile: () => undefined,
  })
  expect(models).toEqual([
    { id: "claude-opus-4-7", displayName: "Claude Opus 4.7", agent: "claude" },
    { id: "claude-sonnet-4-6", displayName: "Claude Sonnet 4.6", agent: "claude" },
  ])
  expect(seenHeaders).toEqual({
    Authorization: "Bearer oauth-token",
    "anthropic-version": "2023-06-01",
  })
})

test("discoverClaudeModels returns empty on auth failure", async () => {
  const mockFetch = mock(() => Promise.resolve({ ok: false, status: 401 }))
  const models = await discoverClaudeModels({
    fetch: mockFetch as any,
    env: { CLAUDE_CODE_OAUTH_TOKEN: "oauth-token" },
    readCredentialFile: () => undefined,
  })
  expect(models).toEqual([])
})

test("discoverClaudeModels returns empty on network error", async () => {
  const mockFetch = mock(() => Promise.reject(new Error("network")))
  const models = await discoverClaudeModels({
    fetch: mockFetch as any,
    env: { CLAUDE_CODE_OAUTH_TOKEN: "oauth-token" },
    readCredentialFile: () => undefined,
  })
  expect(models).toEqual([])
})

test("discoverClaudeModels reads a fresh Linux Claude login from the credentials file", async () => {
  let seenHeaders: Record<string, string> | undefined
  const mockFetch = mock((_input: unknown, init?: { headers?: Record<string, string> }) => {
    seenHeaders = init?.headers
    return Promise.resolve({
      ok: true,
      json: () => Promise.resolve({ data: [{ id: "claude-opus-4-8", display_name: "Claude Opus 4.8" }] }),
    })
  })
  const models = await discoverClaudeModels({
    fetch: mockFetch as any,
    env: {},
    readCredentialFile: () => JSON.stringify({
      claudeAiOauth: { accessToken: "linux-file-oauth-token" },
    }),
  })
  expect(models.map((m) => m.id)).toEqual(["claude-opus-4-8"])
  expect(seenHeaders).toEqual({
    Authorization: "Bearer linux-file-oauth-token",
    "anthropic-version": "2023-06-01",
  })
})

test("discoverClaudeModels supports API-key-only setup", async () => {
  let seenHeaders: Record<string, string> | undefined
  const mockFetch = mock((_input: unknown, init?: { headers?: Record<string, string> }) => {
    seenHeaders = init?.headers
    return Promise.resolve({
      ok: true,
      json: () => Promise.resolve({ data: [{ id: "claude-sonnet-5", display_name: "Claude Sonnet 5" }] }),
    })
  })
  const models = await discoverClaudeModels({
    fetch: mockFetch as any,
    env: { ANTHROPIC_API_KEY: "api-key" },
    readCredentialFile: () => undefined,
  })
  expect(models.map((m) => m.id)).toEqual(["claude-sonnet-5"])
  expect(seenHeaders).toEqual({
    "x-api-key": "api-key",
    "anthropic-version": "2023-06-01",
  })
})

test("discoverCodexModels parses codex debug models JSON", async () => {
  const output = JSON.stringify({
    models: [
      { slug: "gpt-5.5", display_name: "GPT-5.5", visibility: "list" },
      { slug: "gpt-5.4", display_name: "gpt-5.4", visibility: "list" },
      { slug: "hidden-model", display_name: "Hidden", visibility: "hidden" },
    ],
  })
  const mockRun = mock(() => Promise.resolve(output))
  const models = await discoverCodexModels({ run: mockRun })
  expect(models).toEqual([
    { id: "gpt-5.5", displayName: "GPT-5.5", agent: "codex", reasoningLevels: [] },
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

test("discoverCursorModels returns empty when CLI fails", async () => {
  const mockRun = mock(() => Promise.reject(new Error("not found")))
  const models = await discoverCursorModels({ run: mockRun })
  expect(models).toEqual([])
})
