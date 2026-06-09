import { test, expect } from "bun:test"
import type { DisplayProvider, DisplayInstance, DisplayStreamInfo } from "../src/core/display/types"
import { LinuxXvfbProvider } from "../src/core/display/providers/linux-xvfb"
import { MacosScreenProvider } from "../src/core/display/providers/macos-screen"
import { DisplayManager } from "../src/core/display/manager"

test("DisplayStreamInfo shape is usable", () => {
  const info: DisplayStreamInfo = {
    id: "d1",
    sessionName: "worker",
    provider: "linux-xvfb",
    display: ":99",
    status: "running",
    createdAt: new Date(0).toISOString(),
    transport: "vnc",
  }
  expect(info.provider).toBe("linux-xvfb")
})

test("linux-xvfb reports unavailable when Xvfb missing", () => {
  const p = new LinuxXvfbProvider()
  const reason = p.unavailableReason((b) => b === "x11vnc") // Xvfb absent
  expect(reason).toContain("Xvfb")
})

test("linux-xvfb reports unavailable when x11vnc missing", () => {
  const p = new LinuxXvfbProvider()
  const reason = p.unavailableReason((b) => b === "Xvfb") // x11vnc absent
  expect(reason).toContain("x11vnc")
})

test("linux-xvfb available when both present", () => {
  const p = new LinuxXvfbProvider()
  expect(p.unavailableReason((b) => b === "Xvfb" || b === "x11vnc")).toBeNull()
})

test("macos-screen is platform-gated", () => {
  const p = new MacosScreenProvider()
  const reason = p.unavailableReason(() => true)
  if (process.platform === "darwin") expect(reason).toBeNull()
  else expect(reason).toContain("macOS")
})

function fakeProvider(name: any, vncPort: number): DisplayProvider {
  return {
    name,
    unavailableReason: () => null,
    provision: async (): Promise<DisplayInstance> => ({
      display: ":fake",
      vncPort,
      teardown: async () => {},
    }),
  }
}

test("start registers a stream and getPort resolves it", async () => {
  const added: string[] = []
  const removed: string[] = []
  const mgr = new DisplayManager({
    providers: [fakeProvider("linux-xvfb", 5999)],
    onAdded: (s) => added.push(s.id),
    onRemoved: (id) => removed.push(id),
  })
  const info = await mgr.start({ sessionDisplayName: "worker", provider: "linux-xvfb" })
  expect(info.sessionName).toBe("worker")
  expect(mgr.getPort(info.id)).toBe(5999)
  expect(added).toContain(info.id)

  await mgr.stop(info.id)
  expect(mgr.getPort(info.id)).toBeUndefined()
  expect(removed).toContain(info.id)
})

test("killAllForSession tears down only that session's streams", async () => {
  const mgr = new DisplayManager({ providers: [fakeProvider("linux-xvfb", 6001)], onAdded: () => {}, onRemoved: () => {} })
  const a = await mgr.start({ sessionDisplayName: "s1", provider: "linux-xvfb" })
  const b = await mgr.start({ sessionDisplayName: "s2", provider: "linux-xvfb" })
  await mgr.killAllForSession("s1")
  expect(mgr.getPort(a.id)).toBeUndefined()
  expect(mgr.getPort(b.id)).toBe(6001)
})

test("start throws when no provider available for platform", async () => {
  const blocked: DisplayProvider = { name: "linux-xvfb", unavailableReason: () => "Xvfb missing", provision: async () => { throw new Error("nope") } }
  const mgr = new DisplayManager({ providers: [blocked], onAdded: () => {}, onRemoved: () => {} })
  await expect(mgr.start({ sessionDisplayName: "x", provider: "linux-xvfb" })).rejects.toThrow("Xvfb missing")
})

test("DisplayStreamInfo carries a transport", () => {
  const info: DisplayStreamInfo = {
    id: "d1", sessionName: "s", provider: "scrcpy", display: "emulator-5554",
    status: "running", createdAt: new Date(0).toISOString(), transport: "h264",
  }
  expect(info.transport).toBe("h264")
})
