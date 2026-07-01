export type InputEvent =
  | { kind: "char"; text: string }
  | { kind: "backspace" }
  | { kind: "cursorLeft" }
  | { kind: "cursorRight" }
  | { kind: "opaque" }

export interface CursorPos { row: number; col: number }

// Step 2 (caret rewrite): the engine orchestrates the written stream via these
// abstract ops so the caret rides the user's typing. The adapter maps each op to
// its terminal's primitives (xterm here; SwiftTerm/termlib in later phases).
export type DisplayOp =
  // Draw an unconfirmed glyph at a cell (adapter: dim SGR).
  | { op: "drawDim"; id: number; row: number; col: number; char: string }
  // Reposition the real caret (adapter: absolute CUP).
  | { op: "moveCaret"; row: number; col: number }
  // Erase a rolled-back prediction, restoring the pre-prediction snapshot.
  | { op: "restoreCell"; id: number; row: number; col: number }
  // Write authoritative server bytes as-is (adapter: term.write). Confirmed
  // echoes paint over the dim cells here — that IS the confirm.
  | { op: "passthrough"; bytes: Uint8Array }
  // Bracket a reconcile batch to hide repositioning flicker.
  | { op: "hideCaret" }
  | { op: "showCaret" }

export interface PredictionConfig {
  latencyThresholdMs: number
  cooldownMs: number
  maxPending: number
}

export const DEFAULT_CONFIG: PredictionConfig = {
  latencyThresholdMs: 40, // engage only above ~typical-WiFi RTT (predictions add no value on a fast link)
  cooldownMs: 600,        // after a mispredict, pause long enough to avoid flicker storms
  maxPending: 50,         // cap outstanding predictions so state can't grow unbounded if the server stalls
}

/** Decode one xterm onData payload into a prediction input event.
 *  Only single printable chars, lone DEL/BS, and lone left/right arrows are
 *  predictable; everything else (Enter, Tab, Ctrl-keys, other escapes, and any
 *  multi-character payload such as a paste) is opaque. */
export function decodeInput(data: string): InputEvent {
  if (data === "\x7f" || data === "\b") return { kind: "backspace" }
  if (data === "\x1b[D") return { kind: "cursorLeft" }
  if (data === "\x1b[C") return { kind: "cursorRight" }
  if ([...data].length === 1) {
    const cp = data.codePointAt(0)!
    if (cp >= 0x20 && cp !== 0x7f) return { kind: "char", text: data }
  }
  return { kind: "opaque" }
}
