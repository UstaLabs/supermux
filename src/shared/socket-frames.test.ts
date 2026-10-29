import { describe, expect, test } from "bun:test"
import { parseSocketFrame } from "./socket-frames"

describe("socket frame parsing", () => {
  test("parses register frames", () => {
    const frame = parseSocketFrame({ kind: "register", workdir: "/tmp", pid: 12, display_name: "x" })
    expect(frame.kind).toBe("register")
    if (frame.kind === "register") {
      expect(frame.workdir).toBe("/tmp")
      expect(frame.pid).toBe(12)
    }
  })

  test("rejects unknown frame kinds", () => {
    expect(() => parseSocketFrame({ kind: "surprise" })).toThrow("unknown socket frame kind: surprise")
  })

  test("rejects malformed register frames", () => {
    expect(() => parseSocketFrame({ kind: "register", workdir: "/tmp", pid: "bad" })).toThrow("register.pid must be a number")
  })

  test("an orchestration op keeps its callId when it has one, and has no callId key when it has none", () => {
    expect(parseSocketFrame({ kind: "orchestration", call_id: "c1", op: { name: "delegate", args: {}, callId: "toolu_1" } }))
      .toStrictEqual({ kind: "orchestration", call_id: "c1", op: { name: "delegate", args: {}, callId: "toolu_1" } })
    expect(parseSocketFrame({ kind: "orchestration", call_id: "c2", op: { name: "delegate", args: {} } }))
      .toStrictEqual({ kind: "orchestration", call_id: "c2", op: { name: "delegate", args: {} } })
  })
})
