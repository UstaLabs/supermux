import { test, expect, mock } from "bun:test"
import { discoverClaudeModels, discoverCodexModels, discoverCursorModels } from "../src/core/models/discovery"

test("discoverClaudeModels parses Anthropic API response", async () => {
  const mockFetch = mock(() => Promise.resolve({
    ok: true,
    json: () => Promise.resolve({
      data: [
        { id: "claude-opus-4-7", display_name: "Claude Opus 4.7" },
        { id: "claude-sonnet-4-6", display_name: "Claude Sonnet 4.6" },
      ],
    }),
  }))
  const models = await discoverClaudeModels({ fetch: mockFetch as any })
  expect(models).toEqual([
    { id: "claude-opus-4-7", displayName: "Claude Opus 4.7", agent: "claude" },
    { id: "claude-sonnet-4-6", displayName: "Claude Sonnet 4.6", agent: "claude" },
  ])
})

test("discoverClaudeModels returns empty on auth failure", async () => {
  const mockFetch = mock(() => Promise.resolve({ ok: false, status: 401 }))
  const models = await discoverClaudeModels({ fetch: mockFetch as any })
  expect(models).toEqual([])
})

test("discoverClaudeModels returns empty on network error", async () => {
  const mockFetch = mock(() => Promise.reject(new Error("network")))
  const models = await discoverClaudeModels({ fetch: mockFetch as any })
  expect(models).toEqual([])
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
