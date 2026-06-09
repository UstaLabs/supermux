import { test, expect } from "bun:test"
import { encodeFrame, decodeFrames } from "../src/shared/frame-codec"

test("encodes a JSON value as a length-prefixed frame", () => {
  const buf = encodeFrame({ kind: "ping" })
  // 4-byte big-endian length + UTF-8 JSON
  const len = buf.readUInt32BE(0)
  const json = buf.subarray(4).toString("utf8")
  expect(len).toBe(json.length)
  expect(JSON.parse(json)).toEqual({ kind: "ping" })
})

test("decodes a single frame from a buffer", () => {
  const buf = encodeFrame({ kind: "pong" })
  const { messages, rest } = decodeFrames(buf)
  expect(messages).toEqual([{ kind: "pong" }])
  expect(rest.length).toBe(0)
})

test("decodes multiple concatenated frames", () => {
  const buf = Buffer.concat([
    encodeFrame({ a: 1 }),
    encodeFrame({ b: 2 }),
  ])
  const { messages } = decodeFrames(buf)
  expect(messages).toEqual([{ a: 1 }, { b: 2 }])
})

test("keeps a partial trailing frame in rest", () => {
  const full = encodeFrame({ a: 1 })
  const partial = encodeFrame({ b: 2 }).subarray(0, 3) // truncated header
  const buf = Buffer.concat([full, partial])
  const { messages, rest } = decodeFrames(buf)
  expect(messages).toEqual([{ a: 1 }])
  expect(rest.length).toBe(3)
})
