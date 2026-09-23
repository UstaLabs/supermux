import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-ws-${process.pid}.json`
const PORT = 18788
let ch: WebChannel
let token: string

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  const store = new DeviceStore(DEV_PATH)
  token = store.mint("test").token
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [{ name: "ana", workdir: "/h", mute: false, connected: true, agent: "claude" as const }],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
  })
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

function connect(token: string): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws`, { headers: { Cookie: `cmux_token=${token}` } })
    ws.onopen = () => resolve(ws)
    ws.onerror = (e) => reject(e)
    setTimeout(() => reject(new Error("ws connect timeout")), 2000)
  })
}

function connectTerm(session: string, kind: string): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(
      `ws://127.0.0.1:${PORT}/ws/term?session=${session}&kind=${kind}`,
      { headers: { Cookie: `cmux_token=${token}` } },
    )
    ws.onopen = () => resolve(ws)
    ws.onerror = (e) => reject(e)
    ws.onclose = () => reject(new Error("closed before open"))
    setTimeout(() => reject(new Error("term connect timeout")), 2000)
  })
}

function nextMessage(ws: WebSocket, timeoutMs = 1000): Promise<any> {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error("ws message timeout")), timeoutMs)
    ws.onmessage = (e) => { clearTimeout(t); resolve(JSON.parse(String(e.data))) }
  })
}

test("ws without token → close", async () => {
  await expect(connect("")).rejects.toThrow()
})

test("ws with valid token → subscribe → snapshot", async () => {
  const ws = await connect(token)
  ws.send(JSON.stringify({ type: "subscribe" }))
  const snap = await nextMessage(ws)
  expect(snap.type).toBe("snapshot")
  expect(snap.sessions[0]?.name).toBe("ana")
  ws.close()
})

test("broadcastToAll fans out sessions_reordered to subscribers", async () => {
  const ws = await connect(token)
  ws.send(JSON.stringify({ type: "subscribe" }))
  await nextMessage(ws) // snapshot
  const got = nextMessage(ws)
  ch.broadcastToAll({ type: "sessions_reordered", orderedIds: ["b", "a"] })
  const frame = await got
  expect(frame).toEqual({ type: "sessions_reordered", orderedIds: ["b", "a"] })
  ws.close()
})

test("agent terminal: rejects non-claude, accepts claude and attaches with target", async () => {
  const attachCalls: any[] = []
  const focusCalls: any[] = []
  await ch.stop()
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getSessionWorkdir: (id) => (id === "claudeSess" || id === "codexSess" ? "/w" : undefined),
    getSessionTmuxTarget: async (id) => (id === "claudeSess" ? "mux:claudeSess" : undefined),
    terminalManager: {
      attach: (o: any) => { attachCalls.push(o); return { ok: true } },
      detach: () => {},
      focus: (...args: any[]) => { focusCalls.push(args); return true },
    } as any,
  })
  await ch.start()

  // non-claude (no tmux target) → upgrade rejected, no attach
  await expect(connectTerm("codexSess", "agent")).rejects.toThrow()
  expect(attachCalls.length).toBe(0)

  // claude → upgrade ok, attach called with kind:agent + resolved target
  const ws = await connectTerm("claudeSess", "agent")
  await new Promise((r) => setTimeout(r, 100))
  expect(attachCalls.length).toBe(1)
  expect(attachCalls[0].kind).toBe("agent")
  expect(attachCalls[0].agentTarget).toBe("mux:claudeSess")
  ws.send(JSON.stringify({ type: "focus", focused: true, cols: 57, rows: 29 }))
  await new Promise((r) => setTimeout(r, 25))
  // The trailing argument is THIS SOCKET's viewer id — the same one the attach
  // registered, so a focus can only ever move its own connection's claim.
  expect(focusCalls.length).toBe(1)
  expect(focusCalls[0].slice(0, 6)).toEqual(["test", "claudeSess", "agent", true, 57, 29])
  expect(focusCalls[0][6]).toBe(attachCalls[0].viewerId)
  expect(typeof attachCalls[0].viewerId).toBe("string")
  ws.close()
})

