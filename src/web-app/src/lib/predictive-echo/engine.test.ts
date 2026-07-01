import { describe, it, expect } from "bun:test"
import { PredictionEngine } from "./engine"
import { DEFAULT_CONFIG } from "./types"

function mkEngine() {
  let t = 1000
  const eng = new PredictionEngine(DEFAULT_CONFIG, () => t)
  return { eng, tick: (ms: number) => { t += ms } }
}
const enc = (s: string) => new TextEncoder().encode(s)

describe("PredictionEngine — gate + epoch (Step 2)", () => {
  it("does NOT predict when latency is below threshold", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(10)
    expect(eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })).toEqual([])
  })
  it("holds the first char of an epoch tentative (nothing drawn, caret unmoved)", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    expect(eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })).toEqual([])
  })
  it("draws the backlog + rides the caret when the first prediction confirms", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 }) // tentative id1 @5, tentative→6, physical=5
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 5 }) // tentative id2 @6, tentative→7
    expect(eng.onServerData(enc("a"))).toEqual([
      { op: "hideCaret" },
      { op: "moveCaret", row: 0, col: 5 },
      { op: "passthrough", bytes: enc("a") },
      { op: "drawDim", id: 2, row: 0, col: 6, char: "b" },
      { op: "moveCaret", row: 0, col: 7 },
      { op: "showCaret" },
    ])
  })
  it("keeps the epoch confirmed across a drain (every char after the first draws)", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    expect(eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })).toEqual([]) // tentative
    eng.onServerData(enc("a")) // confirm epoch + drain
    expect(eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 6 })).toEqual([
      { op: "drawDim", id: 2, row: 0, col: 6, char: "b" },
      { op: "moveCaret", row: 0, col: 7 },
    ])
  })
  it("never draws when the app does not echo (password-prompt safety)", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    expect(eng.onInput({ kind: "char", text: "p" }, { row: 0, col: 9 })).toEqual([])
    expect(eng.onInput({ kind: "char", text: "w" }, { row: 0, col: 9 })).toEqual([])
    expect(eng.onInput({ kind: "char", text: "d" }, { row: 0, col: 9 })).toEqual([])
  })
  it("stops predicting once maxPending outstanding predictions is reached", () => {
    let t = 1000
    const eng = new PredictionEngine({ latencyThresholdMs: 40, cooldownMs: 600, maxPending: 2 }, () => t)
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 0 }) // tentative id1, pending=1
    eng.onServerData(enc("a")) // confirm + drain, pending=0
    expect(eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 1 }).length).toBe(2) // drawn (drawDim+moveCaret)
    expect(eng.onInput({ kind: "char", text: "c" }, { row: 0, col: 1 }).length).toBe(2)
    expect(eng.onInput({ kind: "char", text: "d" }, { row: 0, col: 1 })).toEqual([]) // maxPending
  })
})

