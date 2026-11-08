import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { EventEmitter } from "events"
import { followAdapterModels, LearnedModels } from "./learned"
import { AgentKind } from "../../shared/agents"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

function tmp(): string {
  const d = mkdtempSync(join(tmpdir(), "learned-models-"))
  dirs.push(d)
  return d
}

// #102: Cursor's model list is learned from a session start (its ACP options) and must outlive the broker process.
test("a learned model list is kept across restarts, and learning the same list again changes nothing", () => {
  const dir = join(tmp(), "learned-models")
  const learned = new LearnedModels(dir)
  expect(learned.get(AgentKind.Cursor)).toBeUndefined()
  const models = [{ id: "auto", displayName: "Auto", agent: AgentKind.Cursor }, { id: "gpt-5.6-sol", displayName: "gpt-5.6-sol", agent: AgentKind.Cursor }]

  expect(learned.learn(AgentKind.Cursor, models)).toBe(true)
  expect(learned.learn(AgentKind.Cursor, models)).toBe(false)

  expect(new LearnedModels(dir).get(AgentKind.Cursor)).toEqual(models)
  expect(new LearnedModels(dir).get(AgentKind.Codex)).toBeUndefined()
})

test("an empty list is never learned (a start that offered no models keeps the last good list)", () => {
  const learned = new LearnedModels(join(tmp(), "learned-models"))
  learned.learn(AgentKind.Cursor, [{ id: "auto", displayName: "Auto", agent: AgentKind.Cursor }])
  expect(learned.learn(AgentKind.Cursor, [])).toBe(false)
  expect(learned.get(AgentKind.Cursor)).toEqual([{ id: "auto", displayName: "Auto", agent: AgentKind.Cursor }])
})

// A session opens (and its agent reports its models) before the resume arms wire its adapter's events, so the
// adapter's current list is read at wiring as well as on every later models-update (as commands are).
test("an adapter's model list is learned though it arrived before the wiring, and again whenever it changes", () => {
  const learned = new LearnedModels(join(tmp(), "learned-models"))
  const adapter = Object.assign(new EventEmitter(), { offeredModels: [{ value: "default[]", name: "Auto" }] })
  const seen: unknown[] = []
  followAdapterModels({ agent: AgentKind.Cursor, adapter, learned, onLearned: (_agent, models) => { seen.push(models) } })
  expect(seen).toEqual([[{ id: "auto", displayName: "Auto", agent: AgentKind.Cursor }]])

  adapter.offeredModels = [{ value: "default[]", name: "Auto" }, { value: "composer-2.5[fast=true]", name: "composer-2.5" }]
  adapter.emit("models-update")
  adapter.emit("models-update")
  expect(seen).toEqual([
    [{ id: "auto", displayName: "Auto", agent: AgentKind.Cursor }],
    [{ id: "auto", displayName: "Auto", agent: AgentKind.Cursor }, { id: "composer-2.5", displayName: "composer-2.5", agent: AgentKind.Cursor }],
  ])
  expect(learned.get(AgentKind.Cursor)?.length).toBe(2)
})

test("only Cursor's adapters are followed", () => {
  const learned = new LearnedModels(join(tmp(), "learned-models"))
  const adapter = Object.assign(new EventEmitter(), { offeredModels: [{ value: "provider/x", name: "x" }] })
  const seen: unknown[] = []
  followAdapterModels({ agent: AgentKind.OpenCode, adapter, learned, onLearned: () => { seen.push(1) } })
  adapter.emit("models-update")
  expect(seen).toEqual([])
  expect(learned.get(AgentKind.OpenCode)).toBeUndefined()
})
