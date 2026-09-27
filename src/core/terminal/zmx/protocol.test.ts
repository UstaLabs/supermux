import { describe, expect, test } from "bun:test"
import {
  FRAME_HEADER_BYTES,
  FrameDecoder,
  HELPER_PROTOCOL_VERSION,
  HelperProtocolError,
  MAX_FRAME_PAYLOAD,
  PENDING_MAX,
  TAG_CONTROL,
  TAG_INPUT,
  TAG_OUTPUT,
  TAG_REPLY,
  encodeControl,
  encodeFrame,
  encodeInput,
  encodeReply,
  outputChunks,
  type DecodedFrame,
} from "./protocol"

const bytes = (...values: number[]) => Uint8Array.from(values)

/** A frame built by hand, so the tests do not merely agree with the encoder. */
function handFrame(tag: number, payload: Uint8Array | string): Uint8Array {
  const body = typeof payload === "string" ? new TextEncoder().encode(payload) : payload
  const out = new Uint8Array(FRAME_HEADER_BYTES + body.length)
  out[0] = tag
  new DataView(out.buffer).setUint32(1, body.length, true) // u32 LE
  out.set(body, FRAME_HEADER_BYTES)
  return out
}

const concat = (...parts: Uint8Array[]) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let at = 0
  for (const p of parts) { out.set(p, at); at += p.length }
  return out
}

const decodeAll = (input: Uint8Array): DecodedFrame[] => {
  const decoder = new FrameDecoder()
  const frames = decoder.push(input)
  decoder.end()
  return frames
}

const throwsProtocol = (fn: () => unknown, reason?: string): boolean => {
  try {
    fn()
  } catch (error) {
    if (!(error instanceof HelperProtocolError)) return false
    return reason === undefined || error.reason === reason
  }
  return false
}

describe("helper frame envelope", () => {
  test("is exactly one tag byte plus a four-byte little-endian length", () => {
    expect(FRAME_HEADER_BYTES).toBe(5)
    const framed = encodeInput(bytes(0x61, 0x62, 0x63))
    expect(framed.length).toBe(FRAME_HEADER_BYTES + 3)
    expect(framed[0]).toBe(TAG_INPUT)
    expect(Array.from(framed.slice(1, 5))).toEqual([3, 0, 0, 0])
    expect(Array.from(framed.slice(5))).toEqual([0x61, 0x62, 0x63])
  })

  test("writes a multi-byte length little-endian, not big-endian", () => {
    const framed = encodeFrame(TAG_OUTPUT, new Uint8Array(0x0201))
    expect(Array.from(framed.slice(1, 5))).toEqual([0x01, 0x02, 0x00, 0x00])
  })

  test("the encoder refuses a payload over the 64 KiB maximum", () => {
    expect(MAX_FRAME_PAYLOAD).toBe(64 * 1024)
    expect(() => encodeFrame(TAG_OUTPUT, new Uint8Array(MAX_FRAME_PAYLOAD + 1))).toThrow()
    expect(encodeFrame(TAG_OUTPUT, new Uint8Array(MAX_FRAME_PAYLOAD)).length)
      .toBe(FRAME_HEADER_BYTES + MAX_FRAME_PAYLOAD)
  })
})

