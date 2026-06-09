export enum TouchAction { DOWN = 0, UP = 1, MOVE = 2 }

const TYPE_INJECT_KEYCODE = 0
const TYPE_INJECT_TEXT = 1
const TYPE_INJECT_TOUCH = 2

export interface TouchInput {
  action: TouchAction
  x: number
  y: number
  width: number
  height: number
  pointerId?: bigint
}

// INJECT_TOUCH layout (32 bytes) — see protocol.md.
export function encodeTouch(t: TouchInput): Uint8Array {
  const b = new Uint8Array(32)
  const dv = new DataView(b.buffer)
  b[0] = TYPE_INJECT_TOUCH
  b[1] = t.action
  dv.setBigUint64(2, t.pointerId ?? 0xffffffffffffffffn)
  dv.setUint32(10, t.x >>> 0)
  dv.setUint32(14, t.y >>> 0)
  dv.setUint16(18, t.width)
  dv.setUint16(20, t.height)
  dv.setUint16(22, t.action === TouchAction.UP ? 0 : 0xffff) // pressure (0xFFFF = 1.0)
  dv.setUint32(24, t.action === TouchAction.DOWN ? 1 : 0)     // actionButton (PRIMARY)
  dv.setUint32(28, t.action === TouchAction.UP ? 0 : 1)       // buttons
  return b
}

// INJECT_KEYCODE layout (14 bytes).
export function encodeKey(keycode: number, action = 0, metaState = 0, repeat = 0): Uint8Array {
  const b = new Uint8Array(14)
  const dv = new DataView(b.buffer)
  b[0] = TYPE_INJECT_KEYCODE
  b[1] = action
  dv.setUint32(2, keycode >>> 0)
  dv.setUint32(6, repeat >>> 0)
  dv.setUint32(10, metaState >>> 0)
  return b
}

// INJECT_TEXT layout.
export function encodeText(text: string): Uint8Array {
  const t = new TextEncoder().encode(text)
  const b = new Uint8Array(5 + t.length)
  const dv = new DataView(b.buffer)
  b[0] = TYPE_INJECT_TEXT
  dv.setUint32(1, t.length)
  b.set(t, 5)
  return b
}
