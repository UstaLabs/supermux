import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"
import { ViewingTracker } from "../src/core/push/viewing-tracker"
import { firePushForReply } from "../src/core/push/hook"
import type { PushSender, PushPayload } from "../src/core/push/sender"
import type { OutboundAction } from "../src/channels/channel"

const DEV_PATH = `/tmp/devices-viewing-${process.pid}.json`
const PORT = 18799

let ch: WebChannel
let tracker: ViewingTracker
let sender: PushSender
let calls: Array<{ device: string; payload: PushPayload }>
let token: string
let deviceName: string

function makeMockSender(): PushSender {
  return {
    sendToChat: async () => ({ ok: true }),
    sendToDevice: async (device: string, payload: PushPayload) => {
      calls.push({ device, payload })
      return { ok: true }
    },
  }
}

// The web fan-out as main.ts wires it: address a session, fan to all devices
// minus the present ones, skip if muted.
function fire(sessionId: string, isMuted: (id: string) => boolean) {
  const action: OutboundAction = { op: "reply", chat_id: "web", text: "hi" }
  return firePushForReply({
    sender, action, sessionName: sessionId, sessionId,
    isMuted,
    devices: () => [deviceName],
    anyPresent: (s) => tracker.isAnyPresentFor(s),
  })
}

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  const store = new DeviceStore(DEV_PATH)
  const minted = store.mint("iphone")
  token = minted.token
  deviceName = minted.name
  tracker = new ViewingTracker()
  calls = []
  sender = makeMockSender()
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [{ name: "foo", workdir: "/h", mute: false, connected: true, agent: "claude" as const }],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    viewingTracker: tracker,
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

async function sendViewingFrame(ws: WebSocket, session: string | null, visible: boolean): Promise<void> {
  ws.send(JSON.stringify({ type: "viewing", session, visible }))
  await new Promise((r) => setTimeout(r, 60))
}

test("viewing(session=foo, visible=true) suppresses push for foo", async () => {
  const ws = await connect(token)
  await sendViewingFrame(ws, "foo", true)
  await fire("foo", () => false)
  expect(calls.length).toBe(0)
  ws.close()
})

test("on the list (session=null, visible) suppresses push for any session", async () => {
  const ws = await connect(token)
  await sendViewingFrame(ws, null, true)
  await fire("foo", () => false)
  expect(calls.length).toBe(0)
  ws.close()
})

test("viewing(session=foo, visible=false) does NOT suppress push for foo", async () => {
  const ws = await connect(token)
  await sendViewingFrame(ws, "foo", false)
  await fire("foo", () => false)
  expect(calls.length).toBe(1)
  ws.close()
})

test("viewing(session=other) does NOT suppress push for foo", async () => {
  const ws = await connect(token)
  await sendViewingFrame(ws, "other", true)
  await fire("foo", () => false)
  expect(calls.length).toBe(1)
  ws.close()
})

test("mute always wins even when not viewing", async () => {
  const ws = await connect(token)
  await sendViewingFrame(ws, "other", true)
  await fire("foo", (id) => id === "foo")
  expect(calls.length).toBe(0)
  ws.close()
})

test("ws close clears the tracker entry", async () => {
  const ws = await connect(token)
  await sendViewingFrame(ws, "foo", true)
  expect(tracker.isPresentFor(deviceName, "foo")).toBe(true)
  ws.close()
  await new Promise((r) => setTimeout(r, 60))
  expect(tracker.isPresentFor(deviceName, "foo")).toBe(false)
})

test("bad viewing frame is ignored without erroring the socket", async () => {
  const ws = await connect(token)
  ws.send(JSON.stringify({ type: "viewing", session: 42, visible: true }))
  await new Promise((r) => setTimeout(r, 60))
  expect(tracker.isPresentFor(deviceName, "foo")).toBe(false)
  await sendViewingFrame(ws, "foo", true)
  expect(tracker.isPresentFor(deviceName, "foo")).toBe(true)
  ws.close()
})
