export type InputEvent =
  | { kind: "char"; text: string }
  | { kind: "backspace" }
  | { kind: "cursorLeft" }
  | { kind: "cursorRight" }
  | { kind: "opaque" }

export interface CursorPos { row: number; col: number }

export type DisplayOp =
  | { op: "predict"; id: number; row: number; col: number; char: string }
  | { op: "confirm"; id: number }
  | { op: "rollback"; ids: number[] }

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
