import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { judge, type Observation } from "../scripts/subagent-truth/judge.js"
import { AGENTS, MATRIX, TABLE_END, TABLE_START, liveCell, renderMatrixTable, terminalStateOf } from "../scripts/subagent-truth/matrix.js"
import { defaultVersionsFile, readVersionState, recordPass, skipReason, writeVersionState } from "../scripts/subagent-truth/versions.js"
import { REASON } from "../src/subagent-actions.js"

const dirs: string[] = []
afterEach(() => { for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true }) })

test("API.md's per-agent table is exactly the one rendered from the truth matrix (one source of truth)", () => {
  const api = readFileSync(new URL("../API.md", import.meta.url), "utf8")
  const start = api.indexOf(TABLE_START), end = api.indexOf(TABLE_END)
  expect(start).toBeGreaterThan(-1)
  expect(end).toBeGreaterThan(start)
  expect(api.slice(start, end + TABLE_END.length)).toBe(renderMatrixTable())
})

test("every live agent has a checked row; refusals carry the library's own reason strings", () => {
  const reasons = new Set<string>(Object.values(REASON))
  for (const agent of AGENTS) {
    expect(liveCell(agent, "running")).toBeDefined()
    expect(liveCell(agent, "finished")).toBeDefined()
    for (const row of MATRIX.filter(r => r.agent === agent)) for (const cell of Object.values(row.cells)) {
      for (const action of cell.live ? [cell.live.message, cell.live.stop] : []) if (!action.ok) expect(reasons.has(action.reason)).toBe(true)
    }
  }
  expect(terminalStateOf({ status: "completed", endedBy: "self" })).toBe("finished")
  expect(terminalStateOf({ status: "cancelled", endedBy: "client" })).toBe("stoppedByClient")
  expect(terminalStateOf({ status: "cancelled", endedBy: "parent" })).toBe("endedByParent")
  expect(terminalStateOf({ status: "failed" })).toBeUndefined()
})

const snap = (status: string, extra: Record<string, unknown> = {}) => ({ status, actionsSource: "derived", ...extra })

/** A complete, correct Cursor run (the smallest row). */
function cursorRun(): Observation[] {
  return [
    { kind: "status", state: "running", subagentId: "a", snapshot: snap("running") },
    { kind: "message", state: "running", subagentId: "a", before: snap("running", { canMessage: true }), outcome: { ok: true, via: "relay", delivery: "delivered", answered: true } },
    { kind: "status", state: "running", subagentId: "b", snapshot: snap("running") },
    { kind: "stop", state: "running", subagentId: "b", before: snap("running", { canStop: false, cannotStopReason: REASON.cursorNoStop }), outcome: { ok: false, code: "subagent_unavailable", message: REASON.cursorNoStop } },
    { kind: "status", state: "finished", subagentId: "a", snapshot: snap("completed", { endedBy: "self" }) },
    { kind: "stop", state: "finished", subagentId: "a", before: snap("completed", { endedBy: "self", canStop: false, cannotStopReason: REASON.cursorNoStop }), outcome: { ok: false, code: "subagent_unavailable", message: REASON.cursorNoStop } },
    { kind: "message", state: "finished", subagentId: "a", before: snap("completed", { endedBy: "self", canMessage: true }), outcome: { ok: true, via: "relay", delivery: "delivered", reran: true, answered: true } },
    { kind: "status", state: "afterResume", resumeOf: "finished", subagentId: "a", snapshot: snap("completed", { endedBy: "self" }) },
    { kind: "stop", state: "afterResume", resumeOf: "finished", subagentId: "a", before: snap("completed", { endedBy: "self", canStop: false, cannotStopReason: REASON.cursorNoStop }), outcome: { ok: false, code: "subagent_unavailable", message: REASON.cursorNoStop } },
    { kind: "message", state: "afterResume", resumeOf: "finished", subagentId: "a", before: snap("completed", { endedBy: "self", canMessage: true }), outcome: { ok: true, via: "relay", delivery: "delivered", reran: true, answered: false } },
  ]
}

test("judge passes a run that matches every cell (a missed keyword is only a note)", () => {
  const checks = judge("cursor", cursorRun())
  expect(checks.filter(c => !c.pass)).toEqual([])
  expect(checks.map(c => `${c.state}/${c.check}`)).toContain("afterResume/message")
  expect(checks.find(c => c.state === "afterResume" && c.check === "message")?.note).toContain("soft")
})

test("judge fails a wrong refusal reason, an offered action that does not work, a wrong endedBy, and an unreached state", () => {
  const run = cursorRun()
  run[3] = { ...run[3], outcome: { ok: false, code: "subagent_unavailable", message: "something else" } } as Observation
  run[6] = { ...run[6], outcome: { ok: true, via: "relay", delivery: "unconfirmed", deliveryReason: "The main agent did not forward the message", reran: false } } as Observation
  run[4] = { kind: "status", state: "finished", subagentId: "a", snapshot: snap("completed", { endedBy: "parent" }) }
  const failed = judge("cursor", run).filter(c => !c.pass)
  expect(failed.map(c => `${c.state}/${c.check}`).sort()).toEqual(["finished/message", "finished/status", "running/stop"])
  expect(failed.find(c => c.check === "message")!.actual).toContain("delivery unconfirmed")

  const gap = judge("codex", [{ kind: "unreached", state: "running", reason: "the parent did not spawn the first subagent" }])
  expect(gap.every(c => !c.pass)).toBe(true)
  expect(gap.find(c => c.state === "running")).toMatchObject({ check: "reached", actual: "the parent did not spawn the first subagent" })
  // Cells the agent cannot reach (Cursor: stopped by client / ended by parent) are not demanded.
  expect(judge("cursor", cursorRun()).some(c => c.state === "stoppedByClient" || c.state === "endedByParent")).toBe(false)
})

test("--if-changed state: skip only the agents whose CLI version already passed", () => {
  const dir = mkdtempSync(join(tmpdir(), "truth-versions-")); dirs.push(dir)
  const file = join(dir, "nested", "truth-versions.json")
  expect(readVersionState(file)).toEqual({ version: 1, agents: {} })
  writeVersionState(file, recordPass(readVersionState(file), "grok", "grok 1.0.46", new Date("2026-10-04T00:00:00Z")))
  const state = readVersionState(file)
  expect(skipReason(state, "grok", "grok 1.0.46")).toContain("unchanged")
  expect(skipReason(state, "grok", "grok 1.0.47")).toBeUndefined()
  expect(skipReason(state, "codex", "codex-cli 0.159.2")).toBeUndefined()
  expect(skipReason(state, "grok", undefined)).toBeUndefined()
  expect(defaultVersionsFile({ XDG_CACHE_HOME: "/c" })).toBe("/c/supermux-core/truth-versions.json")
})
