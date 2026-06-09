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
