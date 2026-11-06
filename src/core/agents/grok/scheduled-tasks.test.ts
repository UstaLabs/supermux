import { afterEach, beforeEach, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { grokHasScheduledTasks } from "./scheduled-tasks"

let home: string
beforeEach(() => { home = mkdtempSync(join(tmpdir(), "grok-sched-")) })
afterEach(() => { rmSync(home, { recursive: true, force: true }) })

/** Grok's own layout: <home>/.grok/sessions/<encoded workdir>/<session id>/resources_state.json. */
function writeState(sessionId: string, state: unknown, workdir = "/home/u/project"): void {
  const dir = join(home, ".grok", "sessions", encodeURIComponent(workdir), sessionId)
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, "resources_state.json"), typeof state === "string" ? state : JSON.stringify(state))
}

test("a session whose scheduler holds a task has scheduled tasks", () => {
  writeState("sid-1", { state: { "grok_build.Scheduler": { tasks: [{ id: "t1", prompt: "summarize new issues" }] } } })
  expect(grokHasScheduledTasks(home, "sid-1")).toBe(true)
})

test("an empty scheduler has none", () => {
  writeState("sid-1", { state: { "grok_build.Scheduler": { tasks: [] } } })
  expect(grokHasScheduledTasks(home, "sid-1")).toBe(false)
})

test("only the session's own state counts", () => {
  writeState("other", { state: { "grok_build.Scheduler": { tasks: [{ id: "t1" }] } } })
  writeState("sid-1", { state: { "grok_build.ReportedTaskCompletions": { reported: [] } } })
  expect(grokHasScheduledTasks(home, "sid-1")).toBe(false)
})

test("no state, no sessions folder, or a state that isn't JSON has none", () => {
  expect(grokHasScheduledTasks(home, "sid-1")).toBe(false)
  writeState("sid-1", "{ not json")
  expect(grokHasScheduledTasks(home, "sid-1")).toBe(false)
})
