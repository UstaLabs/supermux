// #152: POST /sessions takes what the launcher picked (`source`) and records who asked; a source the broker can't
// honour is answered 409 with what the launcher needs to offer a way out.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { SourceRefused } from "../../core/session-manager/born-asleep"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function boot(spawn: WebChannelOpts["spawnSession"]) {
  const dir = mkdtempSync(join(tmpdir(), "mux-spawn-source-"))
  const devicesFile = join(dir, "devices.json")
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost", updateChecker: null,
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    spawnSession: spawn,
  })
  await channel.start()
  const token = new DeviceStore(devicesFile).mint("laptop").token
  return (body: Record<string, unknown>) => fetch(`http://127.0.0.1:${channel!.boundPort}/sessions`, {
    method: "POST", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: JSON.stringify({ workdir: dir, ...body }),
  })
}

test("the source reaches the create path with the device that asked", async () => {
  const seen: any[] = []
  const post = await boot(async (a) => { seen.push(a); return { id: "s1", name: "a", workdir: a.workdir, agent: "claude" as any } })
  expect((await post({ source: { kind: "existing", branch: "feat/x" } })).status).toBe(200)
  expect((await post({ source: { kind: "new", base: "main", name: "ahmet/fix", auto: true } })).status).toBe(200)
  expect((await post({ source: { kind: "local", switchTo: "dev" } })).status).toBe(200)
  expect((await post({ source: { kind: "reuse", sessionId: "s-9" } })).status).toBe(200)
  expect((await post({ source: { kind: "pr", number: 123, divergedPolicy: "pr" } })).status).toBe(200)
  expect((await post({})).status).toBe(200)
  expect(seen.map((a) => a.source)).toEqual([
    { kind: "existing", branch: "feat/x" },
    { kind: "new", base: "main", name: "ahmet/fix", auto: true },
    { kind: "local", switchTo: "dev" },
    { kind: "reuse", sessionId: "s-9" },
    { kind: "pr", number: 123, divergedPolicy: "pr" },
    undefined,
  ])
  expect(JSON.parse(seen[0].createdBy)).toEqual({ kind: "device", name: "laptop" })
})

test("a source that isn't one is a 400, and nothing is created", async () => {
  let calls = 0
  const post = await boot(async (a) => { calls++; return { id: "s1", name: "a", workdir: a.workdir, agent: "claude" as any } })
  for (const source of ["existing", { kind: "existing" }, { kind: "reuse" }, { kind: "fork" }, { kind: "pr" }, { kind: "pr", number: "12" }]) {
    expect((await post({ source })).status).toBe(400)
  }
  expect(calls).toBe(0)
})

test("a refused source is a 409 that says why and who holds the branch", async () => {
  const post = await boot(async () => {
    throw new SourceRefused("held_elsewhere", "feat/x is checked out elsewhere", { branch: "feat/x", holder: { kind: "session", id: "s-2", name: "Cart" } })
  })
  const res = await post({ source: { kind: "existing", branch: "feat/x" } })
  expect(res.status).toBe(409)
  expect(await res.json()).toEqual({ code: "held_elsewhere", error: "feat/x is checked out elsewhere", branch: "feat/x", holder: { kind: "session", id: "s-2", name: "Cart" } })
})
