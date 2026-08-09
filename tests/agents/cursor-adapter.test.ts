import { describe, test, expect } from "bun:test"
import { CursorAdapter } from "../../src/core/agents/cursor/adapter"

function mockRunner() {
  const runs: { args: string[]; emit: (line: string) => void; finish: () => void }[] = []
  const runner = async (args: string[], onLine: (line: string) => void, onExit: (code: number | null) => void) => {
    const handle = {
      args,
      emit: (line: string) => onLine(line),
      finish: () => onExit(0),
    }
    runs.push(handle)
    // do not auto-finish; test drives finish
  }
  return { runner, runs }
}

describe("CursorAdapter", () => {
  test("first send() runs cursor-agent without --resume; captures session_id from init", async () => {
    const { runner, runs } = mockRunner()
    let savedId: string | undefined
    const a = new CursorAdapter({
      sessionName: "s1",
      workdir: "/w",
      runner,
      persistSessionId: async (id) => { savedId = id },
      initialSessionId: undefined,
    })
    const sendP = a.send("hello")
    await new Promise(r => setImmediate(r))
    expect(runs[0]?.args).toContain("-p")
    expect(runs[0]?.args).toContain("hello")
    expect(runs[0]?.args).not.toContain("--resume")
    runs[0]!.emit(JSON.stringify({ type: "system", subtype: "init", session_id: "uuid-1" }))
    runs[0]!.emit(JSON.stringify({ type: "assistant", message: { content: [{ text: "world" }] } }))
    runs[0]!.emit(JSON.stringify({ type: "result", subtype: "ok", is_error: false }))
    runs[0]!.finish()
    await sendP
    expect(savedId).toBe("uuid-1")
  })

  test("subsequent send() uses --resume with captured session_id", async () => {
    const { runner, runs } = mockRunner()
    const a = new CursorAdapter({
      sessionName: "s2",
      workdir: "/w",
      runner,
      persistSessionId: async () => {},
      initialSessionId: "uuid-prior",
    })
    const sendP = a.send("next")
    await new Promise(r => setImmediate(r))
    expect(runs[0]?.args).toContain("--resume")
    expect(runs[0]?.args).toContain("uuid-prior")
    runs[0]!.emit(JSON.stringify({ type: "result", subtype: "ok", is_error: false }))
    runs[0]!.finish()
    await sendP
  })

  test("buffers assistant snapshots, emits only the final one on result", async () => {
    const { runner, runs } = mockRunner()
    const a = new CursorAdapter({
      sessionName: "s3", workdir: "/w", runner,
      persistSessionId: async () => {}, initialSessionId: "id",
    })
    const got: string[] = []
    a.on("assistant-message", (ev) => got.push(ev.text))
    const sendP = a.send("x")
    await new Promise(r => setImmediate(r))
    // With --stream-partial-output, each assistant event is a cumulative
    // snapshot. The adapter buffers and only emits the LAST one on result.
    runs[0]!.emit(JSON.stringify({ type: "assistant", message: { content: [{ text: "Hello" }] } }))
    runs[0]!.emit(JSON.stringify({ type: "assistant", message: { content: [{ text: "Hello world" }] } }))
    expect(got).toEqual([])  // not emitted yet — buffered
    runs[0]!.emit(JSON.stringify({ type: "result", subtype: "ok", is_error: false }))
    runs[0]!.finish()
    await sendP
    expect(got).toEqual(["Hello world"])  // only the final snapshot
  })

  test("web-originated send emits the buffered message and names no destination", async () => {
    const { runner, runs } = mockRunner()
    const a = new CursorAdapter({
      sessionName: "s-web", workdir: "/w", runner,
      persistSessionId: async () => {}, initialSessionId: "id",
    })
    const got: Array<Record<string, unknown>> = []
    a.on("assistant-message", (ev) => got.push({ ...ev }))

    // The inbound meta still carries a chat_id; the adapter must not pass it on.
    const sendP = a.send("from web", { chat_id: "web:phone", message_id: "m1" })
    await new Promise(r => setImmediate(r))
    runs[0]!.emit(JSON.stringify({ type: "assistant", message: { content: [{ text: "Hello web" }] } }))
    runs[0]!.emit(JSON.stringify({ type: "result", subtype: "ok", is_error: false }))
    runs[0]!.finish()
    await sendP

    expect(got).toEqual([{ kind: "assistant-message", text: "Hello web" }])
  })

  test("send() with attachment folds the resolved local path into the prompt", async () => {
    const { runner, runs } = mockRunner()
    const resolved: string[] = []
    const a = new CursorAdapter({
      sessionName: "s-att", workdir: "/w", runner,
      persistSessionId: async () => {}, initialSessionId: "id",
      resolveAttachment: async (fid) => { resolved.push(fid); return "/files/pic.png" },
    })
    const sendP = a.send("look", { attachment_file_id: "abc", attachment_kind: "photo", attachment_mime: "image/png", attachment_name: "pic.png" })
    await new Promise(r => setImmediate(r))
    expect(resolved).toEqual(["abc"])
    const pIdx = runs[0]!.args.indexOf("-p")
    const prompt = runs[0]!.args[pIdx + 1]!
    expect(prompt).toContain("look")
    expect(prompt).toContain("/files/pic.png")
    runs[0]!.emit(JSON.stringify({ type: "result", subtype: "ok", is_error: false }))
    runs[0]!.finish()
    await sendP
  })

  test("send() falls back to the original prompt if attachment resolution fails", async () => {
    const { runner, runs } = mockRunner()
    const a = new CursorAdapter({
      sessionName: "s-att-fail", workdir: "/w", runner,
      persistSessionId: async () => {}, initialSessionId: "id",
      resolveAttachment: async () => { throw new Error("gone") },
    })
    const sendP = a.send("hi", { attachment_file_id: "x", attachment_mime: "image/png" })
    await new Promise(r => setImmediate(r))
    const pIdx = runs[0]!.args.indexOf("-p")
    expect(runs[0]!.args[pIdx + 1]).toBe("hi")
    runs[0]!.emit(JSON.stringify({ type: "result", subtype: "ok", is_error: false }))
    runs[0]!.finish()
    await sendP
  })

  test("interrupt() aborts the active run → clean turn-complete, no error", async () => {
    // A runner that stays alive until its AbortSignal fires, then settles like
    // the real one (calls onExit + resolves). Models a killed cursor-agent.
    let sawSignal = false
    const runner = async (
      _args: string[],
      _onLine: (line: string) => void,
      onExit: (code: number | null) => void,
      signal?: AbortSignal,
    ): Promise<void> => {
      return new Promise<void>((resolve) => {
        const settle = () => { onExit(null); resolve() }
        if (signal) { sawSignal = true; signal.addEventListener("abort", settle, { once: true }) }
      })
    }
    const a = new CursorAdapter({
      sessionName: "s-int", workdir: "/w", runner,
      persistSessionId: async () => {}, initialSessionId: "id",
    })
    let turnComplete = 0
    let errored = 0
    a.on("turn-complete", () => { turnComplete++ })
    a.on("error", () => { errored++ })

    const sendP = a.send("long task")
    await new Promise(r => setImmediate(r))
    // The turn is in-flight (runner pending). Interrupt it.
    await a.interrupt()
    await sendP   // must resolve cleanly, not hang or reject

    expect(sawSignal).toBe(true)
    expect(turnComplete).toBe(1)
    expect(errored).toBe(0)
  })

  test("interrupt() is a harmless no-op when nothing is running", async () => {
    const { runner } = mockRunner()
    const a = new CursorAdapter({
      sessionName: "s-idle", workdir: "/w", runner,
      persistSessionId: async () => {}, initialSessionId: "id",
    })
    await a.interrupt()  // no active run — should not throw
  })
})
