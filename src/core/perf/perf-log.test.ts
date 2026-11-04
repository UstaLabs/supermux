import { afterEach, expect, test } from "bun:test"
import { existsSync, mkdtempSync, readFileSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { FrameStats, LoopLag, PerfLog, lapTimer, perfPath } from "./perf-log"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })
function tmp(): string {
  const d = mkdtempSync(join(tmpdir(), "mux-perf-"))
  dirs.push(d)
  return d
}
const lines = (dir: string) => readFileSync(join(dir, "perf.jsonl"), "utf8").trim().split("\n").map((l) => JSON.parse(l))

test("records nothing while tracing is off", () => {
  const dir = tmp()
  const perf = new PerfLog({ dir, flushMs: 0 })
  perf.record({ event: "http", ms: 3 })
  expect(perf.enabled()).toBe(false)
  expect(perf.events()).toEqual([])
  expect(existsSync(join(dir, "perf.jsonl"))).toBe(false)
})

test("MUX_PERF turns it on and every event lands in the ring and the jsonl file", () => {
  const dir = tmp()
  const perf = new PerfLog({ dir, envEnabled: true, flushMs: 0, now: () => 1_000 })
  perf.record({ event: "snapshot.built", trace: "L1", req: "s1", ms: 12.5 })
  expect(perf.events()).toEqual([{ ts: 1_000, src: "broker", event: "snapshot.built", trace: "L1", req: "s1", ms: 12.5 }])
  expect(lines(dir)).toEqual([{ ts: 1_000, src: "broker", event: "snapshot.built", trace: "L1", req: "s1", ms: 12.5 }])
})

test("the flag file turns it on and off at runtime", () => {
  const dir = tmp()
  const perf = new PerfLog({ dir, flushMs: 0, statCacheMs: 0 })
  expect(perf.enabled()).toBe(false)
  perf.setEnabled(true)
  expect(perf.enabled()).toBe(true)
  expect(existsSync(join(dir, "enabled"))).toBe(true)
  perf.setEnabled(false)
  expect(perf.enabled()).toBe(false)
})

test("events filter by trace and keep only the newest ringSize", () => {
  const perf = new PerfLog({ dir: tmp(), envEnabled: true, flushMs: 0, ringSize: 3 })
  for (const t of ["a", "b", "a", "a"]) perf.record({ event: "x", trace: t })
  expect(perf.events().map((e) => e.trace)).toEqual(["b", "a", "a"])
  expect(perf.events({ trace: "a" })).toHaveLength(2)
})

test("a client event keeps its own src and timestamp", () => {
  const perf = new PerfLog({ dir: tmp(), envEnabled: true, flushMs: 0, now: () => 5 })
  perf.record({ event: "ui.sessions.drawn", src: "client", ts: 3, t: 812, device: "mac" })
  expect(perf.events()[0]).toEqual({ ts: 3, src: "client", event: "ui.sessions.drawn", t: 812, device: "mac" })
})

test("rotates the file once it passes maxFileBytes", () => {
  const dir = tmp()
  const perf = new PerfLog({ dir, envEnabled: true, flushMs: 0, maxFileBytes: 200 })
  for (let i = 0; i < 10; i++) perf.record({ event: "x", pad: "y".repeat(40) })
  expect(existsSync(join(dir, "perf.jsonl.1"))).toBe(true)
  expect(readFileSync(join(dir, "perf.jsonl"), "utf8").length).toBeLessThan(400)
})

test("buffered writes reach the file on flush", () => {
  const dir = tmp()
  const perf = new PerfLog({ dir, envEnabled: true, flushMs: 60_000 })
  perf.record({ event: "x" })
  expect(existsSync(join(dir, "perf.jsonl"))).toBe(false)
  perf.flush()
  expect(lines(dir)).toHaveLength(1)
  perf.close()
})

test("FrameStats counts frames, bytes and sends per type and resets on take", () => {
  const s = new FrameStats()
  s.add("activity_append", 500, 3)
  s.add("activity_append", 300, 3)
  s.add("agent_state", 200, 2)
  expect(s.take()).toEqual({
    activity_append: { frames: 2, bytes: 800, sends: 6 },
    agent_state: { frames: 1, bytes: 200, sends: 2 },
  })
  expect(s.take()).toEqual({})
})

test("perfPath folds ids out of a path so routes group together", () => {
  expect(perfPath("/sessions/5fe66c32-94d9-43d2-916d-9f2ee74366bf/messages")).toBe("/sessions/:id/messages")
  expect(perfPath("/agents/0123456789abcdef0123/avatar")).toBe("/agents/:id/avatar")
  expect(perfPath("/files/f_8Kq2xYz71bQp")).toBe("/files/:id")
  expect(perfPath("/archived-sessions")).toBe("/archived-sessions")
  expect(perfPath("/project-catalog/12/image")).toBe("/project-catalog/:id/image")
})

test("lapTimer gives each phase its own time and the total", () => {
  let t = 0
  const laps = lapTimer(() => t)
  t = 5; laps.lap("a")
  t = 12; laps.lap("b")
  expect(laps.laps).toEqual({ a: 5, b: 7 })
  expect(laps.total()).toBe(12)
})

test("LoopLag reports how late its ticks fired, worst first", () => {
  let t = 0
  const lag = new LoopLag(500, () => t)
  t = 500; lag.tick()
  t = 1_180; lag.tick()     // 180 ms late
  t = 1_690; lag.tick()     // 10 ms late
  expect(lag.take()).toEqual({ ticks: 3, maxMs: 180, over50ms: 1 })
  expect(lag.take()).toEqual({ ticks: 0, maxMs: 0, over50ms: 0 })
})
