import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-soul-rest-${process.pid}.json`
const PORT = 18793
let ch: WebChannel
let token: string
let soul = "I am helpful."

const base = (extra: object) => ({
  port: PORT,
  devicesFile: DEV_PATH,
  publicUrl: "http://127.0.0.1:" + PORT,
  staticDir: undefined,
  getSessionsSnapshot: () => [],
  getSessionLog: () => [],
  setMute: () => {},
  onSendFromWeb: () => {},
  ...extra,
})

beforeEach(async () => {
  __resetAuthFailures()
  soul = "I am helpful."
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  const deviceStore = new DeviceStore(DEV_PATH)
  token = deviceStore.mint("d").token
  ch = new WebChannel(
    base({
      getSoul: () => soul,
      setSoul: (c: string) => { soul = c },
    }),
  )
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

const auth = () => ({ Cookie: `cmux_token=${token}`, Origin: `http://127.0.0.1:${PORT}` })

test("GET /settings/soul returns 200 with soul text", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/soul`, { headers: auth() })
  expect(res.status).toBe(200)
  const body = await res.text()
  expect(body).toBe("I am helpful.")
})

test("PUT /settings/soul updates soul and GET reflects new value", async () => {
  const putRes = await fetch(`http://127.0.0.1:${PORT}/settings/soul`, {
    method: "PUT",
    headers: auth(),
    body: "new soul",
  })
  expect(putRes.status).toBe(200)
  const putBody = await putRes.json() as any
  expect(putBody.ok).toBe(true)
  expect(soul).toBe("new soul")

  const getRes = await fetch(`http://127.0.0.1:${PORT}/settings/soul`, { headers: auth() })
  expect(getRes.status).toBe(200)
  expect(await getRes.text()).toBe("new soul")
})

test("GET /settings/soul with no auth cookie → 401", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/settings/soul`)
  expect(res.status).toBe(401)
})