describe("PredictionEngine — caret + reconciliation (Step 2)", () => {
  it("draws a char dim and advances the caret once the epoch is confirmed", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })
    eng.onServerData(enc("a")) // warm
    expect(eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 6 })).toEqual([
      { op: "drawDim", id: 2, row: 0, col: 6, char: "b" },
      { op: "moveCaret", row: 0, col: 7 },
    ])
  })
  it("confirms a drawn prediction in place via passthrough (caret rides)", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })
    eng.onServerData(enc("a")) // warm
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 6 }) // drawn id2 @6, physical=6, tentative=7
    expect(eng.onServerData(enc("b"))).toEqual([
      { op: "hideCaret" },
      { op: "moveCaret", row: 0, col: 6 },
      { op: "passthrough", bytes: enc("b") },
      { op: "showCaret" },
    ])
  })
  it("partial echo: confirms the matched prefix and re-places the caret after the dim tail", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "x" }, { row: 0, col: 0 })
    eng.onServerData(enc("x")) // warm
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 1 }) // drawn id2 @1, physical=1, tentative→2
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 1 }) // drawn id3 @2, tentative→3
    expect(eng.onServerData(enc("a"))).toEqual([
      { op: "hideCaret" },
      { op: "moveCaret", row: 0, col: 1 },
      { op: "passthrough", bytes: enc("a") },
      { op: "moveCaret", row: 0, col: 3 }, // caret after the still-dim 'b'
      { op: "showCaret" },
    ])
  })
  it("suppresses the tentative caret reposition when the chunk contains an escape/control byte", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "x" }, { row: 0, col: 0 })
    eng.onServerData(enc("x")) // warm
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 1 }) // drawn id2 @1
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 1 }) // drawn id3 @2
    expect(eng.onServerData(enc("a\x1b[K"))).toEqual([
      { op: "hideCaret" },
      { op: "moveCaret", row: 0, col: 1 },
      { op: "passthrough", bytes: enc("a\x1b[K") },
      { op: "showCaret" }, // no tentative reposition — the chunk had an escape
    ])
  })
  it("passes server bytes straight through when there are no predictions", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    expect(eng.onServerData(enc("hello"))).toEqual([{ op: "passthrough", bytes: enc("hello") }])
  })
  it("divergence (drawn): erases every drawn prediction, replays the chunk, resets + cooldown", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "x" }, { row: 0, col: 0 })
    eng.onServerData(enc("x")) // warm
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 1 }) // drawn id2 @1
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 1 }) // drawn id3 @2
    expect(eng.onServerData(enc("aY"))).toEqual([ // 'a' confirms, 'Y' diverges from 'b'
      { op: "hideCaret" },
      { op: "restoreCell", id: 2, row: 0, col: 1 },
      { op: "restoreCell", id: 3, row: 0, col: 2 },
      { op: "moveCaret", row: 0, col: 1 },
      { op: "passthrough", bytes: enc("aY") },
      { op: "showCaret" },
    ])
    expect(eng.onInput({ kind: "char", text: "c" }, { row: 0, col: 2 })).toEqual([]) // cooldown
  })
  it("divergence (tentative only): no restore ops, still replays + resets", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 }) // tentative id1
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 5 }) // tentative id2
    expect(eng.onServerData(enc("X"))).toEqual([
      { op: "hideCaret" },
      { op: "moveCaret", row: 0, col: 5 },
      { op: "passthrough", bytes: enc("X") },
      { op: "showCaret" },
    ])
  })
})

describe("PredictionEngine — backspace/boundary + opaque + reset (Step 2)", () => {
  it("backspace predicts a space, rides the caret back, and stops at the line-start column", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "l" }, { row: 0, col: 2 })
    eng.onServerData(enc("l")) // warm at col 2 (after "$ ")
    eng.onInput({ kind: "char", text: "s" }, { row: 0, col: 3 }) // drawn id2 @3, tentative→4
    expect(eng.onInput({ kind: "backspace" }, { row: 0, col: 3 })).toEqual([
      { op: "drawDim", id: 3, row: 0, col: 3, char: " " },
      { op: "moveCaret", row: 0, col: 3 },
    ])
    expect(eng.onInput({ kind: "backspace" }, { row: 0, col: 3 })).toEqual([
      { op: "drawDim", id: 4, row: 0, col: 2, char: " " },
      { op: "moveCaret", row: 0, col: 2 },
    ])
    expect(eng.onInput({ kind: "backspace" }, { row: 0, col: 3 })).toEqual([]) // at start col 2 → refused
  })
  it("opaque input (Enter) erases drawn predictions, snaps the caret to physical, resets", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "x" }, { row: 0, col: 0 })
    eng.onServerData(enc("x")) // warm
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 1 }) // drawn id2 @1, physical=1
    expect(eng.onInput({ kind: "opaque" }, { row: 0, col: 1 })).toEqual([
      { op: "hideCaret" },
      { op: "restoreCell", id: 2, row: 0, col: 1 },
      { op: "moveCaret", row: 0, col: 1 },
      { op: "showCaret" },
    ])
  })
  it("reset() erases drawn predictions and clears state", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 0 })
    eng.onServerData(enc("a")) // warm
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 1 }) // drawn id2 @1, physical=1
    expect(eng.reset()).toEqual([
      { op: "hideCaret" },
      { op: "restoreCell", id: 2, row: 0, col: 1 },
      { op: "moveCaret", row: 0, col: 1 },
      { op: "showCaret" },
    ])
    expect(eng.reset()).toEqual([])
  })
})

describe("PredictionEngine — latency measurement (Step 2)", () => {
  it("learns latency from confirm timing (EWMA) so it keeps engaging", () => {
    const { eng, tick } = mkEngine()
    eng.primeForTest()
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 0 }) // tentative
    tick(150)
    eng.onServerData(enc("a")) // confirm + sample latency
    expect(eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 1 })[0])
      .toMatchObject({ op: "drawDim", char: "b" })
  })
})
