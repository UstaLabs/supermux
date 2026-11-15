// #117: /a/<name>/ on the real WebChannel. The device COOKIE never opens an applet (in path mode the applet shares the
// app's origin, so a page holding it would hold the owner's whole API); a native client's bearer and the preview-only
// cookie do, and neither reaches the applet. X-Mux-Via names the device by a stable id, not its name.
import { afterEach, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { createHash } from "crypto"
import { WebChannel } from "./index"
import { DeviceStore } from "./device-store"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"
import { AppletService } from "../../core/applets/service"

// These run POSIX shell command lines (sh -c, $VARS, printf), signal process groups and check file modes; the
// Windows supervisor (cmd.exe /c, taskkill, the PowerShell start fingerprint) is covered by the platform layer's tests.
const posix = test.skipIf(process.platform === "win32")

const cleanups: (() => void | Promise<void>)[] = []
afterEach(async () => { while (cleanups.length) await cleanups.pop()!() })

async function boot() {
  const root = mkdtempSync(join(tmpdir(), "mux-applet-wiring-"))
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const dir = join(root, "applets", "echo")
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, "applet.json"), JSON.stringify({ title: "Echo", run: `${process.execPath} server.ts` }))
  writeFileSync(join(dir, "server.ts"), `Bun.serve({ port: Number(process.env.PORT), hostname: "127.0.0.1", fetch(req) {
    return Response.json({ via: req.headers.get("x-mux-via"), name: req.headers.get("x-mux-device-name"), cookie: req.headers.get("cookie"), authorization: req.headers.get("authorization") })
  } })`)
  const applets = new AppletService({ db, appletsDir: join(root, "applets"), stateDir: join(root, "state"), backupsDir: join(root, "backups") })
  await applets.scan()
  const devicesFile = join(root, "devices.json")
  const devices = new DeviceStore(devicesFile)
  const channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "https://app.example.test",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    applets,
  })
  await channel.start()
  cleanups.push(async () => { await channel.stop(); applets.shutdown(); for (const v of applets.list()) applets.supervisor.stop(v.row.id); rmSync(root, { recursive: true, force: true }) })
  const device = devices.mint("phone").token
  return { base: `http://127.0.0.1:${channel.boundPort}`, device, preview: devices.mintPreview(device)!, devices }
}

posix("the device cookie never opens an applet; the bearer and the preview cookie do, and neither reaches it", async () => {
  const { base, device, preview } = await boot()
  const viaId = createHash("sha256").update(device).digest("hex").slice(0, 16)

  const cookieOnly = await fetch(`${base}/a/echo/`, { headers: { cookie: `cmux_token=${device}` } })
  expect(cookieOnly.status).toBe(401)

  const viaBearer = await fetch(`${base}/a/echo/`, { headers: { authorization: `Bearer ${device}`, cookie: "theirs=1" } })
  expect(viaBearer.status).toBe(200)
  expect(await viaBearer.json()).toEqual({ via: `device:${viaId}`, name: "phone", cookie: "theirs=1", authorization: null })

  const viaPreview = await fetch(`${base}/a/echo/`, { headers: { cookie: `cmux_preview=${preview}; theirs=2` } })
  expect(viaPreview.status).toBe(200)
  expect(await viaPreview.json()).toMatchObject({ via: `device:${viaId}`, cookie: "theirs=2" })

  expect((await fetch(`${base}/a/echo`, { headers: { authorization: `Bearer ${device}` }, redirect: "manual" })).status).toBe(301)
  expect((await fetch(`${base}/a/nope/`, { headers: { authorization: `Bearer ${device}` } })).status).toBe(404)
})

posix("GET /applets lists the host's applets for a paired device and nobody else", async () => {
  const { base, device } = await boot()
  expect((await fetch(`${base}/applets`)).status).toBe(401)
  const list = await (await fetch(`${base}/applets`, { headers: { authorization: `Bearer ${device}` } })).json() as { name: string; url: string; kind: string }[]
  expect(list.map((a) => [a.name, a.url, a.kind])).toEqual([["echo", "/a/echo/", "server"]])
})
