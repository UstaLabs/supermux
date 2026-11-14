// #122 phase 4: whose dev server listens on a port (the session whose process started it), so a Browser tab's comments
// on that port's by-port preview go to that session by default.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function boot(owner: WebChannelOpts["portOwner"]) {
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-port-owner-")), "devices.json")
  const token = new DeviceStore(devicesFile).mint("t").token
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    portOwner: owner,
  })
  await channel.start()
  return (path: string) => fetch(`http://127.0.0.1:${channel!.boundPort}${path}`, { headers: { authorization: `Bearer ${token}` } })
}

test("GET /ports/:port/owner names the session whose process listens there", async () => {
  const get = await boot((port) => port === 5173 ? { sessionId: "s1", sessionName: "Checkout agent" } : null)
  const res = await get("/ports/5173/owner")
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ sessionId: "s1", sessionName: "Checkout agent" })
  expect((await get("/ports/3000/owner")).status).toBe(404)
})

test("a port that isn't one is a 400", async () => {
  const get = await boot(() => null)
  expect((await get("/ports/0/owner")).status).toBe(400)
  expect((await get("/ports/70000/owner")).status).toBe(400)
  expect((await get("/ports/abc/owner")).status).toBe(404) // not this route at all
})
