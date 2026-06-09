import { test, expect, afterAll } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const BASE_DOMAIN = "example.test"
const PRIVATE_SUB = "private-app"
const PUBLIC_SUB = "public-app"
const PORT = 18910
const UPSTREAM_PORT = 18911
const DEV_PATH = `/tmp/devices-proxy-public-${process.pid}.json`

const upstream = Bun.serve({
  port: UPSTREAM_PORT,
  fetch() {
    return new Response("ok-from-upstream", { status: 200 })
  },
})

const store = new DeviceStore(DEV_PATH)
store.mint("test")

const ch = new WebChannel({
  port: PORT,
  devicesFile: DEV_PATH,
  publicUrl: `http://${BASE_DOMAIN}:${PORT}`,
  getSessionsSnapshot: () => [],
  getSessionLog: () => [],
  setMute: () => {},
  onSendFromWeb: () => {},
  proxyBaseDomain: BASE_DOMAIN,
  proxyLookup: (domain) => {
    if (domain === PRIVATE_SUB) return { port: UPSTREAM_PORT, sessionName: "test", isPublic: false }
    if (domain === PUBLIC_SUB) return { port: UPSTREAM_PORT, sessionName: "test", isPublic: true }
    return undefined
  },
  proxyAuth: () => false,
})

afterAll(async () => {
  await ch.stop()
  upstream.stop(true)
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

test("public vs private proxy auth", async () => {
  await ch.start()
  const privateRes = await fetch(`http://127.0.0.1:${PORT}/`, {
    headers: { Host: `${PRIVATE_SUB}.${BASE_DOMAIN}` },
  })
  expect(privateRes.status).toBe(401)

  const publicRes = await fetch(`http://127.0.0.1:${PORT}/hello`, {
    headers: { Host: `${PUBLIC_SUB}.${BASE_DOMAIN}` },
  })
  expect(publicRes.status).toBe(200)
  expect(await publicRes.text()).toBe("ok-from-upstream")
})
