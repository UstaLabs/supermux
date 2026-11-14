// GET /sessions/:id/context (#144): how full the context window is, what fills it, and what this chat used.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function start(opts: Partial<WebChannelOpts>): Promise<() => Promise<Response>> {
  const dir = mkdtempSync(join(tmpdir(), "mux-context-"))
  const devicesFile = join(dir, "devices.json")
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    ...opts,
  })
  await channel.start()
  const token = new DeviceStore(devicesFile).mint("test-device").token
  return () => fetch(`http://127.0.0.1:${channel!.boundPort}/sessions/s%201/context`, { headers: { authorization: `Bearer ${token}` } })
}

test("GET /sessions/:id/context returns what the broker knows for that session", async () => {
  const asked: string[] = []
  const report = { context: { used: 154000, size: 300000 }, breakdown: { used: 154000, size: 300000, categories: [] }, used: [{ name: "Read", count: 6 }] }
  const get = await start({ getSessionContext: async (id) => { asked.push(id); return report } })
  const res = await get()
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual(report)
  expect(asked).toEqual(["s 1"])
})

test("an unknown session is a 404; a broker without the wiring a 503", async () => {
  expect((await (await start({ getSessionContext: async () => undefined }))()).status).toBe(404)
  await channel!.stop(); channel = undefined
  expect((await (await start({}))()).status).toBe(503)
})
