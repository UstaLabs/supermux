import { test, expect } from "bun:test"
import { readFileSync } from "fs"
import { ScrcpyVideoParser } from "../src/core/display/scrcpy/video"

function frame(payload: number[], { config = false, key = false } = {}): Uint8Array {
  const buf = new Uint8Array(12 + payload.length)
  const dv = new DataView(buf.buffer)
  let hi = 0
  if (config) hi |= 0x80000000
  if (key) hi |= 0x40000000
  dv.setUint32(0, hi >>> 0)
  dv.setUint32(4, 0)
  dv.setUint32(8, payload.length)
  buf.set(payload, 12)
  return buf
}

test("emits access units split across chunk boundaries", () => {
  const parser = new ScrcpyVideoParser()
  const got: { config: boolean; key: boolean; data: number[] }[] = []
  parser.onAccessUnit = (au) => got.push({ config: au.config, key: au.keyFrame, data: [...au.data] })
  const f1 = frame([9, 9], { config: true })
  const f2 = frame([1, 2, 3], { key: true })
  const all = new Uint8Array([...f1, ...f2])
  parser.push(all.slice(0, 5))
  parser.push(all.slice(5))
  expect(got).toEqual([
    { config: true, key: false, data: [9, 9] },
    { config: false, key: true, data: [1, 2, 3] },
  ])
})

test("parses the real captured config frame from the fixture", () => {
  const raw = readFileSync("tests/fixtures/scrcpy-video-head.bin")
  // strip handshake: dummy(1) + name(64) + codecMeta(12) = 77
  const frameBytes = new Uint8Array(raw.subarray(77))
  const parser = new ScrcpyVideoParser()
  const got: { config: boolean; len: number; head: number[] }[] = []
  parser.onAccessUnit = (au) => got.push({ config: au.config, len: au.data.length, head: [...au.data.slice(0, 5)] })
  parser.push(frameBytes)
  expect(got.length).toBe(1)
  expect(got[0]!.config).toBe(true)        // first packet is CONFIG (SPS/PPS)
  expect(got[0]!.len).toBe(32)             // 32-byte config payload
  expect(got[0]!.head).toEqual([0, 0, 0, 1, 0x67]) // Annex-B start code + SPS NAL (0x67)
})
