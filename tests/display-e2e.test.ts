import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"
import { DisplayManager } from "../src/core/display/manager"
import { LinuxXvfbProvider } from "../src/core/display/providers/linux-xvfb"

// Real end-to-end: a real Xvfb+x11vnc display, driven through the actual
// /ws/display bridge of a real WebChannel. Gated on the host having the
// binaries (Linux + Xvfb + x11vnc), otherwise skipped.
function hasBin(bin: string): boolean {
  return Bun.spawnSync(["which", bin], { stdout: "ignore", stderr: "ignore" }).exitCode === 0
}
const canRun = process.platform === "linux" && hasBin("Xvfb") && hasBin("x11vnc")

const DEV_PATH = `/tmp/devices-display-e2e-${process.pid}.json`
const PORT = 18833

let ch: WebChannel
let mgr: DisplayManager
let token = ""

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  const store = new DeviceStore(DEV_PATH)
  token = store.mint("e2e-device").token

  mgr = new DisplayManager({
    providers: [new LinuxXvfbProvider()],
    onAdded: () => {},
    onRemoved: () => {},
  })

  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: `http://localhost:${PORT}`,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getDisplayPort: (id: string) => mgr.getPort(id),
    listDisplays: () => mgr.list(),
    startDisplay: (args: any) => mgr.start(args),
    stopDisplay: (id: string) => mgr.stop(id),
  } as any)
  await ch.start()
})

afterEach(async () => {
  await mgr.stopAll()
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

test.skipIf(!canRun)("streams a real VNC display through /ws/display", async () => {
  const info = await mgr.start({ sessionDisplayName: "e2e", provider: "linux-xvfb", width: 640, height: 480 })
  expect(mgr.getPort(info.id)).toBeGreaterThan(0)

  const ws = new WebSocket(`ws://localhost:${PORT}/ws/display?id=${encodeURIComponent(info.id)}`, { headers: { Cookie: `cmux_token=${token}` } })
  ws.binaryType = "arraybuffer"
  const greeting = await new Promise<string>((res, rej) => {
    ws.onmessage = (e) => res(new TextDecoder().decode(new Uint8Array(e.data as ArrayBuffer).slice(0, 12)))
    ws.onerror = () => rej(new Error("ws error"))
    setTimeout(() => rej(new Error("timeout waiting for RFB greeting through bridge")), 8000)
  })
  // The RFB ProtocolVersion greeting proves the full path: provider →
  // manager → /ws/display auth → WS↔TCP bridge → real x11vnc.
  expect(greeting.startsWith("RFB ")).toBe(true)
  ws.close()
})
