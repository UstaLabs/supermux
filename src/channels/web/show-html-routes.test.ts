// show_html over the web channel (#104): GET /html/<id> (authed, never text/html) and a `send` frame from a card.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { FsHtmlPageStore } from "../../core/show-html/service"
import type { InboundMessage } from "../channel"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

const PAGE = `<!doctype html><script>fetch('/me')</script><h1>hi</h1>`

async function boot() {
  const dir = mkdtempSync(join(tmpdir(), "mux-show-html-"))
  const devicesFile = join(dir, "devices.json")
  const pages = new FsHtmlPageStore(join(dir, "html"))
  const id = pages.put(PAGE)
  const sent: InboundMessage[] = []
  const opts: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [{ id: "a", name: "alpha", workdir: "/w", mute: false, connected: true }] as any,
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    htmlPages: pages,
    findCard: (session, cardId) => (session === "a" && cardId === id ? { title: "Pick one" } : undefined),
    updateChecker: null,
  }
  channel = new WebChannel(opts)
  channel.on("inbound", (m) => { sent.push(m) })
  await channel.start()
  const token = new DeviceStore(devicesFile).mint("phone").token
  return { base: `http://127.0.0.1:${channel.boundPort}`, id, token, sent }
}

test("GET /html/<id> needs a login", async () => {
  const { base, id } = await boot()
  expect((await fetch(`${base}/html/${id}`)).status).toBe(401)
})

test("GET /html/<id> gives the page as a string in JSON, never as text/html", async () => {
  const { base, id, token } = await boot()
  const res = await fetch(`${base}/html/${id}`, { headers: { authorization: `Bearer ${token}` } })
  expect(res.status).toBe(200)
  const type = res.headers.get("content-type")!
  expect(type).toStartWith("application/json")
  expect(type).not.toContain("html")
  expect(res.headers.get("x-content-type-options")).toBe("nosniff")
  expect(res.headers.get("content-security-policy")).toContain("sandbox")
  expect(res.headers.get("content-disposition")).toBeNull()
  expect(await res.json()).toEqual({ id, html: PAGE })
})

test("GET /html/<id>: an unknown or malformed id is 404, a path never reaches the file system", async () => {
  const { base, token } = await boot()
  const get = (p: string) => fetch(`${base}/html/${p}`, { headers: { authorization: `Bearer ${token}` } })
  expect((await get("0".repeat(64))).status).toBe(404)
  expect((await get("..%2F..%2Fregistry.json")).status).toBe(404)
  expect((await get("%E0%A4%A")).status).toBe(404)
  expect((await get("")).status).not.toBe(200)
})

function open(base: string, token: string) {
  const ws = new WebSocket(`${base.replace("http", "ws")}/ws`, { headers: { Cookie: `cmux_token=${token}` } } as any)
  const frames: any[] = []
  ws.onmessage = (e) => frames.push(JSON.parse(String(e.data)))
  const ready = new Promise<void>((resolve, reject) => { ws.onopen = () => resolve(); ws.onerror = reject })
  const settle = () => new Promise((r) => setTimeout(r, 150))
  return { ws, frames, ready, settle }
}

test("a send from a card becomes the user's message with from_card; an unknown card, another session's card and a long text are refused", async () => {
  const { base, id, token, sent } = await boot()
  const c = open(base, token)
  await c.ready
  const send = (args: any, session = "a") => c.ws.send(JSON.stringify({ type: "send", session, op: "reply", args }))

  send({ text: "Use B", from_card: { id } })
  await c.settle()
  expect(sent.map((m) => [m.text, m.from_card, m.target_session_id])).toEqual([["Use B", { id, title: "Pick one" }, "a"]])

  const refused = async (args: any, session = "a") => {
    const before = sent.length, errors = c.frames.filter((f) => f.type === "error").length
    send(args, session)
    await c.settle()
    expect(sent.length).toBe(before)
    expect(c.frames.filter((f) => f.type === "error").length).toBe(errors + 1)
  }
  await refused({ text: "hi", from_card: { id: "f".repeat(64) } })          // unknown id
  await refused({ text: "hi", from_card: { id } }, "b")                      // not that session's card
  await refused({ text: "hi", from_card: {} })                               // no id
  await new Promise((r) => setTimeout(r, 1000))
  await refused({ text: "x".repeat(2001), from_card: { id } })               // over the cap
  c.ws.close()
})

test("a card sends one message per second", async () => {
  const { base, id, token, sent } = await boot()
  const c = open(base, token)
  await c.ready
  for (const text of ["one", "two"]) c.ws.send(JSON.stringify({ type: "send", session: "a", op: "reply", args: { text, from_card: { id } } }))
  await c.settle()
  expect(sent.map((m) => m.text)).toEqual(["one"])
  expect(c.frames.some((f) => f.type === "error" && /per second/.test(f.reason))).toBe(true)
  await new Promise((r) => setTimeout(r, 1000))
  c.ws.send(JSON.stringify({ type: "send", session: "a", op: "reply", args: { text: "three", from_card: { id } } }))
  await c.settle()
  expect(sent.map((m) => m.text)).toEqual(["one", "three"])
  c.ws.close()
})

test("an ordinary send is untouched by the card rules", async () => {
  const { base, token, sent } = await boot()
  const c = open(base, token)
  await c.ready
  c.ws.send(JSON.stringify({ type: "send", session: "a", op: "reply", args: { text: "hello" } }))
  await c.settle()
  expect(sent.map((m) => [m.text, m.from_card])).toEqual([["hello", undefined]])
  c.ws.close()
})
