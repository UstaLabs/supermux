// HTTP tests for the /project-catalog routes (plan 2026-09-21 "Wire contract").
// Boots a real WebChannel on an ephemeral port, wired to a real ProjectService over
// an in-memory database, and drives it over fetch with a bearer token (native-client
// style, so mutations need no Origin header) — mirroring reasoning-levels-route.test.ts.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, mkdirSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { connect } from "net"
import { WebChannel, isApiPath, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"
import { ProjectStore } from "../../core/project/store"
import { ProjectService } from "../../core/project/service"
import { ProjectImages, PROJECT_IMAGE_MAX_BYTES } from "../../core/project/images"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })
function base(): string { return `http://127.0.0.1:${channel!.boundPort}` }

async function boot(extra: Partial<WebChannelOpts> = {}) {
  const dir = mkdtempSync(join(tmpdir(), "mux-project-routes-"))
  const devicesFile = join(dir, "devices.json")
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const svc = new ProjectService(new ProjectStore(db), {
    home: "/home/u",
    managedWorktreesRoot: "/home/u/.mux/worktrees",
    images: new ProjectImages(join(dir, "project-images")),
  })
  const opts: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    listProjectCatalog: () => svc.list(),
    getProjectMembership: () => ({ w1: "p-fixed" }),
    createProject: (name) => svc.create(name),
    renameProject: (id, name) => svc.rename(id, name),
    reorderProjects: (ids) => svc.reorder(ids),
    addProjectLocation: (id, path) => svc.addLocation(id, path),
    moveProjectLocation: (locationId, projectId) => svc.moveLocation(locationId, projectId),
    setProjectImage: (id, bytes, mime) => svc.setImage(id, bytes, mime),
    clearProjectImage: (id) => svc.clearImage(id),
    projectImageFile: (id) => svc.imageFile(id),
    ...extra,
  }
  channel = new WebChannel(opts)
  await channel.start()
  const frames: any[] = []
  const orig = channel.broadcastToAll.bind(channel)
  channel.broadcastToAll = (f: object) => { frames.push(f); orig(f) }
  const token = new DeviceStore(devicesFile).mint("test-device").token
  const call = (method: string, path: string, body?: unknown, headers: Record<string, string> = {}) =>
    fetch(`${base()}${path}`, {
      method,
      headers: { authorization: `Bearer ${token}`, ...(body !== undefined && !(body instanceof Uint8Array) ? { "content-type": "application/json" } : {}), ...headers },
      body: body === undefined ? undefined : body instanceof Uint8Array ? body : JSON.stringify(body),
    })
  return { svc, frames, call, dir, token }
}

/** Sends only a request head over a raw socket and returns the response status code. */
function rawStatus(token: string, requestLine: string, headers: Record<string, string>): Promise<number> {
  return new Promise((resolve, reject) => {
    let buf = ""
    const sock = connect(channel!.boundPort, "127.0.0.1", () => {
      const head = [`${requestLine} HTTP/1.1`, "host: 127.0.0.1", `authorization: Bearer ${token}`,
        ...Object.entries(headers).map(([k, v]) => `${k}: ${v}`)].join("\r\n")
      sock.write(head + "\r\n\r\n")
    })
    sock.on("data", (d) => {
      buf += d.toString()
      const m = buf.match(/^HTTP\/1\.1 (\d{3})/)
      if (m) { sock.destroy(); resolve(Number(m[1])) }
    })
    sock.on("error", reject)
  })
}

const catalogFrames = (frames: any[]) => frames.filter((f) => f.type === "projects_changed")

test("GET /project-catalog lists projects", async () => {
  const { svc, call } = await boot()
  const p = svc.create("Alpha")
  const res = await call("GET", "/project-catalog")
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ projects: [p] })
})

test("unauthenticated requests are rejected", async () => {
  await boot()
  const res = await fetch(`${base()}/project-catalog`)
  expect(res.status).toBe(401)
})

test("unauthenticated GET /project-catalog/:id/image is rejected", async () => {
  await boot()
  const res = await fetch(`${base()}/project-catalog/x/image`)
  expect(res.status).toBe(401)
})

test("a malformed percent-encoded id → 400 bad id, not 500", async () => {
  const { call } = await boot()
  const rename = await call("PATCH", "/project-catalog/%E0", { name: "x" })
  expect(rename.status).toBe(400)
  expect(await rename.json()).toEqual({ error: "bad id" })

  const image = await call("GET", "/project-catalog/%E0/image")
  expect(image.status).toBe(400)
  expect(await image.json()).toEqual({ error: "bad id" })

  const move = await call("PATCH", "/project-catalog/locations/%E0", { projectId: "p" })
  expect(move.status).toBe(400)
  expect(await move.json()).toEqual({ error: "bad id" })
})

