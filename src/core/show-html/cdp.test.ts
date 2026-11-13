import { afterAll, expect, test } from "bun:test"
import { readdirSync, readFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { CdpClient, launchChrome } from "./cdp"
import { findBrowser } from "./check"

// The wire, against a stand-in browser (a WebSocket server): always run. Real Chrome: SHOW_HTML_EVALS=1.
const run = findBrowser() && process.env.SHOW_HTML_EVALS === "1" ? test : test.skip

type Wire = import("bun").ServerWebSocket<unknown>
const servers: ReturnType<typeof Bun.serve>[] = []
afterAll(() => { for (const s of servers) s.stop(true) })

function standIn(onMessage: (ws: Wire, m: any) => void): string {
  const server = Bun.serve({
    port: 0, hostname: "127.0.0.1",
    fetch(req, srv) { return srv.upgrade(req) ? undefined : new Response("no", { status: 400 }) },
    websocket: { message(ws, text) { onMessage(ws, JSON.parse(String(text))) } },
  })
  servers.push(server)
  return `ws://127.0.0.1:${server.port}/devtools/browser/x`
}

test("a command goes out with an id and the matching result comes back", async () => {
  const seen: any[] = []
  const url = standIn((ws, m) => { seen.push(m); ws.send(JSON.stringify({ id: m.id, result: { echo: m.params.v } })) })
  const c = await CdpClient.connect(url)
  const [a, b] = await Promise.all([c.send("Test.one", { v: 1 }), c.send("Test.two", { v: 2 })])
  expect(a).toEqual({ echo: 1 })
  expect(b).toEqual({ echo: 2 })
  expect(seen.map((m) => m.method)).toEqual(["Test.one", "Test.two"])
  expect(seen[0].id).not.toBe(seen[1].id)
  await c.close()
})

test("a protocol error rejects that call only", async () => {
  const url = standIn((ws, m) => ws.send(JSON.stringify(m.method === "Bad.call"
    ? { id: m.id, error: { code: -32601, message: "not found" } } : { id: m.id, result: {} })))
  const c = await CdpClient.connect(url)
  await expect(c.send("Bad.call")).rejects.toThrow("Bad.call: not found")
  expect(await c.send<object>("Good.call")).toEqual({})
  await c.close()
})

test("an event reaches its subscriber, by session, until unsubscribed", async () => {
  const url = standIn((ws, m) => {
    ws.send(JSON.stringify({ id: m.id, result: {} }))
    ws.send(JSON.stringify({ method: "Page.loadEventFired", params: { n: 1 }, sessionId: "S1" }))
    ws.send(JSON.stringify({ method: "Page.loadEventFired", params: { n: 2 }, sessionId: "S2" }))
    ws.send(JSON.stringify({ method: "Target.targetCreated", params: { n: 3 } }))
  })
  const c = await CdpClient.connect(url)
  const s1: number[] = [], browser: number[] = []
  const off = c.on("Page.loadEventFired", (p) => s1.push(p.n), "S1")
  c.on("Target.targetCreated", (p) => browser.push(p.n))
  await c.send("Poke")
  await Bun.sleep(30)
  expect(s1).toEqual([1])
  expect(browser).toEqual([3])
  off()
  await c.send("Poke")
  await Bun.sleep(30)
  expect(s1).toEqual([1])
  await c.close()
})

test("a session's commands carry its id, and a detached session rejects its pending calls", async () => {
  const seen: any[] = []
  const url = standIn((ws, m) => {
    seen.push(m)
    if (m.method === "Target.attachToTarget") ws.send(JSON.stringify({ id: m.id, result: { sessionId: "SESS" } }))
    else if (m.method === "Page.hang") ws.send(JSON.stringify({ method: "Target.detachedFromTarget", params: { sessionId: "SESS" } }))
    else ws.send(JSON.stringify({ id: m.id, result: { ok: 1 }, sessionId: m.sessionId }))
  })
  const c = await CdpClient.connect(url)
  const s = await c.attach("T1")
  expect(seen[0].params).toEqual({ targetId: "T1", flatten: true })
  expect(await s.send<object>("Page.enable")).toEqual({ ok: 1 })
  expect(seen[1].sessionId).toBe("SESS")
  await expect(s.send("Page.hang")).rejects.toThrow("the page went away")
  await c.close()
})

test("a closed browser rejects pending calls and later ones", async () => {
  const url = standIn((ws, m) => { if (m.method === "Die") ws.close() })
  const c = await CdpClient.connect(url)
  const pending = c.send("Never.answered")
  const dying = c.send("Die")
  const settled = await Promise.allSettled([pending, dying])
  expect(settled.map((r) => r.status)).toEqual(["rejected", "rejected"])
  expect(settled.map((r) => String((r as PromiseRejectedResult).reason.message))).toEqual([
    "Never.answered: the browser connection closed", "Die: the browser connection closed"])
  await c.closed
  expect(c.isClosed).toBe(true)
  await expect(c.send("After")).rejects.toThrow("closed")
})

test("connecting to nothing is an error, not a hang", async () => {
  await expect(CdpClient.connect("ws://127.0.0.1:1/devtools/browser/x", 3000)).rejects.toThrow()
})

run("real Chrome: a round trip, an event, and the profile removed on close", async () => {
  const chrome = await launchChrome(findBrowser()!, { env: { ...process.env, DBUS_SESSION_BUS_ADDRESS: "disabled:" } })
  const before = readdirSync(tmpdir()).filter((n) => n.startsWith("supermux-check-"))
  expect(before.length).toBeGreaterThan(0)
  const v = await chrome.client.send<{ product: string }>("Browser.getVersion")
  expect(v.product).toMatch(/Chrome|Chromium|Edg/)
  const created = new Promise<string>((r) => chrome.client.on("Target.targetCreated", (p) => { if (p.targetInfo.url === "about:blank#probe") r(p.targetInfo.type) }))
  await chrome.client.send("Target.setDiscoverTargets", { discover: true })
  await chrome.client.send("Target.createTarget", { url: "about:blank#probe" })
  expect(["page", "tab"]).toContain(await created)
  await chrome.close()
  expect(chrome.client.isClosed).toBe(true)
  await Bun.sleep(200)
  const after = readdirSync(tmpdir()).filter((n) => n.startsWith("supermux-check-"))
  expect(after.length).toBeLessThan(before.length)
}, 30_000)

run("real Chrome: killing the browser rejects a pending call", async () => {
  const chrome = await launchChrome(findBrowser()!, { env: { ...process.env, DBUS_SESSION_BUS_ADDRESS: "disabled:" } })
  const { targetId } = await chrome.client.send<{ targetId: string }>("Target.createTarget", { url: "about:blank" })
  const page = await chrome.client.attach(targetId)
  const hung = page.send("Runtime.evaluate", { expression: "new Promise(() => {})", awaitPromise: true })
  await Bun.sleep(100)
  const closing = chrome.close()
  await expect(hung).rejects.toThrow()
  await closing
}, 30_000)

test("nothing under src/ names playwright in an import (it is the evals' tool, not the broker's)", () => {
  const files = readdirSync(import.meta.dir).filter((f) => f.endsWith(".ts") && !f.endsWith(".test.ts"))
  expect(files).toContain("check.ts")
  expect(files).toContain("cdp.ts")
  for (const f of files) {
    const src = readFileSync(join(import.meta.dir, f), "utf8")
    const imports = src.split("\n").filter((l) => /^\s*(import|export)\b.*\bfrom\b|\bimport\s*\(|\brequire\s*\(/.test(l))
    expect(imports.filter((l) => /playwright/i.test(l))).toEqual([])
  }
})
