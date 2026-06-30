import { describe, it, expect } from "bun:test"
import { PredictionEngine } from "./engine"
import { DEFAULT_CONFIG } from "./types"

function mkEngine() {
  let t = 1000
  const eng = new PredictionEngine(DEFAULT_CONFIG, () => t)
  return { eng, tick: (ms: number) => { t += ms } }
}
const enc = (s: string) => new TextEncoder().encode(s)

describe("PredictionEngine — char prediction + latency gate", () => {
  it("does NOT predict when latency is below threshold", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(10)
    expect(eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })).toEqual([])
  })
  it("predicts a dim char at the cursor when latency is high", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    expect(eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 }))
      .toEqual([{ op: "predict", id: 1, row: 0, col: 5, char: "a" }])
  })
  it("advances its own cursor for consecutive chars", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })
    expect(eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 5 }))
      .toEqual([{ op: "predict", id: 2, row: 0, col: 6, char: "b" }])
  })
  it("returns no ops for opaque input", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    expect(eng.onInput({ kind: "opaque" }, { row: 0, col: 5 })).toEqual([])
  })
})

describe("PredictionEngine — backspace + cursor moves", () => {
  it("backspace erases the previous cell (predicts a space) and retreats", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })
    expect(eng.onInput({ kind: "backspace" }, { row: 0, col: 5 }))
      .toEqual([{ op: "predict", id: 2, row: 0, col: 5, char: " " }])
  })
  it("left/right arrows move the engine cursor without drawing", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })
    expect(eng.onInput({ kind: "cursorLeft" }, { row: 0, col: 5 })).toEqual([])
    expect(eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 5 }))
      .toEqual([{ op: "predict", id: 2, row: 0, col: 5, char: "b" }])
  })
})

describe("PredictionEngine — reconciliation", () => {
  it("confirms predictions when the server echoes the same chars in order", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 5 })
    expect(eng.onServerData(enc("ab"))).toEqual([{ op: "confirm", id: 1 }, { op: "confirm", id: 2 }])
  })
  it("rolls back all pending + enters cooldown when the echo diverges", () => {
    const { eng, tick } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 5 })
    expect(eng.onServerData(enc("X"))).toEqual([{ op: "rollback", ids: [1, 2] }])
    expect(eng.onInput({ kind: "char", text: "c" }, { row: 0, col: 6 })).toEqual([])
    tick(700)
    expect(eng.onInput({ kind: "char", text: "d" }, { row: 0, col: 6 }))
      .toEqual([{ op: "predict", id: 3, row: 0, col: 6, char: "d" }])
  })
  it("ignores non-printable bytes (CR/LF, escapes) when matching", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })
    expect(eng.onServerData(enc("\x1b[1G\ra"))).toEqual([{ op: "confirm", id: 1 }])
  })
  it("returns confirms accumulated before a mid-buffer divergence, then rolls back the rest", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 5 })
    expect(eng.onServerData(enc("aX")))
      .toEqual([{ op: "confirm", id: 1 }, { op: "rollback", ids: [2] }])
  })
  it("stops predicting once maxPending is reached", () => {
    let t = 1000
    const eng = new PredictionEngine({ latencyThresholdMs: 40, cooldownMs: 600, maxPending: 2 }, () => t)
    eng.setLatencyEstimate(120)
    expect(eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 0 }).length).toBe(1)
    expect(eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 0 }).length).toBe(1)
    expect(eng.onInput({ kind: "char", text: "c" }, { row: 0, col: 0 })).toEqual([])
  })
})

describe("PredictionEngine — latency measurement + reset", () => {
  it("learns latency from confirm timing (EWMA) so it keeps engaging", () => {
    const { eng, tick } = mkEngine()
    eng.primeForTest()
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 0 })
    tick(150)
    eng.onServerData(enc("a"))
    expect(eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 1 })[0])
      .toMatchObject({ op: "predict", char: "b" })
  })
  it("reset() rolls back outstanding predictions and clears state", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 0 })
    expect(eng.reset()).toEqual([{ op: "rollback", ids: [1] }])
    expect(eng.reset()).toEqual([])
  })
})
