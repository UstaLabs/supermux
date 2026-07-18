import { describe, test, expect } from "bun:test"
import { CodexAdapter } from "../../src/core/agents/codex/adapter"

function mockClient() {
  const requests: { method: string; params: any; resolve: (v: any) => void }[] = []
  const notifHandlers: ((n: any) => void)[] = []
  return {
    requests,
    request: (method: string, params: any) =>
      new Promise<any>((resolve) => { requests.push({ method, params, resolve }) }),
    notify: () => {},
    onNotification: (h: (n: any) => void) => { notifHandlers.push(h) },
    emit: (n: any) => { for (const h of notifHandlers) h(n) },
  }
}

describe("CodexAdapter", () => {
  test("start() calls thread/start with cwd; persists threadId via persistThreadId", async () => {
    const c = mockClient()
    let saved: string | undefined
    const a = new CodexAdapter({
      sessionName: "s1",
      workdir: "/w",
      client: c as any,
      persistThreadId: async (id) => { saved = id },
      initialThreadId: undefined,
    })
    const startP = a.start()
    // First request is the JSON-RPC initialize handshake required by codex 0.133.
    expect(c.requests[0]?.method).toBe("initialize")
    c.requests[0]!.resolve({ userAgent: "test" })
    // Second request is the thread/start, which carries the cwd.
    // Give the adapter a tick to enqueue the next request.
    await new Promise(r => setImmediate(r))
    expect(c.requests[1]?.method).toBe("thread/start")
    expect(c.requests[1]?.params.cwd).toBe("/w")
    c.requests[1]!.resolve({ thread: { id: "thr_abc", sessionId: "thr_abc" } })
    await startP
    expect(saved).toBe("thr_abc")
  })

  test("resume() uses thread/resume when initialThreadId present", async () => {
    const c = mockClient()
    const a = new CodexAdapter({
      sessionName: "s2",
      workdir: "/w",
      client: c as any,
      persistThreadId: async () => {},
      initialThreadId: "thr_xyz",
    })
    const resumeP = a.resume()
    expect(c.requests[0]?.method).toBe("initialize")
    c.requests[0]!.resolve({ userAgent: "test" })
    await new Promise(r => setImmediate(r))
    expect(c.requests[1]?.method).toBe("thread/resume")
    expect(c.requests[1]?.params.threadId).toBe("thr_xyz")
    c.requests[1]!.resolve({})
    await resumeP
  })

  // Helper: drive an adapter.start() to completion (initialize + thread/start)
  // and return after the second request resolves. Subsequent c.requests entries
  // will be turn/start, turn/steer, etc.
  async function driveStart(c: ReturnType<typeof mockClient>, a: CodexAdapter): Promise<void> {
    const p = a.start()
    // initialize
    while (!c.requests[0]) await new Promise(r => setImmediate(r))
    c.requests[0]!.resolve({ userAgent: "test" })
    // thread/start
    while (!c.requests[1]) await new Promise(r => setImmediate(r))
    c.requests[1]!.resolve({ thread: { id: "t", sessionId: "t" } })
    await p
  }

  test("send() between turns uses turn/start; emits assistant-message on item completion", async () => {
    const c = mockClient()
    const a = new CodexAdapter({
      sessionName: "s3",
      workdir: "/w",
      client: c as any,
      persistThreadId: async () => {},
      initialThreadId: undefined,
    })
    await driveStart(c, a)
    // After start: requests[0]=initialize, requests[1]=thread/start. Next is turn/start.

    const messages: string[] = []
    a.on("assistant-message", (ev) => messages.push(ev.text))

    const sendP = a.send("hello")
    while (!c.requests[2]) await new Promise(r => setImmediate(r))
    expect(c.requests[2]?.method).toBe("turn/start")
    expect(c.requests[2]?.params.input).toEqual([{ type: "text", text: "hello" }])
    c.requests[2]!.resolve({ turn: { id: "turn1" } })
    await sendP

    c.emit({ method: "item/completed", params: { item: { type: "agentMessage", text: "hi there" } } })
    expect(messages).toEqual(["hi there"])
  })

  test("web-originated send preserves chat_id on assistant message even if turn completes first", async () => {
    const c = mockClient()
    const a = new CodexAdapter({
      sessionName: "s-web",
      workdir: "/w",
      client: c as any,
      persistThreadId: async () => {},
      initialThreadId: undefined,
    })
    await driveStart(c, a)

    const messages: Array<{ text: string; chat_id?: string }> = []
    a.on("assistant-message", (ev) => messages.push({ text: ev.text, chat_id: ev.chat_id }))

    const sendP = a.send("hello from web", { chat_id: "web:phone", message_id: "m1" })
    while (!c.requests[2]) await new Promise(r => setImmediate(r))
    c.requests[2]!.resolve({ turnId: "turn-web" })
    await sendP

    c.emit({ method: "turn/completed", params: { turnId: "turn-web" } })
    c.emit({ method: "item/completed", params: { item: { type: "agentMessage", text: "hi web" } } })

    expect(messages).toEqual([{ text: "hi web", chat_id: "web:phone" }])
  })

  test("preserves chat_id for every assistant message in a turn", async () => {
    const c = mockClient()
    const a = new CodexAdapter({
      sessionName: "s-web-multiple",
      workdir: "/w",
      client: c as any,
      persistThreadId: async () => {},
      initialThreadId: undefined,
    })
    await driveStart(c, a)

    const messages: Array<{ text: string; chat_id?: string }> = []
    a.on("assistant-message", (ev) => messages.push({ text: ev.text, chat_id: ev.chat_id }))

    const sendP = a.send("do work", { chat_id: "web", message_id: "m1" })
    while (!c.requests[2]) await new Promise(r => setImmediate(r))
    c.requests[2]!.resolve({ turnId: "turn-web-multiple" })
    await sendP

    c.emit({ method: "item/completed", params: { item: { type: "agentMessage", text: "working on it" } } })
    c.emit({ method: "item/completed", params: { item: { type: "agentMessage", text: "finished" } } })

    expect(messages).toEqual([
      { text: "working on it", chat_id: "web" },
      { text: "finished", chat_id: "web" },
    ])
  })

  test("send() during turn uses turn/steer with expectedTurnId", async () => {
    const c = mockClient()
    const a = new CodexAdapter({
      sessionName: "s4",
      workdir: "/w",
      client: c as any,
      persistThreadId: async () => {},
      initialThreadId: undefined,
    })
    await driveStart(c, a)

    const sendP = a.send("first")
    while (!c.requests[2]) await new Promise(r => setImmediate(r))
    c.requests[2]!.resolve({ turnId: "turn1" })
    await sendP
    c.emit({ method: "turn/started", params: { turnId: "turn1" } })

    const send2P = a.send("interject")
    while (!c.requests[3]) await new Promise(r => setImmediate(r))
    expect(c.requests[3]?.method).toBe("turn/steer")
    expect(c.requests[3]?.params.expectedTurnId).toBe("turn1")
    c.requests[3]!.resolve({})
    await send2P
  })

  test("send() with image attachment includes a localImage input item with the resolved path", async () => {
    const c = mockClient()
    const resolved: string[] = []
    const a = new CodexAdapter({
      sessionName: "s-img",
      workdir: "/w",
      client: c as any,
      persistThreadId: async () => {},
      initialThreadId: undefined,
      resolveAttachment: async (id) => { resolved.push(id); return "/files/pic.png" },
    })
    await driveStart(c, a)

    const sendP = a.send("look at this", { chat_id: "web:p", attachment_file_id: "abc123", attachment_kind: "photo", attachment_mime: "image/png" })
    while (!c.requests[2]) await new Promise(r => setImmediate(r))
    expect(resolved).toEqual(["abc123"])
    expect(c.requests[2]?.params.input).toEqual([
      { type: "localImage", path: "/files/pic.png" },
      { type: "text", text: "look at this" },
    ])
    c.requests[2]!.resolve({ turnId: "t" })
    await sendP
  })

  test("send() with image attachment and no caption sends only the localImage item", async () => {
    const c = mockClient()
    const a = new CodexAdapter({
      sessionName: "s-img2",
      workdir: "/w",
      client: c as any,
      persistThreadId: async () => {},
      initialThreadId: undefined,
      resolveAttachment: async () => "/files/pic.jpg",
    })
    await driveStart(c, a)

    const sendP = a.send("", { attachment_file_id: "f1", attachment_mime: "image/jpeg" })
    while (!c.requests[2]) await new Promise(r => setImmediate(r))
    expect(c.requests[2]?.params.input).toEqual([{ type: "localImage", path: "/files/pic.jpg" }])
    c.requests[2]!.resolve({ turnId: "t" })
    await sendP
  })

  test("send() with non-image attachment folds the local path into the text input", async () => {
    const c = mockClient()
    const a = new CodexAdapter({
      sessionName: "s-doc",
      workdir: "/w",
      client: c as any,
      persistThreadId: async () => {},
      initialThreadId: undefined,
      resolveAttachment: async () => "/files/report.pdf",
    })
    await driveStart(c, a)

    const sendP = a.send("see attached", { attachment_file_id: "f2", attachment_kind: "document", attachment_mime: "application/pdf", attachment_name: "report.pdf" })
    while (!c.requests[2]) await new Promise(r => setImmediate(r))
    const input = c.requests[2]?.params.input
    expect(input).toHaveLength(1)
    expect(input[0].type).toBe("text")
    expect(input[0].text).toContain("see attached")
    expect(input[0].text).toContain("/files/report.pdf")
    c.requests[2]!.resolve({ turnId: "t" })
    await sendP
  })

  test("send() falls back to text-only if attachment resolution fails", async () => {
    const c = mockClient()
    const a = new CodexAdapter({
      sessionName: "s-fail",
      workdir: "/w",
      client: c as any,
      persistThreadId: async () => {},
      initialThreadId: undefined,
      resolveAttachment: async () => { throw new Error("gone") },
    })
    await driveStart(c, a)

    const sendP = a.send("hi", { attachment_file_id: "f3", attachment_mime: "image/png" })
    while (!c.requests[2]) await new Promise(r => setImmediate(r))
    expect(c.requests[2]?.params.input).toEqual([{ type: "text", text: "hi" }])
    c.requests[2]!.resolve({ turnId: "t" })
    await sendP
  })

  test("interrupt() sends the active turnId required by the current protocol", async () => {
    const c = mockClient()
    const a = new CodexAdapter({
      sessionName: "s5",
      workdir: "/w",
      client: c as any,
      persistThreadId: async () => {},
      initialThreadId: undefined,
    })
    await driveStart(c, a)

    const sendP = a.send("keep working")
    while (!c.requests[2]) await new Promise(r => setImmediate(r))
    c.requests[2]!.resolve({ turn: { id: "turn-current" } })
    await sendP

    const ipP = a.interrupt()
    while (!c.requests[3]) await new Promise(r => setImmediate(r))
    expect(c.requests[3]?.method).toBe("turn/interrupt")
    expect(c.requests[3]?.params).toEqual({ threadId: "t", turnId: "turn-current" })
    c.requests[3]!.resolve({})
    await ipP
  })

  test("turn/started notification records the active turn from turn.id", async () => {
    const c = mockClient()
    const a = new CodexAdapter({
      sessionName: "s6",
      workdir: "/w",
      client: c as any,
      persistThreadId: async () => {},
      initialThreadId: undefined,
    })
    await driveStart(c, a)

    c.emit({ method: "turn/started", params: { threadId: "t", turn: { id: "turn-notified" } } })
    const ipP = a.interrupt()
    while (!c.requests[2]) await new Promise(r => setImmediate(r))
    expect(c.requests[2]?.params).toEqual({ threadId: "t", turnId: "turn-notified" })
    c.requests[2]!.resolve({})
    await ipP
  })

  test("interrupt() is a no-op between turns", async () => {
    const c = mockClient()
    const a = new CodexAdapter({
      sessionName: "s7",
      workdir: "/w",
      client: c as any,
      persistThreadId: async () => {},
      initialThreadId: undefined,
    })
    await driveStart(c, a)

    await a.interrupt()
    expect(c.requests).toHaveLength(2)
  })
})