describe("FrameDecoder.push", () => {
  test("decodes one control frame into a parsed message", () => {
    const frames = decodeAll(handFrame(TAG_CONTROL, JSON.stringify({ v: 1, ev: "ok", id: 7 })))
    expect(frames.length).toBe(1)
    expect(frames[0]).toEqual({ type: "control", message: { v: 1, ev: "ok", id: 7 } })
  })

  test("decodes byte frames for each of the three data tags", () => {
    const frames = decodeAll(concat(
      handFrame(TAG_OUTPUT, bytes(1, 2)),
      handFrame(TAG_INPUT, bytes(3)),
      handFrame(TAG_REPLY, bytes(4, 5, 6)),
    ))
    expect(frames.map(f => f.type)).toEqual(["output", "input", "reply"])
    expect(Array.from((frames[0] as { bytes: Uint8Array }).bytes)).toEqual([1, 2])
    expect(Array.from((frames[2] as { bytes: Uint8Array }).bytes)).toEqual([4, 5, 6])
  })

  // The property the whole class exists for: the helper's stdout arrives in
  // pipe-sized lumps that have nothing to do with frame boundaries.
  test("every split point of two concatenated frames decodes identically", () => {
    const stream = concat(
      encodeControl({ v: HELPER_PROTOCOL_VERSION, ev: "replay-start", epoch: "42" }),
      handFrame(TAG_OUTPUT, bytes(0, 255, 10, 13, 27)),
    )
    const whole = decodeAll(stream)
    expect(whole.length).toBe(2)

    for (let split = 0; split <= stream.length; split++) {
      const decoder = new FrameDecoder()
      const frames = [
        ...decoder.push(stream.slice(0, split)),
        ...decoder.push(stream.slice(split)),
      ]
      decoder.end()
      expect(frames).toEqual(whole)
    }
  })

  test("a frame fed one byte at a time decodes identically", () => {
    const stream = concat(
      handFrame(TAG_OUTPUT, "hello"),
      handFrame(TAG_CONTROL, JSON.stringify({ v: 1, ev: "exit", code: 0 })),
      handFrame(TAG_REPLY, bytes(0x1b, 0x5b, 0x63)),
    )
    const decoder = new FrameDecoder()
    const frames: DecodedFrame[] = []
    for (const byte of stream) frames.push(...decoder.push(Uint8Array.of(byte)))
    decoder.end()
    expect(frames).toEqual(decodeAll(stream))
    expect(frames.length).toBe(3)
  })

  test("a multibyte JSON string survives a split inside its UTF-8 sequence", () => {
    const message = { v: 1, ev: "ok", id: 1, result: { name: "türkçe 日本語 🙂🙃", path: "/tmp/ünïcode" } }
    const frame = handFrame(TAG_CONTROL, JSON.stringify(message))
    for (let split = 0; split <= frame.length; split++) {
      const decoder = new FrameDecoder()
      const frames = [...decoder.push(frame.slice(0, split)), ...decoder.push(frame.slice(split))]
      decoder.end()
      expect(frames).toEqual([{ type: "control", message }])
    }
  })

  test("raw bytes are never decoded as text", () => {
    // Lone continuation bytes: valid pty output, invalid UTF-8. A decoder that
    // round-tripped through a string would replace them with U+FFFD.
    const payload = bytes(0x80, 0xff, 0xfe, 0xc3, 0x28)
    const frames = decodeAll(handFrame(TAG_OUTPUT, payload))
    expect(Array.from((frames[0] as { bytes: Uint8Array }).bytes)).toEqual(Array.from(payload))
  })

  test("a zero-length data frame is a frame, not a no-op", () => {
    const frames = decodeAll(concat(
      handFrame(TAG_OUTPUT, new Uint8Array(0)),
      handFrame(TAG_INPUT, new Uint8Array(0)),
      handFrame(TAG_OUTPUT, bytes(9)),
    ))
    expect(frames.length).toBe(3)
    expect(frames[0]).toEqual({ type: "output", bytes: new Uint8Array(0) })
    expect(frames[1]).toEqual({ type: "input", bytes: new Uint8Array(0) })
  })

  test("a zero-length frame split between header and payload still emits", () => {
    const frame = handFrame(TAG_OUTPUT, new Uint8Array(0))
    const decoder = new FrameDecoder()
    expect(decoder.push(frame.slice(0, 4))).toEqual([])
    expect(decoder.push(frame.slice(4))).toEqual([{ type: "output", bytes: new Uint8Array(0) }])
    decoder.end()
  })

  test("pushing nothing yields nothing and keeps the decoder usable", () => {
    const decoder = new FrameDecoder()
    expect(decoder.push(new Uint8Array(0))).toEqual([])
    expect(decoder.push(handFrame(TAG_OUTPUT, bytes(1)))).toEqual([{ type: "output", bytes: bytes(1) }])
    decoder.end()
  })

  test("a maximum-size payload is accepted whole", () => {
    const payload = new Uint8Array(MAX_FRAME_PAYLOAD).fill(0xab)
    const frames = decodeAll(handFrame(TAG_OUTPUT, payload))
    expect((frames[0] as { bytes: Uint8Array }).bytes.length).toBe(MAX_FRAME_PAYLOAD)
  })
})