// Two tabs of ONE browser on ONE terminal used to share a viewer slot in
// TerminalManager, so the second tab's attach detached the first tab's backend
// viewer behind its back and the first tab's eventual close took the second
// tab's live viewer with it. Each socket now carries its own viewer identity.
test("two terminal sockets on one terminal get distinct viewer ids, and each detaches only itself", async () => {
  const attachCalls: any[] = []
  const detachCalls: any[] = []
  await ch.stop()
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getSessionWorkdir: () => "/w",
    terminalManager: {
      attach: (o: any) => { attachCalls.push(o); return { ok: true } },
      detach: (...args: any[]) => { detachCalls.push(args) },
    } as any,
  })
  await ch.start()

  const first = await connectTerm("ana", "scratch")
  const second = await connectTerm("ana", "scratch")
  await new Promise((r) => setTimeout(r, 100))

  expect(attachCalls.length).toBe(2)
  expect(attachCalls[0].terminalId).toBe(attachCalls[1].terminalId)
  expect(attachCalls[0].deviceName).toBe(attachCalls[1].deviceName)
  expect(attachCalls[0].viewerId).not.toBe(attachCalls[1].viewerId)

  first.close()
  await new Promise((r) => setTimeout(r, 100))
  expect(detachCalls.length).toBe(1)
  expect(detachCalls[0][3]).toBe(attachCalls[0].viewerId)
  second.close()
})

test("terminal attach rejection reports an error and closes with 1011", async () => {
  await ch.stop()
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getSessionWorkdir: () => "/w",
    terminalManager: {
      attach: async () => { throw new Error("attach exploded") },
      detach: () => {},
    } as any,
  })
  await ch.start()
  const ws = new WebSocket(
    `ws://127.0.0.1:${PORT}/ws/term?session=ana&kind=scratch`,
    { headers: { Cookie: `cmux_token=${token}` } },
  )
  const messages: any[] = []
  const opened = new Promise<void>((resolve, reject) => {
    ws.onopen = () => resolve()
    ws.onerror = event => reject(event)
  })
  ws.onmessage = event => { messages.push(JSON.parse(String(event.data))) }
  const closed = new Promise<number>(resolve => {
    ws.onclose = event => { resolve(event.code) }
  })
  await opened
  expect(await closed).toBe(1011)
  expect(messages).toEqual([{ type: "reset" }, { type: "error", reason: "attach exploded" }])
})

test("terminal reset precedes replay and viewer failure reconnects without a target-exit frame", async () => {
  await ch.stop()
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getSessionWorkdir: () => "/w",
    terminalManager: {
      attach: async (opts: any) => {
        await opts.onData(new TextEncoder().encode("snapshot"))
        setTimeout(() => opts.onFailure("viewer queue overflow"), 0)
        return { ok: true }
      },
      detach: () => {},
    } as any,
  })
  await ch.start()
  const frames: Array<{ type: string } | string> = []
  const ws = new WebSocket(
    `ws://127.0.0.1:${PORT}/ws/term?session=ana&kind=scratch`,
    { headers: { Cookie: `cmux_token=${token}` } },
  )
  ws.binaryType = "arraybuffer"
  ws.onmessage = event => {
    if (event.data instanceof ArrayBuffer) frames.push(new TextDecoder().decode(event.data))
    else frames.push(JSON.parse(String(event.data)))
  }
  const closed = new Promise<number>(resolve => { ws.onclose = event => resolve(event.code) })
  expect(await closed).toBe(1011)
  expect(frames).toEqual([{ type: "reset" }, "snapshot"])
  expect(frames).not.toContainEqual(expect.objectContaining({ type: "exit" }))
})

// ── revision 2 ─────────────────────────────────────────────────────────────

function collectTermFrames(ws: WebSocket, into: Array<any>) {
  ws.binaryType = "arraybuffer"
  ws.onmessage = event => {
    if (event.data instanceof ArrayBuffer) into.push(new TextDecoder().decode(event.data))
    else into.push(JSON.parse(String(event.data)))
  }
}

