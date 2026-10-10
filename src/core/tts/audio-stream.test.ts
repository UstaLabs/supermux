import { describe, expect, test } from "bun:test"
import { TtsAudioStreams } from "./audio-stream"
import type { TtsEngine, TtsSpeakOpts } from "./tts-types"

const enc = (s: string) => new TextEncoder().encode(s)
const dec = (parts: Uint8Array[]) => new TextDecoder().decode(Buffer.concat(parts))

async function drain(rs: ReadableStream<Uint8Array>): Promise<Uint8Array[]> {
  const out: Uint8Array[] = []
  const r = rs.getReader()
  while (true) {
    const { done, value } = await r.read()
    if (done) return out
    out.push(value)
  }
}

/** An engine whose pieces stream "A1","A2" etc. and only finish when the test releases them. */
function gatedEngine() {
  const started: string[] = []
  const gates: (() => void)[] = []
  const signals: AbortSignal[] = []
  const engine: TtsEngine = {
    name: "codex",
    isAvailable: () => true,
    speak: async () => { throw new Error("unused") },
    async speakStream(text: string, opts?: TtsSpeakOpts) {
      const n = started.push(text)
      if (opts?.signal) signals.push(opts.signal)
      let release!: () => void
      const gate = new Promise<void>((r) => (release = r))
      gates[n - 1] = release
      async function* body() {
        yield enc(`[${n}a]`)
        await gate
        if (opts?.signal?.aborted) throw new Error("aborted")
        yield enc(`[${n}b]`)
      }
      return { mime: "audio/mpeg", body: body() }
    },
  }
  return { engine, started, gates, signals }
}

// ~1,800 chars: exactly two codex pieces.
const longText = Array.from({ length: 40 }, (_, i) => `Sentence ${i} is here to make the text long.`).join(" ")

describe("TtsAudioStreams", () => {
  test("first bytes flow before the piece finishes, and pieces join in order", async () => {
    const { engine, started, gates } = gatedEngine()
    const streams = new TtsAudioStreams()
    const id = streams.start(engine, longText)
    const reader = streams.open(id)!.getReader()

    expect(dec([(await reader.read()).value!])).toBe("[1a]")
    // Piece 2 is already synthesizing while piece 1 is still streaming.
    expect(started.length).toBe(2)
    gates[0]!()
    gates[1]!()
    const rest: Uint8Array[] = []
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      rest.push(value)
    }
    expect(dec(rest)).toBe("[1b][2a][2b]")
  })

  test("a second GET replays from byte 0 without synthesizing again", async () => {
    const { engine, started, gates } = gatedEngine()
    const streams = new TtsAudioStreams()
    const id = streams.start(engine, "Short text.")
    const first = drain(streams.open(id)!)
    await Bun.sleep(5)
    const second = drain(streams.open(id)!)
    gates[0]!()
    expect(dec(await first)).toBe("[1a][1b]")
    expect(dec(await second)).toBe("[1a][1b]")
    expect(started.length).toBe(1)
  })

  test("unknown ids are null", () => {
    expect(new TtsAudioStreams().open("nope")).toBeNull()
  })

  test("synthesis is abandoned once nobody listens", async () => {
    const { engine, signals } = gatedEngine()
    const streams = new TtsAudioStreams({ idleMs: 20 })
    const id = streams.start(engine, longText)
    const reader = streams.open(id)!.getReader()
    await reader.read()
    await reader.cancel()
    await Bun.sleep(60)
    expect(signals.length).toBeGreaterThan(0)
    expect(signals.every((s) => s.aborted)).toBe(true)
  })

  test("an engine without speakStream is refused", () => {
    const engine: TtsEngine = { name: "x", isAvailable: () => true, speak: async () => ({ audio: new Uint8Array(), mime: "", engine: "x" }) }
    expect(() => new TtsAudioStreams().start(engine, "hi")).toThrow()
  })
})
