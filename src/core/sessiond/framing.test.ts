import { describe, expect, test } from "bun:test"
import { encodeFrame } from "../../shared/frame-codec"
import { SESSIOND_MAX_FRAME_BYTES, SessiondFrameAccumulator, SessiondFrameError } from "./framing"

function exactLimitFrame(base: Record<string, unknown>, paddingField = "padding"): Buffer {
  const overhead = Buffer.byteLength(JSON.stringify({ ...base, [paddingField]: "" }))
  return encodeFrame({ ...base, [paddingField]: "x".repeat(SESSIOND_MAX_FRAME_BYTES - overhead) })
}

describe("SessiondFrameAccumulator", () => {
  test("emits split and coalesced frames in order while retaining only one residual", () => {
    const parser = new SessiondFrameAccumulator()
    const first = encodeFrame({ n: 1 }), second = encodeFrame({ n: 2 })
    expect(parser.push(first.subarray(0, 2))).toEqual([])
    expect(parser.residualBytes).toBe(2)
    expect(parser.push(Buffer.concat([first.subarray(2), second]))).toEqual([{ n: 1 }, { n: 2 }])
    expect(parser.residualBytes).toBe(0)
  })

  test("accepts a max-size valid frame coalesced with a small frame", () => {
    const parser = new SessiondFrameAccumulator()
    const large = exactLimitFrame({ id: "large", ok: true })
    const small = encodeFrame({ id: "small", ok: true })
    expect(large.readUInt32BE(0)).toBe(SESSIOND_MAX_FRAME_BYTES)
    const messages = parser.push(Buffer.concat([large, small])) as Array<{ id: string; padding?: string }>
    expect(messages.map(message => message.id)).toEqual(["large", "small"])
    expect(messages[0]!.padding).toHaveLength(SESSIOND_MAX_FRAME_BYTES - Buffer.byteLength(JSON.stringify({ id: "large", ok: true, padding: "" })))
    expect(parser.residualBytes).toBe(0)
  })

  test("rejects declared oversize, zero-length JSON, and malformed JSON deterministically", () => {
    const oversized = Buffer.alloc(4); oversized.writeUInt32BE(SESSIOND_MAX_FRAME_BYTES + 1)
    expect(() => new SessiondFrameAccumulator().push(oversized)).toThrow(SessiondFrameError)
    expect(() => new SessiondFrameAccumulator().push(Buffer.alloc(4))).toThrow("malformed")
    expect(() => new SessiondFrameAccumulator().push(Buffer.from([0, 0, 0, 1, 0xff]))).toThrow("malformed")
  })
})
