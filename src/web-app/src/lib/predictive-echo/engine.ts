import type { InputEvent, CursorPos, DisplayOp, PredictionConfig } from "./types"

interface Pending {
  id: number
  row: number
  col: number
  char: string
  predictedAt: number
  drawn: boolean
}

export class PredictionEngine {
  private cfg: PredictionConfig
  private now: () => number
  private nextId = 1
  private pending: Pending[] = []
  private latencyMs = 0
  private cursor: CursorPos | null = null
  private cooldownUntil = 0
  // Whether the current epoch has had a server-confirmed prediction. Until it
  // does, predictions are tracked but NOT drawn ("wait for first confirmation"):
  // this hides post-Enter / into-prompt mispredicts, and means a no-echo context
  // (a password prompt) never draws anything. Reset on opaque input / divergence.
  private epochConfirmed = false
  // Leftmost column the current line's editing may touch (= the authoritative
  // cursor column when this prediction run began, right after the prompt).
  // Backspace/cursor-left predictions are refused at or left of it so we never
  // predict-delete the prompt. null = unseeded; set on the first prediction of a
  // line, reset when the line context changes.
  private epochStartCol: number | null = null

  constructor(cfg: PredictionConfig, now: () => number) {
    this.cfg = cfg
    this.now = now
  }

  setLatencyEstimate(ms: number): void { this.latencyMs = ms }
  /** test-only: prime the estimate so predictions are allowed past the latency gate */
  primeForTest(): void { this.latencyMs = this.cfg.latencyThresholdMs }

  private active(): boolean {
    return this.latencyMs >= this.cfg.latencyThresholdMs && this.now() >= this.cooldownUntil
  }

  onInput(ev: InputEvent, serverCursor: CursorPos): DisplayOp[] {
    if (!this.active()) return []
    // Opaque input (Enter, Tab, Ctrl-keys, escapes, paste) changes the line
    // unpredictably → roll back drawn predictions and reset the epoch.
    if (ev.kind === "opaque") return this.resetEpoch()
    if (this.pending.length === 0) {
      this.cursor = { ...serverCursor }
      if (this.epochStartCol === null) this.epochStartCol = serverCursor.col
    }
    if (this.pending.length >= this.cfg.maxPending) return []
    // invariant: cursor is non-null here — seeded above when pending was empty,
    // and retained (never cleared without also clearing pending) while pending > 0.
    const cursor = this.cursor!

    if (ev.kind === "char") {
      const p: Pending = {
        id: this.nextId++, row: cursor.row, col: cursor.col,
        char: ev.text, predictedAt: this.now(), drawn: this.epochConfirmed,
      }
      this.pending.push(p)
      cursor.col += 1
      // Tentative (epoch not yet confirmed) → tracked for matching but not drawn.
      return p.drawn ? [{ op: "predict", id: p.id, row: p.row, col: p.col, char: p.char }] : []
    }
    if (ev.kind === "backspace") {
      // Never predict-delete past the line's start column (into the prompt).
      if (cursor.col <= (this.epochStartCol ?? 0)) return []
      cursor.col -= 1
      const p: Pending = {
        id: this.nextId++, row: cursor.row, col: cursor.col,
        char: " ", predictedAt: this.now(), drawn: this.epochConfirmed,
      }
      this.pending.push(p)
      return p.drawn ? [{ op: "predict", id: p.id, row: p.row, col: p.col, char: " " }] : []
    }
    if (ev.kind === "cursorLeft") { if (cursor.col > (this.epochStartCol ?? 0)) cursor.col -= 1; return [] }
    if (ev.kind === "cursorRight") { cursor.col += 1; return [] }
    return []
  }

  // v1 reconciliation limitations (all self-heal via rollback + cooldown):
  // - a CSI sequence split across two onServerData calls misreads the leading '[' (spurious rollback);
  // - only ESC[ (CSI) is fully skipped, so a mid-stream OSC (ESC]) can leak content bytes to the matcher;
  // - wide chars (CJK/emoji) advance col by 1 not 2, so predictions after one are a column early;
  // - no-echo input (password prompts, `read -s`) never confirms the epoch, so nothing is ever DRAWN
  //   (predictions stay tentative) — the v1 password-ghost is gone. A line whose app stops echoing
  //   mid-way, AFTER the epoch already confirmed, can still leave drawn predictions lingering until the
  //   next divergence; bounded timeout expiry for that residual case is deferred to a later version.
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
        if (head.drawn) ops.push({ op: "confirm", id: head.id })
        this.pending.shift()
        // First confirmation in this epoch → the app echoes, so trust it: confirm
        // the epoch and draw the backlog of tentative predictions queued behind it.
        if (!this.epochConfirmed) {
          this.epochConfirmed = true
          for (const p of this.pending) {
            if (!p.drawn) {
              p.drawn = true
              ops.push({ op: "predict", id: p.id, row: p.row, col: p.col, char: p.char })
            }
          }
        }
        if (this.pending.length === 0) break
      } else {
        // Divergence → roll back what we drew, reset the epoch, enter cooldown.
        const ids = this.pending.filter((p) => p.drawn).map((p) => p.id)
        this.pending = []
        this.cursor = null
        this.epochConfirmed = false
        this.epochStartCol = null
        this.cooldownUntil = this.now() + this.cfg.cooldownMs
        return ids.length ? [...ops, { op: "rollback", ids }] : ops
      }
    }
    return ops
  }

  private sampleLatency(ms: number): void {
    this.latencyMs = this.latencyMs === 0 ? ms : Math.round(this.latencyMs * 0.7 + ms * 0.3)
  }

  /** Roll back any drawn predictions and clear all epoch state. Used on opaque
   *  input (Enter/Tab/Ctrl/paste) and on reconnect via reset(). */
  private resetEpoch(): DisplayOp[] {
    const ids = this.pending.filter((p) => p.drawn).map((p) => p.id)
    this.pending = []
    this.cursor = null
    this.epochConfirmed = false
    this.epochStartCol = null
    return ids.length ? [{ op: "rollback", ids }] : []
  }

  reset(): DisplayOp[] {
    return this.resetEpoch()
  }
}
