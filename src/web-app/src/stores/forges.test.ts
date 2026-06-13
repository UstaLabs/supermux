import { test, expect, beforeEach, afterEach } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useForges } from "./forges"

beforeEach(() => setActivePinia(createPinia()))
const realFetch = globalThis.fetch
afterEach(() => { globalThis.fetch = realFetch })
function mockFetch(routes: Record<string, unknown>) {
  globalThis.fetch = (async (url: any, init?: any) => {
    const key = `${init?.method ?? "GET"} ${String(url)}`
    const body = routes[key] ?? routes[String(url)] ?? {}
    return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } })
  }) as any
}

test("loadConnections populates connections + cliStatus", async () => {
  mockFetch({ "GET /forge/connections": { connections: [{ id: "github:github.com:a", kind: "github", host: "github.com", apiBase: "", label: "github.com · @a", account: { login: "a" }, source: "pat", transport: "https", status: "ok" }], cli: { github: { available: true, login: "a" }, gitlab: { available: false } } } })
  const s = useForges()
  await s.loadConnections()
  expect(s.connections).toHaveLength(1)
  expect(s.cliStatus?.github.available).toBe(true)
})

test("connect posts a PAT then reloads", async () => {
  mockFetch({
    "POST /forge/connections": { id: "github:github.com:a", kind: "github", host: "github.com", apiBase: "", label: "x", account: { login: "a" }, source: "pat", transport: "https", status: "ok" },
    "GET /forge/connections": { connections: [{ id: "github:github.com:a", kind: "github", host: "github.com", apiBase: "", label: "x", account: { login: "a" }, source: "pat", transport: "https", status: "ok" }], cli: null },
  })
  const s = useForges()
  await s.connect({ kind: "github", token: "ghp_x", source: "pat" })
  expect(s.connections).toHaveLength(1)
  expect(s.error).toBeNull()
})
