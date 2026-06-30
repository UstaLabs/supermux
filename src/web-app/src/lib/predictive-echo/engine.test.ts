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
  it("holds the first char of an epoch tentative (not drawn) until the server confirms", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    expect(eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })).toEqual([])
  })
  it("draws the backlog (at advanced cursor cols) when the first prediction confirms", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 }) // tentative id1 @5, cursor→6
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 5 }) // tentative id2 @6, cursor→7
    // Server echoes 'a': epoch confirms; 'a' was undrawn (no confirm op); backlog 'b' draws at col 6.
    expect(eng.onServerData(enc("a"))).toEqual([{ op: "predict", id: 2, row: 0, col: 6, char: "b" }])
  })
  it("keeps the epoch confirmed across a pending-drain (slow typing still predicts every char after the first)", () => {
    // The trap: if the epoch reset whenever the queue drained, typing slower than
    // the round-trip would make EVERY char "first in epoch" → tentative → nothing
    // ever draws. The epoch must persist across a natural drain.
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    expect(eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 })).toEqual([]) // first char tentative
    eng.onServerData(enc("a"))                                                        // confirms epoch; queue drains empty
    // Queue is empty but the epoch stays confirmed — the next char draws instantly.
    expect(eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 6 }))
      .toEqual([{ op: "predict", id: 2, row: 0, col: 6, char: "b" }])
  })
  it("returns no ops for opaque input when nothing is pending", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    expect(eng.onInput({ kind: "opaque" }, { row: 0, col: 5 })).toEqual([])
  })
  it("never draws predictions when the app does not echo (password-prompt safety)", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    expect(eng.onInput({ kind: "char", text: "p" }, { row: 0, col: 9 })).toEqual([])
    expect(eng.onInput({ kind: "char", text: "w" }, { row: 0, col: 9 })).toEqual([])
    expect(eng.onInput({ kind: "char", text: "d" }, { row: 0, col: 9 })).toEqual([])
    // A divergent printable byte clears the (undrawn) pending with no visual rollback.
    expect(eng.onServerData(enc("X"))).toEqual([])
  })
})

describe("PredictionEngine — backspace + cursor moves", () => {
  it("backspace predicts a space and retreats once the epoch is confirmed", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 }) // tentative id1 @5, cursor→6
    eng.onServerData(enc("a"))                                   // confirm epoch (backlog empty)
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 6 }) // drawn id2 @6, cursor→7
    expect(eng.onInput({ kind: "backspace" }, { row: 0, col: 6 }))
      .toEqual([{ op: "predict", id: 3, row: 0, col: 6, char: " " }]) // cursor 7→6
  })
  it("left arrow moves the predicted cursor (no draw); next char lands at the moved column", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 }) // tentative id1 @5, cursor→6
    eng.onServerData(enc("a"))                                   // confirm epoch
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 6 }) // drawn id2 @6, cursor→7
    expect(eng.onInput({ kind: "cursorLeft" }, { row: 0, col: 6 })).toEqual([]) // 7→6
    expect(eng.onInput({ kind: "char", text: "c" }, { row: 0, col: 6 }))
      .toEqual([{ op: "predict", id: 3, row: 0, col: 6, char: "c" }]) // c at the moved col 6
  })
  it("refuses to predict-delete past the line's start column (no eating the prompt)", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    // Warm the epoch at col 2 (just after a "$ " prompt) so predictions draw.
    eng.onInput({ kind: "char", text: "l" }, { row: 0, col: 2 }) // tentative id1 @2, start=2
    eng.onServerData(enc("l"))                                   // confirm epoch
    eng.onInput({ kind: "char", text: "s" }, { row: 0, col: 3 }) // drawn id2 @3, cursor→4
    expect(eng.onInput({ kind: "backspace" }, { row: 0, col: 3 }))
      .toEqual([{ op: "predict", id: 3, row: 0, col: 3, char: " " }]) // 4→3
    expect(eng.onInput({ kind: "backspace" }, { row: 0, col: 3 }))
      .toEqual([{ op: "predict", id: 4, row: 0, col: 2, char: " " }]) // 3→2
    // A third backspace would cross into the prompt: refused.
    expect(eng.onInput({ kind: "backspace" }, { row: 0, col: 3 })).toEqual([]) // at col2, floor2
  })
  it("cursor-left stops at the line's start column", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "x" }, { row: 0, col: 2 }) // tentative id1 @2, start=2
    eng.onServerData(enc("x"))                                   // confirm epoch
    eng.onInput({ kind: "char", text: "y" }, { row: 0, col: 3 }) // drawn id2 @3, cursor→4
    eng.onInput({ kind: "cursorLeft" }, { row: 0, col: 3 })      // 4→3
    eng.onInput({ kind: "cursorLeft" }, { row: 0, col: 3 })      // 3→2
    eng.onInput({ kind: "cursorLeft" }, { row: 0, col: 3 })      // 2→2 clamped (floor 2)
    expect(eng.onInput({ kind: "char", text: "z" }, { row: 0, col: 3 }))
      .toEqual([{ op: "predict", id: 3, row: 0, col: 2, char: "z" }]) // z at the clamped col 2
  })
  it("the start-column boundary resets on Enter so a new line re-seeds it", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "x" }, { row: 0, col: 2 }) // tentative id1, start=2
    eng.onServerData(enc("x"))                                   // confirm epoch (x shifts out)
    eng.onInput({ kind: "opaque" }, { row: 0, col: 3 })          // Enter: reset epoch + boundary
    eng.onInput({ kind: "char", text: "a" }, { row: 1, col: 0 }) // tentative id2, start=0
    eng.onServerData(enc("a"))                                   // confirm epoch
    eng.onInput({ kind: "char", text: "b" }, { row: 1, col: 1 }) // drawn id3 @1, cursor→2
    expect(eng.onInput({ kind: "backspace" }, { row: 1, col: 1 }))
      .toEqual([{ op: "predict", id: 4, row: 1, col: 1, char: " " }]) // 2→1
    expect(eng.onInput({ kind: "backspace" }, { row: 1, col: 1 }))
      .toEqual([{ op: "predict", id: 5, row: 1, col: 0, char: " " }]) // 1→0
    expect(eng.onInput({ kind: "backspace" }, { row: 1, col: 1 })).toEqual([]) // at col0, floor0
  })
})

