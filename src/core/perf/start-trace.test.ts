import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { PerfLog } from "./perf-log"
import { START_TRACE_TTL_MS, StartTraces } from "./start-trace"
import { perfMark, setPerfMarkSink } from "../../../packages/supermux-core/src/perf-marks.js"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
  setPerfMarkSink(undefined)
})
function log(on: boolean): PerfLog {
  const dir = mkdtempSync(join(tmpdir(), "mux-start-"))
  dirs.push(dir)
  return new PerfLog({ dir, envEnabled: on, flushMs: 0 })
}

test("while tracing is off a start gets no trace and nothing is recorded", () => {
  const perf = log(false)
  const traces = new StartTraces()
  traces.setLog(perf)
  const trace = traces.begin("SAPP.1")
  expect(trace).toBeUndefined()
  traces.mark(trace, "request", { via: "launcher" })
  traces.bind("s1", trace)
  traces.markSession("s1", "reply.first")
  expect(traces.traceOf("s1")).toBeUndefined()
  expect(perf.events()).toEqual([])
})

test("the app's own trace id is kept; a start without one gets a fresh S… id", () => {
  const traces = new StartTraces()
  traces.setLog(log(true))
  expect(traces.begin("SK7Q2.3")).toBe("SK7Q2.3")
  const minted = traces.begin()
  expect(minted).toMatch(/^S[A-Z2-9]{7}$/)
  // Not a start trace (a launch's, a chat's, or junk): a fresh one instead.
  expect(traces.begin("LABCDEF")).toMatch(/^S[A-Z2-9]{7}$/)
  expect(traces.begin("S bad/id")).toMatch(/^S[A-Z2-9]{7}$/)
})

test("marks before the id carry the trace; after bind, marks by session id find it", () => {
  const perf = log(true)
  const traces = new StartTraces(() => 1_000)
  traces.setLog(perf)
  const trace = traces.begin("SAPP.1")
  traces.mark(trace, "worktree.add", { ms: 812.5, branch: "mux/x" })
  traces.bind("s1", trace)
  traces.markSession("s1", "keeper.spawned", { ms: 140 })
  traces.markSession("s2", "keeper.spawned", { ms: 1 }) // not a start being traced
  expect(perf.events().map((e) => [e.event, e.trace, e.session, e.ms])).toEqual([
    ["start.worktree.add", "SAPP.1", undefined, 812.5],
    ["start.keeper.spawned", "SAPP.1", "s1", 140],
  ])
})

test("once keeps only the first mark of a name, and close ends the start", () => {
  const perf = log(true)
  const traces = new StartTraces()
  traces.setLog(perf)
  traces.bind("s1", traces.begin())
  traces.markSession("s1", "activity.first", { kind: "thinking" }, true)
  traces.markSession("s1", "activity.first", { kind: "tool" }, true)
  traces.close("s1")
  traces.markSession("s1", "reply.first", {}, true)
  expect(perf.events().map((e) => [e.event, e.kind])).toEqual([["start.activity.first", "thinking"]])
})

test("a start stops taking marks after the TTL, so later turns are never timed as the start", () => {
  const perf = log(true)
  let now = 0
  const traces = new StartTraces(() => now)
  traces.setLog(perf)
  traces.bind("s1", traces.begin())
  now = START_TRACE_TTL_MS + 1
  traces.markSession("s1", "reply.first")
  expect(traces.traceOf("s1")).toBeUndefined()
  expect(perf.events()).toEqual([])
})

test("the core's perf marks are no-ops without a sink and reach the start through one", () => {
  const perf = log(true)
  const traces = new StartTraces()
  traces.setLog(perf)
  perfMark("s1", "claude.initialized", { ms: 2_100 })
  traces.bind("s1", traces.begin("SAPP.2"))
  setPerfMarkSink((id, event, fields) => traces.markSession(id, event, fields, true))
  perfMark("s1", "claude.initialized", { ms: 2_100, commands: 39 })
  perfMark("s1", "claude.initialized", { ms: 1 })
  setPerfMarkSink(() => { throw new Error("a sink that throws") })
  expect(() => perfMark("s1", "claude.first_stream")).not.toThrow()
  expect(perf.events().map((e) => [e.event, e.trace, e.ms, e.commands])).toEqual([["start.claude.initialized", "SAPP.2", 2_100, 39]])
})
