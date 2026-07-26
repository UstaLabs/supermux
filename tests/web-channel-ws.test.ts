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

test("agent terminal: rejects non-claude, accepts claude and attaches with target", async () => {
  const attachCalls: any[] = []
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
  ws.close()
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
