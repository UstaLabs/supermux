// #119: GET /pair and /pair.json enforce a pairing link's single use and 10-minute life; POST /devices mints one.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function start() {
  const dir = mkdtempSync(join(tmpdir(), "mux-pairing-routes-"))
  const devicesFile = join(dir, "devices.json")
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
  })
  await channel.start()
  const base = `http://127.0.0.1:${channel.boundPort}`
  const admin = new DeviceStore(devicesFile).mint("laptop").token
  const newCode = async () => {
    const r = await fetch(`${base}/devices`, { method: "POST", headers: { authorization: `Bearer ${admin}`, "content-type": "application/json", origin: base }, body: JSON.stringify({ name: "phone" }) })
    expect(r.status).toBe(200)
    return new URL((await r.json() as { url: string }).url).searchParams.get("t")!
  }
  return { base, newCode, admin }
}

test("/pair works once: the second visit says the code was used", async () => {
  const { base, newCode } = await start()
  const t = await newCode()
  const first = await fetch(`${base}/pair?t=${t}`, { redirect: "manual" })
  expect(first.status).toBe(302)
  expect(first.headers.get("set-cookie")).toContain(t)
  const second = await fetch(`${base}/pair?t=${t}`, { redirect: "manual" })
  expect(second.status).toBe(401)
  expect(await second.text()).toContain("This code was already used. Make a new one.")
  // The device it paired keeps working.
  expect((await fetch(`${base}/me`, { headers: { authorization: `Bearer ${t}` } })).status).toBe(200)
})

test("/pair.json works once and answers a code the app can show", async () => {
  const { base, newCode } = await start()
  const t = await newCode()
  const ok = await fetch(`${base}/pair.json?t=${t}`)
  expect(ok.status).toBe(200)
  expect((await ok.json() as { token: string }).token).toBe(t)
  const used = await fetch(`${base}/pair.json?t=${t}`)
  expect(used.status).toBe(401)
  expect(await used.json()).toEqual({ error: "This code was already used. Make a new one.", code: "pairing_used" })
})

test("New code invalidates the previous one", async () => {
  const { base, newCode } = await start()
  const old = await newCode()
  await newCode()
  const r = await fetch(`${base}/pair.json?t=${old}`)
  expect(r.status).toBe(401)
  expect((await fetch(`${base}/me`, { headers: { authorization: `Bearer ${old}` } })).status).toBe(401)
})
