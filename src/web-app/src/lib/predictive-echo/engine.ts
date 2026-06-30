import type { InputEvent, CursorPos, DisplayOp, PredictionConfig } from "./types"

interface Pending {
  id: number
  row: number
  col: number
  char: string
  predictedAt: number
}

export class PredictionEngine {
  private cfg: PredictionConfig
  private now: () => number
  private nextId = 1
  private pending: Pending[] = []
  private latencyMs = 0
  private cursor: CursorPos | null = null
  private cooldownUntil = 0

  constructor(cfg: PredictionConfig, now: () => number) {
    this.cfg = cfg
    this.now = now
  }

  setLatencyEstimate(ms: number): void { this.latencyMs = ms }
  /** test-only: prime the estimate so the first prediction is allowed */
  primeForTest(): void { this.latencyMs = this.cfg.latencyThresholdMs }

  private active(): boolean {
    return this.latencyMs >= this.cfg.latencyThresholdMs && this.now() >= this.cooldownUntil
  }

  onInput(ev: InputEvent, serverCursor: CursorPos): DisplayOp[] {
    if (!this.active()) return []
    if (ev.kind === "opaque") return []
    if (this.pending.length === 0) this.cursor = { ...serverCursor }
    if (this.pending.length >= this.cfg.maxPending) return []
    // invariant: cursor is non-null here — seeded above when pending was empty,
    // and retained (never cleared without also clearing pending) while pending > 0.
    const cursor = this.cursor!

    if (ev.kind === "char") {
      const p: Pending = {
        id: this.nextId++, row: cursor.row, col: cursor.col,
        char: ev.text, predictedAt: this.now(),
      }
      this.pending.push(p)
      cursor.col += 1
      return [{ op: "predict", id: p.id, row: p.row, col: p.col, char: p.char }]
    }
    if (ev.kind === "backspace") {
      if (cursor.col <= 0) return []
      cursor.col -= 1
      const p: Pending = {
        id: this.nextId++, row: cursor.row, col: cursor.col,
        char: " ", predictedAt: this.now(),
      }
      this.pending.push(p)
      return [{ op: "predict", id: p.id, row: p.row, col: p.col, char: " " }]
    }
    if (ev.kind === "cursorLeft") { if (cursor.col > 0) cursor.col -= 1; return [] }
    if (ev.kind === "cursorRight") { cursor.col += 1; return [] }
    return []
  }

  // v1 reconciliation limitations (all self-heal via rollback + cooldown):
  // - a CSI sequence split across two onServerData calls misreads the leading '[' (spurious rollback);
  // - only ESC[ (CSI) is fully skipped, so a mid-stream OSC (ESC]) can leak content bytes to the matcher;
  // - wide chars (CJK/emoji) advance col by 1 not 2, so predictions after one are a column early.
  // Cross-call escape buffering + wcwidth are deferred to a later version.
  onServerData(bytes: Uint8Array): DisplayOp[] {
    if (this.pending.length === 0) return []
    const ops: DisplayOp[] = []
    const chars = [...new TextDecoder().decode(bytes)]
    for (let i = 0; i < chars.length; i++) {
      const ch = chars[i]!
      const cp = ch.codePointAt(0)!
      if (cp === 0x1b) { // skip a whole escape sequence (CSI: ESC [ ... final byte 0x40-0x7e)
        i++
        if (chars[i] === "[") {
          i++
          while (i < chars.length && !(chars[i]!.codePointAt(0)! >= 0x40 && chars[i]!.codePointAt(0)! <= 0x7e)) i++
        }
        continue
      }
      if (cp < 0x20 || cp === 0x7f) continue // CR/LF / other lone control bytes
      const head = this.pending[0]!
      if (ch === head.char) {
        this.sampleLatency(this.now() - head.predictedAt)
        ops.push({ op: "confirm", id: head.id })
        this.pending.shift()
        if (this.pending.length === 0) break
      } else {
        const ids = this.pending.map((p) => p.id)
        this.pending = []
        this.cursor = null
        this.cooldownUntil = this.now() + this.cfg.cooldownMs
        return [...ops, { op: "rollback", ids }]
      }
    }
    return ops
  }

  private sampleLatency(ms: number): void {
    this.latencyMs = this.latencyMs === 0 ? ms : Math.round(this.latencyMs * 0.7 + ms * 0.3)
  }

  reset(): DisplayOp[] {
    if (this.pending.length === 0) { this.cursor = null; return [] }
    const ids = this.pending.map((p) => p.id)
    this.pending = []
    this.cursor = null
    return [{ op: "rollback", ids }]
  }
}
