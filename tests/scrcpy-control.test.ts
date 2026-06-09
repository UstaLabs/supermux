import { test, expect } from "bun:test"
import { encodeTouch, encodeKey, encodeText, TouchAction } from "../src/core/display/scrcpy/control"

test("encodeTouch matches the documented 32-byte layout", () => {
  const b = encodeTouch({ action: TouchAction.DOWN, x: 100, y: 200, width: 1080, height: 2400 })
  expect(b.length).toBe(32)
  expect(b[0]).toBe(2)  // INJECT_TOUCH_EVENT
  expect(b[1]).toBe(0)  // ACTION_DOWN
  const dv = new DataView(b.buffer)
  expect(dv.getUint32(10)).toBe(100)   // x (after type1+action1+pointerId8)
  expect(dv.getUint32(14)).toBe(200)   // y
  expect(dv.getUint16(18)).toBe(1080)  // width
  expect(dv.getUint16(20)).toBe(2400)  // height
})

test("encodeKey matches the documented 14-byte layout", () => {
  const b = encodeKey(66 /* ENTER */, 0, 0, 0)
  expect(b.length).toBe(14)
  expect(b[0]).toBe(0)  // INJECT_KEYCODE
  expect(b[1]).toBe(0)  // ACTION_DOWN
  expect(new DataView(b.buffer).getUint32(2)).toBe(66)
})

test("encodeText carries length + utf8", () => {
  const b = encodeText("hi")
  expect(b[0]).toBe(1)  // INJECT_TEXT
  expect(new DataView(b.buffer).getUint32(1)).toBe(2)
  expect(b[5]).toBe("h".charCodeAt(0))
  expect(b[6]).toBe("i".charCodeAt(0))
})
