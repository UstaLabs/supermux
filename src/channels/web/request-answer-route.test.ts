// #143: POST /sessions/:id/requests/:requestId answers an agent's prompt over HTTP, so a phone can answer from its
// notification (Allow / Deny) without opening the app's WebSocket. Paired devices only, like every API route.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })
const base = () => `http://127.0.0.1:${channel!.boundPort}`

function boot(opts: Partial<WebChannelOpts>): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-request-answer-"))
  const devicesFile = join(dir, "devices.json")
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [{ id: "s1", name: "alpha", workdir: "/w", mute: false, connected: true, agent: "claude" }],
    getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    ...opts,
  })
  return new DeviceStore(devicesFile).mint("phone").token
}

const post = (token: string | undefined, path: string, body?: unknown) => fetch(`${base()}${path}`, {
  method: "POST",
  headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), "content-type": "application/json" },
  ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
})

test("a paired device answers a prompt; the answer goes to respondRequest as given", async () => {
  const calls: unknown[] = []
  const token = boot({ respondRequest: async (...args) => { calls.push(args); return { ok: true } } })
  await channel!.start()
  const res = await post(token, "/sessions/s1/requests/r%2F1", { answer: { optionId: "allow_once" } })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ ok: true })
  // #143 team rule: who answered travels with the answer (the device now, a user later).
  expect(calls).toEqual([["s1", "r/1", { optionId: "allow_once" }, { by: "phone" }]])
})

test("without a paired device's token nothing is answered", async () => {
  const calls: unknown[] = []
  boot({ respondRequest: async (...args) => { calls.push(args); return { ok: true } } })
  await channel!.start()
  expect((await post(undefined, "/sessions/s1/requests/r1", { answer: { optionId: "allow_once" } })).status).toBe(401)
  expect((await post("not-a-token", "/sessions/s1/requests/r1", { answer: { optionId: "allow_once" } })).status).toBe(401)
  expect(calls).toEqual([])
})

test("no answer is 400; a prompt already gone is 409 with the reason", async () => {
  const token = boot({ respondRequest: async () => ({ ok: false, error: "request not found" }) })
  await channel!.start()
  expect((await post(token, "/sessions/s1/requests/r1", {})).status).toBe(400)
  const res = await post(token, "/sessions/s1/requests/r1", { answer: { optionId: "reject_once" } })
  expect(res.status).toBe(409)
  expect(await res.json()).toEqual({ ok: false, error: "request not found" })
})