test("POST /project-catalog creates → 201 and broadcasts one projects_changed", async () => {
  const { call, frames } = await boot()
  const res = await call("POST", "/project-catalog", { name: "  Supermux " })
  expect(res.status).toBe(201)
  const dto = await res.json() as any
  expect(dto).toMatchObject({ name: "Supermux", sort_order: 0, locations: [] })
  const sent = catalogFrames(frames)
  expect(sent).toHaveLength(1)
  expect(sent[0]).toEqual({ type: "projects_changed", projects: [dto], projectMembership: { w1: "p-fixed" } })
})

test("POST /project-catalog without a name → 400, no broadcast", async () => {
  const { call, frames } = await boot()
  expect((await call("POST", "/project-catalog", { name: "  " })).status).toBe(400)
  expect((await call("POST", "/project-catalog", {})).status).toBe(400)
  expect(catalogFrames(frames)).toHaveLength(0)
})

test("PATCH /project-catalog/:id renames; unknown id → 404", async () => {
  const { svc, call, frames } = await boot()
  const p = svc.create("Old")
  const ok = await call("PATCH", `/project-catalog/${p.id}`, { name: "New" })
  expect(ok.status).toBe(200)
  expect((await ok.json() as any).name).toBe("New")
  expect(catalogFrames(frames)).toHaveLength(1)

  const missing = await call("PATCH", "/project-catalog/nope", { name: "X" })
  expect(missing.status).toBe(404)
  expect(catalogFrames(frames)).toHaveLength(1)
})

test("PATCH /project-catalog/reorder applies the order and broadcasts", async () => {
  const { svc, call, frames } = await boot()
  const a = svc.create("A")
  const b = svc.create("B")
  const res = await call("PATCH", "/project-catalog/reorder", { orderedIds: [b.id, a.id] })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ ok: true })
  expect(svc.list().map((p) => p.id)).toEqual([b.id, a.id])
  expect(catalogFrames(frames)).toHaveLength(1)
})

test("PATCH /project-catalog/reorder with an unknown id → 400, no broadcast", async () => {
  const { svc, call, frames } = await boot()
  const a = svc.create("A")
  const res = await call("PATCH", "/project-catalog/reorder", { orderedIds: [a.id, "nope"] })
  expect(res.status).toBe(400)
  expect(await res.json()).toEqual({ error: "unknown project id: nope" })
  expect(svc.list().map((p) => p.id)).toEqual([a.id])
  expect(catalogFrames(frames)).toHaveLength(0)
})

test("PATCH /project-catalog/reorder with a duplicate id → 400, no broadcast", async () => {
  const { svc, call, frames } = await boot()
  const a = svc.create("A")
  const res = await call("PATCH", "/project-catalog/reorder", { orderedIds: [a.id, a.id] })
  expect(res.status).toBe(400)
  expect(await res.json()).toEqual({ error: `duplicate project id: ${a.id}` })
  expect(catalogFrames(frames)).toHaveLength(0)
})

test("POST /project-catalog/:id/locations: adds an existing dir, 409 names the owner, 400 on a missing dir", async () => {
  const { svc, call, dir } = await boot()
  const a = svc.create("A")
  const b = svc.create("B")
  const loc = join(dir, "repo")
  mkdirSync(loc)

  const added = await call("POST", `/project-catalog/${a.id}/locations`, { path: loc })
  expect(added.status).toBe(200)
  expect((await added.json() as any).locations.map((l: any) => l.path)).toEqual([loc])

  const conflict = await call("POST", `/project-catalog/${b.id}/locations`, { path: loc })
  expect(conflict.status).toBe(409)
  expect(await conflict.json()).toMatchObject({ error: expect.any(String), projectId: a.id })

  const missingDir = await call("POST", `/project-catalog/${a.id}/locations`, { path: join(dir, "absent") })
  expect(missingDir.status).toBe(400)

  const unknown = await call("POST", "/project-catalog/nope/locations", { path: loc })
  expect(unknown.status).toBe(404)
})

test("PATCH /project-catalog/locations/:id moves a location to the target and returns it", async () => {
  const { svc, call, frames, dir } = await boot()
  const a = svc.create("A")
  const b = svc.create("B")
  const loc = join(dir, "repo")
  mkdirSync(loc)
  const locId = svc.addLocation(a.id, loc).locations[0]!.id

  const res = await call("PATCH", `/project-catalog/locations/${locId}`, { projectId: b.id })
  expect(res.status).toBe(200)
  expect(await res.json()).toMatchObject({ id: b.id, locations: [{ id: locId, path: loc }] })
  expect(catalogFrames(frames)).toHaveLength(1)

  expect((await call("PATCH", "/project-catalog/locations/nope", { projectId: b.id })).status).toBe(404)
  expect((await call("PATCH", `/project-catalog/locations/${locId}`, { projectId: "nope" })).status).toBe(404)
})

