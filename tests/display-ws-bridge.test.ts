import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-display-${process.pid}.json`
const PORT = 18811

let ch: WebChannel
let echo: Bun.TCPSocketListener<undefined>
let echoPort = 0
let token = ""

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  const store = new DeviceStore(DEV_PATH)
  token = store.mint("test-device").token

  // Fake VNC server: echoes back whatever bytes it receives.
  echo = Bun.listen({ hostname: "127.0.0.1", port: 0, socket: { data(sock, data) { sock.write(data) } } })
  echoPort = echo.port

  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: `http://localhost:${PORT}`,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getDisplayPort: (id: string) => (id === "d-ok" ? echoPort : undefined),
    listDisplays: () => [],
  } as any)
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  echo.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

test("rejects unknown stream id", async () => {
  const ws = new WebSocket(`ws://localhost:${PORT}/ws/display?id=d-missing`, { headers: { Cookie: `cmux_token=${token}` } })
  const closed = await new Promise<number>((res) => {
    ws.onclose = (e) => res(e.code)
    ws.onopen = () => {}
  })
  expect(closed).not.toBe(1000)
})

test("bridges bytes to the upstream VNC socket", async () => {
  const ws = new WebSocket(`ws://localhost:${PORT}/ws/display?id=d-ok`, { headers: { Cookie: `cmux_token=${token}` } })
  ws.binaryType = "arraybuffer"
  const got = await new Promise<Uint8Array>((res, rej) => {
    ws.onopen = () => ws.send(new Uint8Array([1, 2, 3, 4]).buffer)
    ws.onmessage = (e) => res(new Uint8Array(e.data as ArrayBuffer))
    ws.onerror = () => rej(new Error("ws error"))
    setTimeout(() => rej(new Error("timeout")), 3000)
  })
  expect(Array.from(got)).toEqual([1, 2, 3, 4])
  ws.close()
})

test("rejects bad token", async () => {
  const ws = new WebSocket(`ws://localhost:${PORT}/ws/display?id=d-ok`, { headers: { Cookie: `cmux_token=bad` } })
  const closed = await new Promise<number>((res) => { ws.onclose = (e) => res(e.code); ws.onerror = () => {} })
  expect(closed).not.toBe(1000)
})

test("GET /displays returns the list", async () => {
  const res = await fetch(`http://localhost:${PORT}/displays`, { headers: { Cookie: `cmux_token=${token}` } })
  expect(res.status).toBe(200)
  const body = await res.json()
  expect(Array.isArray(body)).toBe(true)
})