describe("PredictionEngine — reconciliation", () => {
  it("confirms in order; the first echo also draws the backlog", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 }) // tentative id1 @5
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 5 }) // tentative id2 @6
    expect(eng.onServerData(enc("ab")))
      .toEqual([{ op: "predict", id: 2, row: 0, col: 6, char: "b" }, { op: "confirm", id: 2 }])
  })
  it("rolls back drawn predictions + enters cooldown when a confirmed epoch diverges", () => {
    const { eng, tick } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 }) // tentative id1
    eng.onServerData(enc("a"))                                   // confirm epoch
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 6 }) // drawn id2 @6
    eng.onInput({ kind: "char", text: "c" }, { row: 0, col: 6 }) // drawn id3 @7
    expect(eng.onServerData(enc("X"))).toEqual([{ op: "rollback", ids: [2, 3] }])
    expect(eng.onInput({ kind: "char", text: "d" }, { row: 0, col: 6 })).toEqual([]) // cooldown
    tick(700)
    // After cooldown, the first char of the fresh epoch is tentative again (no draw).
    expect(eng.onInput({ kind: "char", text: "e" }, { row: 0, col: 6 })).toEqual([])
  })
  it("emits no rollback op when a divergence hits only tentative (undrawn) predictions", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 }) // tentative
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 5 }) // tentative
    expect(eng.onServerData(enc("X"))).toEqual([])
  })
  it("ignores non-printable bytes (CR/LF, escapes) when matching", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 }) // tentative id1
    eng.onServerData(enc("a"))                                   // confirm epoch
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 6 }) // drawn id2 @6
    expect(eng.onServerData(enc("\x1b[1G\rb"))).toEqual([{ op: "confirm", id: 2 }])
  })
  it("on mid-buffer divergence: draws the backlog on the first confirm, then rolls back what it drew", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 }) // tentative id1
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 5 }) // tentative id2 @6
    expect(eng.onServerData(enc("aX")))
      .toEqual([{ op: "predict", id: 2, row: 0, col: 6, char: "b" }, { op: "rollback", ids: [2] }])
  })
  it("stops predicting once maxPending outstanding predictions is reached", () => {
    let t = 1000
    const eng = new PredictionEngine({ latencyThresholdMs: 40, cooldownMs: 600, maxPending: 2 }, () => t)
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 0 }) // tentative id1, pending=1
    eng.onServerData(enc("a"))                                   // confirm epoch, pending=0
    expect(eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 1 }).length).toBe(1) // pending=1
    expect(eng.onInput({ kind: "char", text: "c" }, { row: 0, col: 1 }).length).toBe(1) // pending=2
    expect(eng.onInput({ kind: "char", text: "d" }, { row: 0, col: 1 })).toEqual([])    // maxPending
  })
  it("opaque input (Enter) rolls back drawn predictions and resets the epoch", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 5 }) // tentative id1
    eng.onServerData(enc("a"))                                   // confirm epoch
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 6 }) // drawn id2 @6
    expect(eng.onInput({ kind: "opaque" }, { row: 0, col: 6 })).toEqual([{ op: "rollback", ids: [2] }])
  })
})

describe("PredictionEngine — latency measurement + reset", () => {
  it("learns latency from confirm timing (EWMA) so it keeps engaging", () => {
    const { eng, tick } = mkEngine()
    eng.primeForTest()
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 0 }) // tentative
    tick(150)
    eng.onServerData(enc("a"))                                   // confirm epoch + sample latency
    expect(eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 1 })[0])
      .toMatchObject({ op: "predict", char: "b" })
  })
  it("reset() rolls back drawn predictions and clears state", () => {
    const { eng } = mkEngine()
    eng.setLatencyEstimate(120)
    eng.onInput({ kind: "char", text: "a" }, { row: 0, col: 0 }) // tentative id1
    eng.onServerData(enc("a"))                                   // confirm epoch
    eng.onInput({ kind: "char", text: "b" }, { row: 0, col: 1 }) // drawn id2
    expect(eng.reset()).toEqual([{ op: "rollback", ids: [2] }])
    expect(eng.reset()).toEqual([])
  })
})
