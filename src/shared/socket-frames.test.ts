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
})
