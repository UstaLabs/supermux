import { describe, it, expect } from "bun:test"
import { decodeInput } from "./types"

describe("decodeInput", () => {
  it("classifies a printable char", () => {
    expect(decodeInput("a")).toEqual({ kind: "char", text: "a" })
  })
  it("classifies DEL (0x7f) and BS (0x08) as backspace", () => {
    expect(decodeInput("\x7f")).toEqual({ kind: "backspace" })
    expect(decodeInput("\b")).toEqual({ kind: "backspace" })
  })
  it("classifies left/right arrow escape sequences", () => {
    expect(decodeInput("\x1b[D")).toEqual({ kind: "cursorLeft" })
    expect(decodeInput("\x1b[C")).toEqual({ kind: "cursorRight" })
  })
  it("classifies Enter, Tab, Ctrl-C, and multi-char paste as opaque", () => {
    expect(decodeInput("\r")).toEqual({ kind: "opaque" })
    expect(decodeInput("\t")).toEqual({ kind: "opaque" })
    expect(decodeInput("\x03")).toEqual({ kind: "opaque" })
    expect(decodeInput("hello")).toEqual({ kind: "opaque" })
  })
})
