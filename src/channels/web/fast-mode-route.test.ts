// POST /sessions/:id/fast-mode: body validation, the switchFastMode wiring and the queued → 202 rule.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function start(opts: Partial<WebChannelOpts>): Promise<(body: unknown) => Promise<Response>> {
  const dir = mkdtempSync(join(tmpdir(), "mux-fast-mode-"))
  const devicesFile = join(dir, "devices.json")
  channel = new WebChannel({
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    ...opts,
  })
  await channel.start()
  const token = new DeviceStore(devicesFile).mint("test-device").token
  return (body) => fetch(`http://127.0.0.1:${channel!.boundPort}/sessions/s%201/fast-mode`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify(body),
  })
}

test("POST /sessions/:id/fast-mode forwards the flag; queued answers 202", async () => {
  const calls: Array<[string, boolean]> = []
  const post = await start({
    switchFastMode: async (id, fast) => { calls.push([id, fast]); return { ok: true, status: fast ? "queued" : "applied" } },
  })
  const on = await post({ fastMode: true })
  expect(on.status).toBe(202)
  expect(await on.json()).toEqual({ ok: true, status: "queued" })
  expect((await post({ fastMode: false })).status).toBe(200)
  expect(calls).toEqual([["s 1", true], ["s 1", false]])
})

test("POST /sessions/:id/fast-mode needs a boolean and reports the manager's refusal", async () => {
  const post = await start({ switchFastMode: async () => ({ ok: false, error: "fast mode is not available on this model" }) })
  expect((await post({ fastMode: "yes" })).status).toBe(400)
  const refused = await post({ fastMode: true })
  expect(refused.status).toBe(400)
  expect(await refused.json()).toEqual({ error: "fast mode is not available on this model" })
})
