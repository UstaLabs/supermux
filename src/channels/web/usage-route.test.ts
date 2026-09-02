import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { UsageStore, setUsageStoreForTests } from "../../core/usage/store"

let channel: WebChannel | undefined
let store: UsageStore | undefined

afterEach(async () => {
  if (channel) {
    await channel.stop()
    channel = undefined
  }
  store?.dispose()
  store = undefined
  setUsageStoreForTests(null)
})

function base(): string {
  return `http://127.0.0.1:${channel!.boundPort}`
}

function mintToken(devicesFile: string): string {
  return new DeviceStore(devicesFile).mint("test-device").token
}

function makeChannel(opts: Partial<WebChannelOpts> = {}): { channel: WebChannel; devicesFile: string } {
  const dir = mkdtempSync(join(tmpdir(), "mux-usage-route-"))
  const devicesFile = join(dir, "devices.json")
  const full: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    ...opts,
  }
  return { channel: new WebChannel(full), devicesFile }
}

function hungStore(): UsageStore {
  const dir = mkdtempSync(join(tmpdir(), "mux-usage-store-"))
  const never = new Promise<null>(() => {})
  return new UsageStore({
    filePath: join(dir, "usage-snapshot.json"),
    fetchers: {
      claude: () => never,
      cursor: () => never,
    },
    localReaders: {},
  })
}

test("GET /usage returns the snapshot without awaiting a hung fetcher", async () => {
  store = hungStore()
  setUsageStoreForTests(store)
  const made = makeChannel()
  channel = made.channel
  await channel.start()
  const token = mintToken(made.devicesFile)

  const started = Date.now()
  const res = await fetch(`${base()}/usage`, {
    headers: { authorization: `Bearer ${token}` },
  })
  expect(Date.now() - started).toBeLessThan(1_000)
  expect(res.status).toBe(200)
  const body = await res.json() as { refreshing: string[]; claude: unknown }
  expect(body.refreshing).toEqual(["claude", "cursor"])
  expect(body.claude).toBeNull()
})

test("GET /usage?refresh=1 forces a live refresh and still returns immediately", async () => {
  store = hungStore()
  store.apply("claude", {
    fiveHour: { used: 1, resetsAt: null, resetsAtIso: null },
    sevenDay: { used: 0, resetsAt: null, resetsAtIso: null },
    sevenDaySonnet: null,
    sevenDayFable: null,
    extraUsage: null,
  }, "live")
  setUsageStoreForTests(store)
  const made = makeChannel()
  channel = made.channel
  await channel.start()
  const token = mintToken(made.devicesFile)

  const started = Date.now()
  const res = await fetch(`${base()}/usage?refresh=1`, {
    headers: { authorization: `Bearer ${token}` },
  })
  expect(Date.now() - started).toBeLessThan(1_000)
  expect(res.status).toBe(200)
  const body = await res.json() as { refreshing: string[] }
  expect(body.refreshing).toContain("claude")
})

test("POST /usage/refresh ignores unknown providers and lists in-flight ones", async () => {
  store = hungStore()
  setUsageStoreForTests(store)
  const made = makeChannel()
  channel = made.channel
  await channel.start()
  const token = mintToken(made.devicesFile)

  const started = Date.now()
  const res = await fetch(`${base()}/usage/refresh`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({ providers: ["claude", "nope"], force: true }),
  })
  expect(Date.now() - started).toBeLessThan(1_000)
  expect(res.status).toBe(200)
  const body = await res.json() as { refreshing: string[] }
  expect(body.refreshing).toEqual(["claude"])
})
