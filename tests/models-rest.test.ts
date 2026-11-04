import { afterEach, beforeEach, expect, test } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

const DEV_PATH = `/tmp/devices-models-rest-${process.pid}.json`
// Port 0: the OS picks a free port, read back once the channel listens. A fixed one collides with
// whatever else holds it (another run, a listener a crashed test left behind) and fails for no
// reason of the code under test.
let PORT = 0
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
    port: 0,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1",
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
  PORT = channel.boundPort

  const res = await fetch(`http://127.0.0.1:${PORT}/models?agent=claude`, {
    headers: { Cookie: `cmux_token=${token}` },
  })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({
    models: [{ id: "claude-model", displayName: "Fresh model" }],
  })
  expect(refreshes).toBe(1)
})

test("GET /agents/models returns the whole catalog in one answer", async () => {
  channel = new WebChannel({
    port: 0,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getAgentModels: () => ({
      agents: [{
        kind: "codex",
        models: [{ id: "gpt-a", displayName: "GPT A" }],
        fast: false,
        reasoning: { levels: [], visible: false },
        modelReasoning: { "gpt-a": { levels: [{ id: "low" }, { id: "high" }], visible: true } },
      }],
    }),
  })
  await channel.start()
  PORT = channel.boundPort

  const res = await fetch(`http://127.0.0.1:${PORT}/agents/models`, {
    headers: { Cookie: `cmux_token=${token}` },
  })
  expect(res.status).toBe(200)
  const body = await res.json() as { agents: { kind: string; modelReasoning: Record<string, { visible: boolean }> }[] }
  expect(body.agents.map((a) => a.kind)).toEqual(["codex"])
  expect(body.agents[0]!.modelReasoning["gpt-a"]!.visible).toBe(true)
})