test("revision 2: the only reset is the backend's, and it arrives after ready", async () => {
  let drive: ((o: any) => Promise<void>) | undefined
  await ch.stop()
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getSessionWorkdir: () => "/w",
    terminalManager: {
      attach: async (o: any) => {
        drive = async (opts: any) => {
          await opts.onEvent({ type: "reset", epoch: "e1" })
          await opts.onEvent({ type: "replay-start", epoch: "e1" })
          await opts.onEvent({ type: "output", bytes: new TextEncoder().encode("history") })
          await opts.onEvent({ type: "replay-end", epoch: "e1" })
          await opts.onEvent({ type: "owner", enabled: true })
          await opts.onEvent({ type: "output", bytes: new TextEncoder().encode("live") })
        }
        await drive(o)
        return { ok: true }
      },
      detach: () => {},
      resize: () => true,
      focus: () => true,
    } as any,
  })
  await ch.start()
  const frames: any[] = []
  const ws = new WebSocket(
    `ws://127.0.0.1:${PORT}/ws/term?session=ana&kind=scratch&terminalProtocol=2`,
    { headers: { Cookie: `cmux_token=${token}` } },
  )
  collectTermFrames(ws, frames)
  await new Promise<void>((resolve, reject) => { ws.onopen = () => resolve(); ws.onerror = reject })
  await new Promise((r) => setTimeout(r, 200))
  expect(frames[0]).toEqual({ type: "ready", version: 2, epoch: expect.any(String), replyOwner: false, ownerGeneration: 0 })
  expect(frames.slice(1)).toEqual([
    { type: "reset", epoch: "e1" },
    { type: "replay-start", epoch: "e1" },
    "history",
    { type: "replay-end", epoch: "e1" },
    { type: "owner", epoch: "e1", enabled: true, ownerGeneration: 1 },
    "live",
  ])
  expect(drive).toBeDefined()
  ws.close()
})

test("revision 2: a lost helper is a failure frame, never a fabricated exit", async () => {
  await ch.stop()
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getSessionWorkdir: () => "/w",
    terminalManager: {
      attach: async () => ({ ok: false, error: "zmx helper exited before the attach completed", code: "backend-unavailable", recoverable: true }),
      detach: () => {},
    } as any,
  })
  await ch.start()
  const frames: any[] = []
  const ws = new WebSocket(
    `ws://127.0.0.1:${PORT}/ws/term?session=ana&kind=scratch&terminalProtocol=2`,
    { headers: { Cookie: `cmux_token=${token}` } },
  )
  collectTermFrames(ws, frames)
  await new Promise<number>(resolve => { ws.onclose = event => resolve(event.code) })
  expect(frames[0].type).toBe("ready")
  expect(frames[1]).toEqual({
    type: "failure",
    code: "backend-unavailable",
    recoverable: true,
    message: "zmx helper exited before the attach completed",
  })
  expect(frames.some((f: any) => f?.type === "exit")).toBe(false)
})

test("an unsupported protocol revision is refused by name and closed", async () => {
  await ch.stop()
  // No terminalManager at all: the refusal happens before anything is attached.
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getSessionWorkdir: () => "/w",
  })
  await ch.start()
  const frames: any[] = []
  const ws = new WebSocket(
    `ws://127.0.0.1:${PORT}/ws/term?session=ana&kind=scratch&terminalProtocol=7`,
    { headers: { Cookie: `cmux_token=${token}` } },
  )
  collectTermFrames(ws, frames)
  await new Promise<number>(resolve => { ws.onclose = event => resolve(event.code) })
  expect(frames).toEqual([{
    type: "failure",
    code: "protocol-unsupported",
    recoverable: false,
    message: `terminal protocol "7" is not supported; this broker speaks revision 2`,
  }])
})