test("PUT image: 415 on text/plain, 413 on oversize content-length, 404 on unknown project", async () => {
  const { svc, call, frames, token } = await boot()
  const p = svc.create("A")
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47])

  const bad = await call("PUT", `/project-catalog/${p.id}/image`, png, { "content-type": "text/plain" })
  expect(bad.status).toBe(415)

  // Declares an oversize body but never sends it: the route must answer 413 from the
  // header alone. (A real 5 MB fetch body races the early response and errors client-side.)
  expect(await rawStatus(token, `PUT /project-catalog/${p.id}/image`, {
    "content-type": "image/png", "content-length": String(PROJECT_IMAGE_MAX_BYTES + 1),
  })).toBe(413)

  const unknown = await call("PUT", "/project-catalog/nope/image", png, { "content-type": "image/png" })
  expect(unknown.status).toBe(404)
  expect(catalogFrames(frames)).toHaveLength(0)
})

test("PUT image: a chunked body with no content-length is still capped as it streams in → 413", async () => {
  const { svc, token } = await boot()
  const p = svc.create("A")

  // No content-length header: the declared-size check can't catch this, and the
  // producer never signals `done`, so this only resolves (instead of hanging
  // forever, or buffering without bound) if the route caps the read as bytes
  // arrive rather than waiting to read the whole body first.
  const chunkSize = 1024 * 1024 // 1 MiB
  const body = new ReadableStream<Uint8Array>({
    async pull(controller) { controller.enqueue(new Uint8Array(chunkSize)) },
  })

  const res = await fetch(`${base()}/project-catalog/${p.id}/image`, {
    method: "PUT",
    headers: { authorization: `Bearer ${token}`, "content-type": "image/png" },
    body,
    // Bun/undici requires duplex for a streaming request body. Not every fetch typing declares
    // it, so it goes in through a cast rather than a @ts-expect-error that breaks once one does.
    ...({ duplex: "half" } as RequestInit),
  })
  expect(res.status).toBe(413)
  expect(await res.json()).toEqual({ error: "image too large" })
})

test("PUT, GET and DELETE image round-trip", async () => {
  const { svc, call, frames } = await boot()
  const p = svc.create("A")
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 1, 2, 3])

  const put = await call("PUT", `/project-catalog/${p.id}/image`, png, { "content-type": "image/png" })
  expect(put.status).toBe(200)
  const dto = await put.json() as any
  expect(dto.image_id).toMatch(/\.png$/)

  const get = await call("GET", `/project-catalog/${p.id}/image`)
  expect(get.status).toBe(200)
  expect(get.headers.get("content-type")).toBe("image/png")
  expect(get.headers.get("cache-control")).toBe("private, max-age=300")
  expect(get.headers.get("x-content-type-options")).toBe("nosniff")
  expect(new Uint8Array(await get.arrayBuffer())).toEqual(png)

  const del = await call("DELETE", `/project-catalog/${p.id}/image`)
  expect(del.status).toBe(200)
  expect((await del.json() as any).image_id).toBeUndefined()
  expect((await call("GET", `/project-catalog/${p.id}/image`)).status).toBe(404)
  expect(catalogFrames(frames)).toHaveLength(2)
})

test("GET image → 404 when the file is gone from disk", async () => {
  const { svc, call } = await boot()
  const p = svc.create("A")
  svc.setImage(p.id, new Uint8Array([1]), "image/png")
  rmSync(svc.imageFile(p.id)!.path)
  expect((await call("GET", `/project-catalog/${p.id}/image`)).status).toBe(404)
})

test("GET /projects keeps its path-only shape", async () => {
  const { call } = await boot({
    getSessionsSnapshot: () => [{ name: "s", workdir: "/srv/app", mute: false, connected: true } as any],
  })
  const res = await call("GET", "/projects")
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ projects: [{ path: "/srv/app" }] })
})

test("/project-catalog is an API path, not an SPA route", () => {
  expect(isApiPath("/project-catalog")).toBe(true)
  expect(isApiPath("/project-catalog/abc/image")).toBe(true)
})

test("the WS snapshot carries projects and projectMembership", async () => {
  const { svc, token } = await boot()
  const p = svc.create("Alpha")
  const ws = new WebSocket(`ws://127.0.0.1:${channel!.boundPort}/ws`, { headers: { Cookie: `cmux_token=${token}` } } as any)
  await new Promise<void>((resolve, reject) => { ws.onopen = () => resolve(); ws.onerror = reject })
  const snap = new Promise<any>((resolve) => { ws.onmessage = (e) => resolve(JSON.parse(String(e.data))) })
  ws.send(JSON.stringify({ type: "subscribe" }))
  const frame = await snap
  ws.close()
  expect(frame.type).toBe("snapshot")
  expect(frame.projects).toEqual([p])
  expect(frame.projectMembership).toEqual({ w1: "p-fixed" })
})