describe("FrameDecoder rejects malformed input", () => {
  test("an unknown tag byte", () => {
    for (const tag of [0, 5, 99, 255]) {
      expect(throwsProtocol(() => decodeAll(handFrame(tag, bytes(1))), "tag")).toBe(true)
    }
  })

  test("a length over the maximum payload, without buffering it", () => {
    const header = new Uint8Array(FRAME_HEADER_BYTES)
    header[0] = TAG_OUTPUT
    new DataView(header.buffer).setUint32(1, MAX_FRAME_PAYLOAD + 1, true)
    expect(throwsProtocol(() => decodeAll(header), "length")).toBe(true)

    // The pathological case: a header claiming 4 GiB. The decoder must reject
    // it on the header alone rather than waiting for bytes that will never
    // come (and never sizing a buffer from it).
    new DataView(header.buffer).setUint32(1, 0xffffffff, true)
    const decoder = new FrameDecoder()
    expect(throwsProtocol(() => decoder.push(header), "length")).toBe(true)
  })

  test("a control frame that is not JSON", () => {
    expect(throwsProtocol(() => decodeAll(handFrame(TAG_CONTROL, "{not json")), "json")).toBe(true)
    expect(throwsProtocol(() => decodeAll(handFrame(TAG_CONTROL, "")), "json")).toBe(true)
  })

  test("a control frame that is JSON but not an object", () => {
    for (const body of ["42", '"hi"', "null", "[1,2]", "true"]) {
      expect(throwsProtocol(() => decodeAll(handFrame(TAG_CONTROL, body)), "json")).toBe(true)
    }
  })

  test("a control frame carrying a protocol version we do not speak", () => {
    for (const v of [0, 2, 99, "1", null]) {
      const body = JSON.stringify({ v, ev: "ok" })
      expect(throwsProtocol(() => decodeAll(handFrame(TAG_CONTROL, body)), "version")).toBe(true)
    }
    // ...and one with no version at all.
    expect(throwsProtocol(() => decodeAll(handFrame(TAG_CONTROL, '{"ev":"ok"}')), "version")).toBe(true)
  })

  test("EOF in the middle of a frame is an error, not a silent truncation", () => {
    const frame = handFrame(TAG_OUTPUT, "abcdef")
    for (let cut = 1; cut < frame.length; cut++) {
      const decoder = new FrameDecoder()
      decoder.push(frame.slice(0, cut))
      expect(throwsProtocol(() => decoder.end(), "truncated")).toBe(true)
    }
  })

  test("EOF on a frame boundary is clean", () => {
    const decoder = new FrameDecoder()
    decoder.push(handFrame(TAG_OUTPUT, "abc"))
    expect(() => decoder.end()).not.toThrow()
  })

  test("frames before the bad one are still returned by the throwing push", () => {
    // A decoder that dropped them would lose output the helper really sent.
    const decoder = new FrameDecoder()
    const good = handFrame(TAG_OUTPUT, bytes(7))
    try {
      decoder.push(concat(good, handFrame(0x7f, bytes(1))))
      expect.unreachable()
    } catch (error) {
      expect(error).toBeInstanceOf(HelperProtocolError)
      expect((error as HelperProtocolError).decoded).toEqual([{ type: "output", bytes: bytes(7) }])
    }
  })

  test("a decoder that has thrown refuses further input", () => {
    const decoder = new FrameDecoder()
    expect(throwsProtocol(() => decoder.push(handFrame(0x7f, bytes(1))), "tag")).toBe(true)
    expect(throwsProtocol(() => decoder.push(handFrame(TAG_OUTPUT, bytes(1))), "failed")).toBe(true)
  })
})

