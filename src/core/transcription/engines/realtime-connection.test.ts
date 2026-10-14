import { test, expect } from "bun:test"
import { writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { encodeWav16 } from "../wav"
import { codexRealtimeEngine, type RealtimeWs } from "./codex-realtime"
import { DictationConnections, RealtimeConnection } from "./realtime-connection"

function clip(ms: number): string {
  const p = join(tmpdir(), `rt-conn-${process.pid}-${ms}-${Math.random().toString(36).slice(2)}.wav`)
  writeFileSync(p, encodeWav16(new Uint8Array((16000 * ms / 1000) * 2), 16000))
  return p
}

/**
 * A fake realtime socket that behaves like the live API: it opens after the caller listens, and every
 * commit is answered by `committed` + `completed` for a fresh item, with the next transcript.
 */
class FakeRealtime implements RealtimeWs {
  readyState = 1
  protocol = "realtime"
  sent: any[] = []
  closed = false
  private listeners: Record<string, Array<(ev: any) => void>> = {}
  private items = 0
  /** Delay commit → completed by this much (ms); null = never answer. */
  answerAfterMs: number | null = 0

  constructor(private transcripts: string[]) {}

  addEventListener(type: string, l: (ev: any) => void) {
    (this.listeners[type] ??= []).push(l)
    // Built up front by the tests, so it opens once someone is listening (as a real one would).
    if (type === "open") setTimeout(() => this.emit("open", {}), 0)
  }

  send(data: string) {
    const msg = JSON.parse(data)
    this.sent.push(msg)
    if (msg.type !== "input_audio_buffer.commit") return
    const item = `item_${++this.items}`
    const text = this.transcripts.shift() ?? ""
    this.message({ type: "input_audio_buffer.committed", item_id: item })
    if (this.answerAfterMs === null) return
    setTimeout(() => this.message({ type: "conversation.item.input_audio_transcription.completed", item_id: item, transcript: text }), this.answerAfterMs)
  }

  close() {
    if (this.closed) return
    this.closed = true
    this.emit("close", { code: 1000 })
  }

  types(): string[] {
    return this.sent.map((m) => m.type)
  }

  private message(m: unknown) {
    this.emit("message", { data: JSON.stringify(m) })
  }

  private emit(type: string, ev: unknown) {
    for (const l of this.listeners[type] ?? []) l(ev)
  }
}

const AUTH = JSON.stringify({ tokens: { access_token: "at" } })

function engine(sockets: FakeRealtime[], connections = new DictationConnections()) {
  let mints = 0
  const e = codexRealtimeEngine({
    authPath: "/fake/auth.json",
    readFileFn: () => AUTH,
    fetchFn: async () => { mints++; return new Response(JSON.stringify({ value: `ek${mints}` }), { status: 200 }) },
    connectWs: () => {
      const ws = sockets.shift()
      if (!ws) throw new Error("no more sockets")
      return ws
    },
    connections,
  })
  return { e, mints: () => mints, connections }
}

test("a dictation's passes share one connection: one mint, one socket", async () => {
  const ws = new FakeRealtime(["first", "second", "third"])
  const { e, mints } = engine([ws])
  const a = await e.transcribe(clip(300), { stream: "d1" })
  const b = await e.transcribe(clip(600), { stream: "d1", fromMs: 300, previousText: "first" })
  const c = await e.transcribe(clip(900), { stream: "d1", fromMs: 600, previousText: "first second" })
  expect([a.text, b.text, c.text]).toEqual(["first", "second", "third"])
  expect(mints()).toBe(1)
  expect(ws.types().filter((t) => t === "input_audio_buffer.commit").length).toBe(3)
})

test("a changed prompt is sent as session.update before the pass's audio", async () => {
  const ws = new FakeRealtime(["a", "b"])
  const { e } = engine([ws])
  await e.transcribe(clip(300), { stream: "d1" })
  await e.transcribe(clip(600), { stream: "d1", fromMs: 300, previousText: "a" })
  const types = ws.types()
  const update = types.indexOf("session.update")
  expect(update).toBeGreaterThan(types.indexOf("input_audio_buffer.commit"))
  expect(JSON.stringify(ws.sent[update])).toContain("continues a dictation already transcribed as")
  expect(types[update + 1]).toBe("input_audio_buffer.append")
})

test("warm opens the connection before the first pass", async () => {
  const ws = new FakeRealtime(["hello"])
  const { e, mints } = engine([ws])
  await e.warm!("d1", {})
  expect(mints()).toBe(1)
  const r = await e.transcribe(clip(300), { stream: "d1" })
  expect(r.text).toBe("hello")
  expect(mints()).toBe(1)
})

test("the last pass closes the connection", async () => {
  const ws = new FakeRealtime(["done"])
  const { e, connections } = engine([ws])
  await e.transcribe(clip(300), { stream: "d1", endStream: true })
  await Bun.sleep(0)
  expect(ws.closed).toBe(true)
  expect(connections.size).toBe(0)
})

test("separate dictations get separate connections", async () => {
  const a = new FakeRealtime(["from a"])
  const b = new FakeRealtime(["from b"])
  const { e, mints } = engine([a, b])
  const [ra, rb] = await Promise.all([
    e.transcribe(clip(300), { stream: "A" }),
    e.transcribe(clip(300), { stream: "B" }),
  ])
  expect([ra.text, rb.text]).toEqual(["from a", "from b"])
  expect(mints()).toBe(2)
})

test("a dead connection is replaced by a new one on the next pass", async () => {
  const first = new FakeRealtime(["one"])
  const second = new FakeRealtime(["two", "three"])
  const { e, mints } = engine([first, second])
  expect((await e.transcribe(clip(300), { stream: "d1" })).text).toBe("one")
  first.close()
  expect((await e.transcribe(clip(600), { stream: "d1", fromMs: 300, previousText: "one" })).text).toBe("two")
  expect((await e.transcribe(clip(900), { stream: "d1", fromMs: 600, previousText: "one two" })).text).toBe("three")
  expect(mints()).toBe(2)
})

test("when the reused connection fails mid-pass, the pass is retried on a one-off connection", async () => {
  const broken = new FakeRealtime([])
  broken.answerAfterMs = null
  const oneOff = new FakeRealtime(["rescued"])
  const { e } = engine([broken, oneOff])
  await e.warm!("d1", {})
  const pending = e.transcribe(clip(300), { stream: "d1" })
  await Bun.sleep(10)
  broken.close()
  expect((await pending).text).toBe("rescued")
})

test("passes on one connection run in order", async () => {
  const ws = new FakeRealtime(["first", "second"])
  ws.answerAfterMs = 20
  const { e } = engine([ws])
  await e.warm!("d1", {})
  const [a, b] = await Promise.all([
    e.transcribe(clip(300), { stream: "d1" }),
    e.transcribe(clip(600), { stream: "d1" }),
  ])
  expect([a.text, b.text]).toEqual(["first", "second"])
})

test("a pass that never answers retires the connection", async () => {
  const ws = new FakeRealtime([])
  ws.answerAfterMs = null
  const conn = await RealtimeConnection.open(ws, { model: "m" }, 1_000)
  await expect(conn.transcribe(new Uint8Array(4_800), { model: "m" }, 30)).rejects.toThrow(/timeout/)
  expect(conn.alive).toBe(false)
})

test("an idle connection is closed", async () => {
  const ws = new FakeRealtime([])
  const connections = new DictationConnections({ idleMs: 20, maxAgeMs: 60_000 })
  await connections.acquire("d1", () => RealtimeConnection.open(ws, { model: "m" }, 1_000))
  expect(connections.size).toBe(1)
  await Bun.sleep(50)
  expect(connections.size).toBe(0)
  expect(ws.closed).toBe(true)
})

test("a connection past its max age is replaced", async () => {
  let t = 0
  const connections = new DictationConnections({ idleMs: 60_000, maxAgeMs: 100 }, () => t)
  const a = new FakeRealtime([])
  const b = new FakeRealtime([])
  const c1 = await connections.acquire("d1", () => RealtimeConnection.open(a, { model: "m" }, 1_000))
  t = 50
  expect(await connections.acquire("d1", () => RealtimeConnection.open(b, { model: "m" }, 1_000))).toBe(c1)
  t = 200
  const c2 = await connections.acquire("d1", () => RealtimeConnection.open(b, { model: "m" }, 1_000))
  expect(c2).not.toBe(c1)
  await Bun.sleep(0)
  expect(a.closed).toBe(true)
  connections.release("d1")
})
