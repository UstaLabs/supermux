// POST /sessions `firstAttachments`: the launcher uploads its staged files before
// the spawn and names them here, so the broker owns the whole first turn. Same
// ownership rule as a WS `send` — only the requesting device's own web uploads.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { FileStore } from "../../core/files/store"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"

let channel: WebChannel | undefined
afterEach(async () => {
  if (channel) { await channel.stop(); channel = undefined }
})

async function boot() {
  const dir = mkdtempSync(join(tmpdir(), "mux-spawn-first-"))
  const devicesFile = join(dir, "devices.json")
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new FileStore(db, join(dir, "files"))
  const spawned: any[] = []
  const opts: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    fileStore: store,
    updateChecker: null,
    spawnSession: async (args) => { spawned.push(args); return { id: "s1", name: "a", workdir: args.workdir, agent: "claude" as any } },
  }
  channel = new WebChannel(opts)
  await channel.start()
  const devices = new DeviceStore(devicesFile)
  const mine = devices.mint("phone").token
  const theirs = devices.mint("laptop").token
  const base = `http://127.0.0.1:${channel.boundPort}`
  const upload = async (token: string) => {
    const res = await fetch(`${base}/upload`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${token}`,
        "content-type": "application/octet-stream",
        "x-mux-session": "launcher",
        "x-mux-mime": "image/png",
        "x-mux-filename": "shot.png",
      },
      body: new Uint8Array([1, 2, 3]),
    })
    return ((await res.json()) as any).file_id as string
  }
  const spawn = (token: string, body: Record<string, unknown>) =>
    fetch(`${base}/sessions`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ workdir: tmpdir(), ...body }),
    })
  return { spawned, mine, theirs, upload, spawn }
}

test("resolves the device's own uploads and hands them to the spawn with the device", async () => {
  const t = await boot()
  const fileId = await t.upload(t.mine)

  const res = await t.spawn(t.mine, { firstMessage: "look", firstAttachments: [fileId] })

  expect(res.status).toBe(200)
  expect(t.spawned[0].firstMessage).toBe("look")
  expect(t.spawned[0].device).toBe("phone")
  expect(t.spawned[0].firstAttachments).toEqual([
    expect.objectContaining({ file_id: fileId, kind: "photo", mime: "image/png", name: "shot.png", size: 3 }),
  ])
})

test("refuses another device's upload and spawns nothing", async () => {
  const t = await boot()
  const fileId = await t.upload(t.theirs)

  const res = await t.spawn(t.mine, { firstMessage: "look", firstAttachments: [fileId] })

  expect(res.status).toBe(400)
  expect(t.spawned).toHaveLength(0)
})
