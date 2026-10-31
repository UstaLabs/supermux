import { describe, expect, test } from "bun:test"
import { parseSocketFrame, type ShimTool } from "./socket-frames"

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

  test("a register frame says which MCP server the shim runs as", () => {
    expect(parseSocketFrame({ kind: "register", workdir: "/tmp", pid: 12, server: "mux-rpc" })).toMatchObject({ server: "mux-rpc" })
    expect(() => parseSocketFrame({ kind: "register", workdir: "/tmp", pid: 12, server: 7 })).toThrow("register.server must be a string")
  })

  test("a registered frame carries the broker's tools with their routes; no tools key when it sends none", () => {
    const tools: ShimTool[] = [{ name: "zap", description: "Zap", inputSchema: { type: "object", properties: {} }, route: { kind: "orchestration", op: "zap" } }]
    expect(parseSocketFrame({ kind: "registered", display_name: "n", session_id: "s", tools }))
      .toStrictEqual({ kind: "registered", display_name: "n", session_id: "s", tools })
    expect(parseSocketFrame({ kind: "registered", display_name: "n", session_id: "s" }))
      .toStrictEqual({ kind: "registered", display_name: "n", session_id: "s" })
  })

  test("rejects a registered frame whose tools are malformed", () => {
    const tool = { name: "zap", description: "Zap", inputSchema: { type: "object" }, route: { kind: "orchestration", op: "zap" } }
    const registered = (tools: unknown) => ({ kind: "registered", display_name: "n", session_id: "s", tools })
    expect(() => parseSocketFrame(registered({}))).toThrow("registered.tools must be an array")
    expect(() => parseSocketFrame(registered([{ ...tool, name: 1 }]))).toThrow("registered.tools[0].name must be a string")
    expect(() => parseSocketFrame(registered([{ ...tool, inputSchema: [] }]))).toThrow("registered.tools[0].inputSchema must be an object")
    expect(() => parseSocketFrame(registered([{ ...tool, route: { kind: "inbound", op: "zap" } }]))).toThrow("registered.tools[0].route.kind must be outbound or orchestration")
  })

  test("an orchestration op keeps its callId when it has one, and has no callId key when it has none", () => {
    expect(parseSocketFrame({ kind: "orchestration", call_id: "c1", op: { name: "delegate", args: {}, callId: "toolu_1" } }))
      .toStrictEqual({ kind: "orchestration", call_id: "c1", op: { name: "delegate", args: {}, callId: "toolu_1" } })
    expect(parseSocketFrame({ kind: "orchestration", call_id: "c2", op: { name: "delegate", args: {} } }))
      .toStrictEqual({ kind: "orchestration", call_id: "c2", op: { name: "delegate", args: {} } })
  })
})
