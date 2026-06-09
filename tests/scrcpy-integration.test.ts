import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"
import { DisplayManager } from "../src/core/display/manager"
import { LinuxXvfbProvider } from "../src/core/display/providers/linux-xvfb"
import { MacosScreenProvider } from "../src/core/display/providers/macos-screen"

function hasBin(b: string): boolean { return Bun.spawnSync(["which", b], { stdout: "ignore", stderr: "ignore" }).exitCode === 0 }
function deviceAttached(): boolean {
  if (!hasBin("adb")) return false
  const r = Bun.spawnSync(["adb", "devices"], { stdout: "pipe" })
  return new TextDecoder().decode(r.stdout).split("\n").slice(1).some((l) => l.trim().endsWith("\tdevice"))
}
const canRun = process.platform === "linux" && hasBin("adb") && hasBin("scrcpy") && deviceAttached()

const DEV_PATH = `/tmp/devices-scrcpy-e2e-${process.pid}.json`
const PORT = 18844
let ch: WebChannel, mgr: DisplayManager, token = ""

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  token = new DeviceStore(DEV_PATH).mint("e2e").token
  mgr = new DisplayManager({ providers: [new LinuxXvfbProvider(), new MacosScreenProvider()], onAdded: () => {}, onRemoved: () => {} })
  ch = new WebChannel({
    port: PORT, devicesFile: DEV_PATH, publicUrl: `http://localhost:${PORT}`,
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    getScrcpy: (id: string) => mgr.getScrcpy(id),
  } as any)
  await ch.start()
})
afterEach(async () => { await mgr.stopAll(); await ch.stop(); if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH) })

test.skipIf(!canRun)("streams a real device through /ws/scrcpy", async () => {
  const serial = (() => {
    const r = Bun.spawnSync(["adb", "devices"], { stdout: "pipe" })
    return new TextDecoder().decode(r.stdout).split("\n").slice(1).map((l) => l.trim()).find((l) => l.endsWith("\tdevice"))!.split("\t")[0]!
  })()
  const info = await mgr.start({ sessionName: "e2e", provider: "scrcpy", device: serial } as any)
  expect(info.transport).toBe("h264")

  const ws = new WebSocket(`ws://localhost:${PORT}/ws/scrcpy?id=${encodeURIComponent(info.id)}`, { headers: { Cookie: `cmux_token=${token}` } })
  ws.binaryType = "arraybuffer"
  const result = await new Promise<{ init: any; gotKeyframe: boolean }>((res, rej) => {
    let init: any = null
    ws.onmessage = (e) => {
      if (typeof e.data === "string") { init = JSON.parse(e.data) }
      else { const u8 = new Uint8Array(e.data as ArrayBuffer); if ((u8[0]! & 0x01) === 1 && u8.length > 100) res({ init, gotKeyframe: true }) }
    }
    ws.onerror = () => rej(new Error("ws error"))
    setTimeout(() => rej(new Error("timeout: " + JSON.stringify({ init }))), 12000)
  })
  expect(result.init.type).toBe("init")
  expect(result.init.width).toBeGreaterThan(0)
  expect(result.gotKeyframe).toBe(true)
  ws.close()
}, 20000)
