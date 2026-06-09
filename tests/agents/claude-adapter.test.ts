import { describe, test, expect } from "bun:test"
import { ClaudeCodeAdapter } from "../../src/core/agents/claude/index"

describe("ClaudeCodeAdapter", () => {
  test("emits assistant-message when emitAssistantMessage is called", async () => {
    const a = new ClaudeCodeAdapter({
      sessionName: "s1",
      workdir: "/w",
      sendInboundSocket: async () => {},
      interruptSocket: async () => {},
    })
    const received: string[] = []
    a.on("assistant-message", (ev) => received.push(ev.text))
    a.emitAssistantMessage("hello world")
    expect(received).toEqual(["hello world"])
  })

  test("send forwards to sendInboundSocket", async () => {
    const calls: any[] = []
    const a = new ClaudeCodeAdapter({
      sessionName: "s2",
      workdir: "/w",
      sendInboundSocket: async (payload) => { calls.push(payload) },
      interruptSocket: async () => {},
    })
    await a.send("hi from user", { chat_id: "telegram:1", message_id: "5" })
    expect(calls).toEqual([{ content: "hi from user", meta: { chat_id: "telegram:1", message_id: "5" } }])
  })

  test("kind is 'claude'", () => {
    const a = new ClaudeCodeAdapter({ sessionName: "x", workdir: "/", sendInboundSocket: async () => {}, interruptSocket: async () => {} })
    expect(a.kind).toBe("claude")
  })

  test("interrupt forwards to interruptSocket", async () => {
    let calls = 0
    const a = new ClaudeCodeAdapter({
      sessionName: "s3",
      workdir: "/w",
      sendInboundSocket: async () => {},
      interruptSocket: async () => { calls++ },
    })
    await a.interrupt()
    expect(calls).toBe(1)
  })
})
