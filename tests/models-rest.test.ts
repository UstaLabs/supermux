import { afterEach, beforeEach, expect, test } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-models-rest-${process.pid}.json`
const PORT = 18917
let channel: WebChannel
let token: string

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  const deviceStore = new DeviceStore(DEV_PATH)
  token = deviceStore.mint("test").token
})

afterEach(async () => {
  await channel?.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

test("GET /models retries discovery when the boot cache is empty", async () => {
  let models: { id: string; displayName: string }[] = []
  let refreshes = 0
  channel = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: `http://127.0.0.1:${PORT}`,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getModels: () => models,
    refreshModels: async (agent) => {
      refreshes++
      models = [{ id: `${agent}-model`, displayName: "Fresh model" }]
    },
  })
  await channel.start()

  const res = await fetch(`http://127.0.0.1:${PORT}/models?agent=claude`, {
    headers: { Cookie: `cmux_token=${token}` },
  })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({
    models: [{ id: "claude-model", displayName: "Fresh model" }],
  })
  expect(refreshes).toBe(1)
})
