import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync, existsSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { AppletDataApi } from "./data-api"

const cleanups: (() => void)[] = []
afterEach(() => { while (cleanups.length) cleanups.pop()!() })

function setup() {
  const dir = mkdtempSync(join(tmpdir(), "applet-data-"))
  const api = new AppletDataApi()
  cleanups.push(() => { api.closeAll(); rmSync(dir, { recursive: true, force: true }) })
  const call = (method: string, rest: string, body?: unknown, raw?: BodyInit) =>
    api.handle(new Request(`http://b/a/x/api${rest}`, { method, ...(body !== undefined ? { body: JSON.stringify(body), headers: { "content-type": "application/json" } } : raw !== undefined ? { body: raw } : {}) }), dir, rest.split("?")[0]!)
  return { dir, api, call }
}

test("collections: create, list with a filter and a sort, get, patch (merged), delete", async () => {
  const { call } = setup()
  const a = await (await call("POST", "/collections/books/records", { title: "Dune", done: 1 })).json() as any
  expect(a).toMatchObject({ title: "Dune", done: 1 })
  expect(typeof a.id).toBe("string")
  await call("POST", "/collections/books/records", { title: "Piranesi", done: 0 })
  await call("POST", "/collections/books/records", { id: "snow", title: "Snow Crash", done: 1 })
  expect((await call("POST", "/collections/books/records", { id: "snow" })).status).toBe(409)
  const done = await (await call("GET", "/collections/books/records?done=1&sort=-title")).json() as any
  expect(done.items.map((b: any) => b.title)).toEqual(["Snow Crash", "Dune"])
  const p = await (await call("PATCH", "/collections/books/records/snow", { done: 0, note: "reread" })).json() as any
  expect(p).toMatchObject({ id: "snow", title: "Snow Crash", done: 0, note: "reread" })
  expect((await call("DELETE", "/collections/books/records/snow")).status).toBe(200)
  expect((await call("GET", "/collections/books/records/snow")).status).toBe(404)
  expect((await call("GET", "/collections/b%27ad/records")).status).toBe(400)
})

test("live updates: a change arrives as a server-sent event", async () => {
  const { call } = setup()
  const res = await call("GET", "/collections/books/events")
  expect(res.headers.get("content-type")).toBe("text/event-stream")
  const reader = res.body!.getReader()
  await reader.read() // ": connected"
  await call("POST", "/collections/books/records", { title: "Klara" })
  const { value } = await reader.read()
  const text = typeof value === "string" ? value : new TextDecoder().decode(value)
  expect(text).toContain('"action":"create"')
  expect(text).toContain("Klara")
  await reader.cancel()
})

test("kv and files, never outside data/files", async () => {
  const { call, dir } = setup()
  expect((await call("GET", "/kv/theme")).status).toBe(404)
  await call("PUT", "/kv/theme", { dark: true })
  expect(await (await call("GET", "/kv/theme")).json()).toEqual({ dark: true })
  expect((await call("PUT", "/files/photos/a.txt", undefined, "hello")).status).toBe(200)
  expect(await (await call("GET", "/files/photos/a.txt")).text()).toBe("hello")
  expect(((await (await call("GET", "/files")).json()) as any).items).toMatchObject([{ path: "photos/a.txt", size: 5 }])
  for (const bad of ["/files/..%2F..%2Fetc", "/files/a/../../x", "/files/%00"]) expect([bad, (await call("GET", bad)).status]).toEqual([bad, 400])
  expect(existsSync(join(dir, "app.db"))).toBe(true)
})