describe("FrameDecoder keeps its pending buffer bounded", () => {
  test("a partial frame retains no more than one maximum frame", () => {
    expect(PENDING_MAX).toBe(FRAME_HEADER_BYTES + MAX_FRAME_PAYLOAD)
    const decoder = new FrameDecoder()
    const header = new Uint8Array(FRAME_HEADER_BYTES)
    header[0] = TAG_OUTPUT
    new DataView(header.buffer).setUint32(1, MAX_FRAME_PAYLOAD, true)
    decoder.push(header)
    decoder.push(new Uint8Array(MAX_FRAME_PAYLOAD - 1))
    expect(decoder.pendingBytes).toBe(PENDING_MAX - 1)
    const frames = decoder.push(new Uint8Array(1))
    expect(frames.length).toBe(1)
    expect(decoder.pendingBytes).toBe(0)
  })

  test("a long run of complete frames never grows the pending buffer", () => {
    const decoder = new FrameDecoder()
    const stream = concat(...Array.from({ length: 200 }, (_, i) => handFrame(TAG_OUTPUT, bytes(i & 0xff))))
    expect(decoder.push(stream).length).toBe(200)
    expect(decoder.pendingBytes).toBe(0)
    decoder.end()
  })
})

describe("control encoding", () => {
  test("round-trips a command through the decoder", () => {
    const command = {
      v: HELPER_PROTOCOL_VERSION,
      id: 3,
      op: "create" as const,
      name: "muxterm_77_6d61696e",
      socket: "/run/user/1000/supermux/zmx/mx0123456789abcdef0123",
      argv: ["/bin/bash", "-l"],
      env: { TERM: "xterm-256color", LANG: "tr_TR.UTF-8" },
      cwd: "/home/ünïcode",
      cols: 100,
      rows: 30,
    }
    expect(decodeAll(encodeControl(command))).toEqual([{ type: "control", message: command }])
  })

  test("a control message too large for one frame is an error, never split", () => {
    // Control messages are bounded by construction; output is what gets
    // chunked. Splitting a JSON object across frames would mean the decoder
    // had to buffer unbounded text to reassemble it.
    const huge = { v: 1, op: "create", cwd: "x".repeat(MAX_FRAME_PAYLOAD) }
    expect(() => encodeControl(huge)).toThrow()
  })

  test("encodeReply and encodeInput use distinct tags", () => {
    expect(encodeReply(bytes(1))[0]).toBe(TAG_REPLY)
    expect(encodeInput(bytes(1))[0]).toBe(TAG_INPUT)
    expect(TAG_REPLY).not.toBe(TAG_INPUT)
  })
})

describe("outputChunks", () => {
  test("splits at the frame maximum and preserves order and bytes", () => {
    const source = new Uint8Array(MAX_FRAME_PAYLOAD * 2 + 17)
    for (let i = 0; i < source.length; i++) source[i] = i & 0xff
    const chunks = [...outputChunks(source)]
    expect(chunks.map(c => c.length)).toEqual([MAX_FRAME_PAYLOAD, MAX_FRAME_PAYLOAD, 17])
    expect(concat(...chunks)).toEqual(source)
  })

  test("a short buffer is one chunk and an empty one is none", () => {
    expect([...outputChunks(bytes(1, 2, 3))].length).toBe(1)
    expect([...outputChunks(new Uint8Array(0))].length).toBe(0)
  })
})
