import { expect, test } from "bun:test"
import { AGENT_KINDS } from "../../shared/agents"
import { modesFor } from "../agents/permission-modes"
import type { ModelInfo } from "./discovery"
import { listModels, type ListModelsInput } from "./list-models"

const you = { agent: "claude" as const, model: "opus", effort: "high", access: "bypass" }
const input = (over: Partial<ListModelsInput> = {}): ListModelsInput => ({
  you,
  statuses: AGENT_KINDS.map((kind) => ({ kind, installed: true, authed: true, usableWithoutAuth: false })),
  lookupModels: () => [],
  modelGates: () => [],
  ...over,
})

test("every agent kind is listed, ready, with its permission modes and their levels", () => {
  const r = listModels(input())
  expect(r.you).toEqual(you)
  expect(r.agents.map((a) => a.agent)).toEqual([...AGENT_KINDS])
  for (const a of r.agents) {
    expect(a.ready).toBe(true)
    expect(a.why_not).toBeUndefined()
    expect(a.access).toEqual(modesFor(a.agent).map((m) => ({ id: m.id, label: m.label, level: m.level })))
  }
})

test("agent limits the list to that kind", () => {
  expect(listModels(input({ agent: "codex" })).agents.map((a) => a.agent)).toEqual(["codex"])
})

test("an agent that is not installed or not signed in is not ready, and says why", () => {
  const r = listModels(input({
    statuses: [
      { kind: "claude", installed: true, authed: true, usableWithoutAuth: false },
      { kind: "codex", installed: false, authed: false, usableWithoutAuth: false },
      { kind: "grok", installed: true, authed: false, usableWithoutAuth: false },
    ],
  }))
  const byKind = Object.fromEntries(r.agents.map((a) => [a.agent, a]))
  expect(byKind.claude!.ready).toBe(true)
  expect(byKind.codex).toMatchObject({ ready: false, why_not: "not installed" })
  expect(byKind.grok).toMatchObject({ ready: false, why_not: "not signed in" })
  // A kind detection did not report on counts as not installed.
  expect(byKind.cursor).toMatchObject({ ready: false, why_not: "not installed" })
})

test("an agent that works without sign-in (OpenCode's free tier) is ready once installed", () => {
  const free = (installed: boolean) => listModels(input({
    agent: "opencode",
    statuses: [{ kind: "opencode", installed, authed: false, usableWithoutAuth: true }],
  })).agents[0]!
  expect(free(true).ready).toBe(true)
  expect(free(true).why_not).toBeUndefined()
  // Working without sign-in does not make up for a missing install.
  expect(free(false)).toMatchObject({ ready: false, why_not: "not installed" })
})

test("models carry their label and efforts; a gated model is not ready, and says until when", () => {
  const codex: ModelInfo[] = [
    { id: "gpt-6", displayName: "GPT-6", agent: "codex", reasoningLevels: [{ id: "low" }, { id: "high" }] },
    { id: "gpt-6-astra", displayName: "GPT-6 Astra", agent: "codex", reasoningLevels: [{ id: "medium" }] },
    { id: "gpt-6-mini", displayName: "GPT-6 mini", agent: "codex" },
  ]
  const r = listModels(input({
    agent: "codex",
    lookupModels: (a) => (a === "codex" ? codex : []),
    modelGates: (a) => (a === "codex"
      ? [
          { id: "gpt-6", available: true, availableAtIso: null },
          { id: "gpt-6-astra", available: false, availableAtIso: "2026-11-01T00:00:00.000Z" },
          { id: "gpt-6-mini", available: false, availableAtIso: null },
        ]
      : []),
  }))
  expect(r.agents[0]!.models).toEqual([
    { id: "gpt-6", label: "GPT-6", efforts: ["low", "high"], ready: true },
    { id: "gpt-6-astra", label: "GPT-6 Astra", efforts: ["medium"], ready: false, why_not: "unavailable until 2026-11-01T00:00:00.000Z" },
    { id: "gpt-6-mini", label: "GPT-6 mini", efforts: [], ready: false, why_not: "unavailable" },
  ])
})

test("Claude models get the --effort scale the app offers", () => {
  const r = listModels(input({ agent: "claude", lookupModels: () => [{ id: "opus", displayName: "Opus", agent: "claude" }] }))
  expect(r.agents[0]!.models).toEqual([{ id: "opus", label: "Opus", efforts: ["low", "medium", "high", "xhigh", "max"], ready: true }])
})
