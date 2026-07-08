// Integration tests for the session-less GET /reasoning-levels route used by the
// New Session launcher (mirrors watch-sessions-route.test.ts). The level logic
// itself (supportedReasoningLevels) is unit-tested; this proves the route parses
// agent+model, wires the getReasoningLevels opt, and guards a bad/missing agent.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })
function base(): string { return `http://127.0.0.1:${channel!.boundPort}` }
function mintToken(devicesFile: string): string { return new DeviceStore(devicesFile).mint("test-device").token }

function makeChannel(opts: Partial<WebChannelOpts>): { channel: WebChannel; devicesFile: string } {
  const dir = mkdtempSync(join(tmpdir(), "mux-reasoning-levels-"))
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

test("GET /reasoning-levels passes agent+model through and returns the levels", async () => {
  const calls: Array<{ agent: string; model?: string }> = []
  const made = makeChannel({
    getReasoningLevels: (agent, model) => {
      calls.push({ agent, model })
      return { agent, levels: [{ id: "low" }, { id: "high" }], visible: true }
    },
  })
  channel = made.channel
  await channel.start()
  const token = mintToken(made.devicesFile)

  const res = await fetch(`${base()}/reasoning-levels?agent=codex&model=gpt-x`, {
    headers: { authorization: `Bearer ${token}` },
  })
  expect(res.status).toBe(200)
  const body = await res.json()
  expect(body).toEqual({ agent: "codex", levels: [{ id: "low" }, { id: "high" }], visible: true })
  expect(calls).toEqual([{ agent: "codex", model: "gpt-x" }])
})

test("GET /reasoning-levels omits model when not provided", async () => {
  const calls: Array<{ agent: string; model?: string }> = []
  const made = makeChannel({
    getReasoningLevels: (agent, model) => { calls.push({ agent, model }); return { agent, levels: [], visible: false } },
  })
  channel = made.channel
  await channel.start()
  const token = mintToken(made.devicesFile)

  const res = await fetch(`${base()}/reasoning-levels?agent=cursor`, {
    headers: { authorization: `Bearer ${token}` },
  })
  expect(res.status).toBe(200)
  expect(calls).toEqual([{ agent: "cursor", model: undefined }])
})

test("GET /reasoning-levels rejects a missing or unknown agent", async () => {
  const made = makeChannel({ getReasoningLevels: (agent) => ({ agent, levels: [], visible: false }) })
  channel = made.channel
  await channel.start()
  const token = mintToken(made.devicesFile)

  const missing = await fetch(`${base()}/reasoning-levels`, { headers: { authorization: `Bearer ${token}` } })
  expect(missing.status).toBe(400)
  const bogus = await fetch(`${base()}/reasoning-levels?agent=nope`, { headers: { authorization: `Bearer ${token}` } })
  expect(bogus.status).toBe(400)
})

test("GET /reasoning-levels falls back to empty when no provider is wired", async () => {
  const made = makeChannel({}) // no getReasoningLevels opt
  channel = made.channel
  await channel.start()
  const token = mintToken(made.devicesFile)

  const res = await fetch(`${base()}/reasoning-levels?agent=claude`, {
    headers: { authorization: `Bearer ${token}` },
  })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ agent: "claude", levels: [], visible: false })
})