test("revision 2: a reply reaches the pty only while owning, past replay-end, on the live epoch", async () => {
  const replies: Uint8Array[] = []
  const writes: Uint8Array[] = []
  let events: ((e: any) => Promise<void>) | undefined
  await ch.stop()
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getSessionWorkdir: () => "/w",
    terminalManager: {
      attach: async (o: any) => {
        events = o.onEvent
        await o.onEvent({ type: "reset", epoch: "e1" })
        await o.onEvent({ type: "replay-start", epoch: "e1" })
        return { ok: true }
      },
      detach: () => {},
      write: (_d: string, _s: string, _t: string, data: Uint8Array) => { writes.push(data); return true },
      reply: (_d: string, _s: string, _t: string, data: Uint8Array) => { replies.push(data); return true },
    } as any,
  })
  await ch.start()
  const frames: any[] = []
  const ws = new WebSocket(
    `ws://127.0.0.1:${PORT}/ws/term?session=ana&kind=scratch&terminalProtocol=2`,
    { headers: { Cookie: `cmux_token=${token}` } },
  )
  collectTermFrames(ws, frames)
  await new Promise<void>((resolve, reject) => { ws.onopen = () => resolve(); ws.onerror = reject })
  await new Promise((r) => setTimeout(r, 150))

  const answer = { type: "reply", epoch: "e1", ownerGeneration: 1, data: "G1syNDsxUg==" }
  // Still inside the replay, and not the owner: an answer to history.
  ws.send(JSON.stringify(answer))
  await new Promise((r) => setTimeout(r, 50))
  expect(replies).toEqual([])

  await events!({ type: "replay-end", epoch: "e1" })
  await events!({ type: "owner", enabled: true })
  ws.send(JSON.stringify({ ...answer, epoch: "e-gone" }))   // stale epoch
  ws.send(JSON.stringify({ ...answer, ownerGeneration: 0 })) // stale lease
  await new Promise((r) => setTimeout(r, 50))
  expect(replies).toEqual([])

  ws.send(JSON.stringify(answer))
  // Typing is a binary frame and takes the other path entirely.
  ws.send(new TextEncoder().encode("ls\r"))
  await new Promise((r) => setTimeout(r, 100))
  expect(replies.map(r => new TextDecoder().decode(r))).toEqual(["\u001b[24;1R"])
  expect(writes.map(w => new TextDecoder().decode(w))).toEqual(["ls\r"])
  ws.close()
})

test("ws send frame triggers onSendFromWeb callback", async () => {
  const received: any[] = []
  await ch.stop()
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: (msg) => received.push(msg),
  })
  await ch.start()
  const ws = await connect(token)
  ws.send(JSON.stringify({ type: "send", session: "ana", op: "reply", args: { text: "hi" } }))
  // wait a tick for the handler
  await new Promise((r) => setTimeout(r, 100))
  expect(received.length).toBe(1)
  expect(received[0].text).toBe("hi")
  expect(received[0].chat_id).toBe("web") // single logical web channel
  expect(received[0].user_id).toBe(received[0].user) // device identity preserved
  expect(received[0].target_session_id).toBe("ana")
  ws.close()
})

// The revision-1 branch parsed `cols`/`rows` with `typeof frame.cols === "number"`
// and handed whatever came through to the backend — while the revision-2 decoder
// beside it refused anything outside 0..5000. A bound one of two doors enforces
// is not a bound, and the geometry behind it is applied to a pty and to a
// terminal model that allocates per cell.
test("legacy resize/focus geometry is bounded, exactly like revision 2's", async () => {
  const resizeCalls: any[] = []
  const focusCalls: any[] = []
  await ch.stop()
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getSessionWorkdir: () => "/w",
    terminalManager: {
      attach: () => ({ ok: true }),
      detach: () => {},
      resize: (...args: any[]) => { resizeCalls.push(args.slice(3, 5)); return true },
      focus: (...args: any[]) => { focusCalls.push(args.slice(4, 6)); return true },
    } as any,
  })
  await ch.start()

  const ws = await connectTerm("ana", "scratch")
  await new Promise((r) => setTimeout(r, 50))
  for (const frame of [
    { type: "resize", cols: 1e9, rows: 1e9 },
    { type: "resize", cols: 5001, rows: 24 },
    { type: "resize", cols: 80, rows: 5001 },
    { type: "resize", cols: 80.5, rows: 24 },      // not an integer
    { type: "resize", cols: -1, rows: 24 },
    { type: "resize", cols: "80", rows: 24 },      // the NaN this used to forward
  ]) ws.send(JSON.stringify(frame))
  // ...and the two that ARE geometry: the inclusive bound, and an ordinary size.
  ws.send(JSON.stringify({ type: "resize", cols: 5000, rows: 5000 }))
  ws.send(JSON.stringify({ type: "resize", cols: 80, rows: 24 }))
  ws.send(JSON.stringify({ type: "focus", focused: true, cols: 1e9, rows: 1e9 }))
  await new Promise((r) => setTimeout(r, 100))

  expect(resizeCalls).toEqual([[5000, 5000], [80, 24]])
  // A focus is still a focus with an unusable geometry — it just carries none.
  expect(focusCalls).toEqual([[undefined, undefined]])
  ws.close()
})
