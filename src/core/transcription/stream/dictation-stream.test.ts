import { describe, expect, test } from "bun:test"
import { DictationStream, type DictationStreamDeps, type StreamEvent } from "./dictation-stream"
import type { VadStream } from "./speech-models"

// The fakes speak a tiny language: audio is "speech" wherever it is non-zero, and a stretch of one
// constant value v is the word `w<v>`. Words of 100 and up end a sentence (the fake Smart Turn
// says "finished" when the audio ends on one).

const word = (v: number, ms = 400) => new Int16Array(ms * 16).fill(v)
const quiet = (ms: number) => new Int16Array(ms * 16)

function bytes(...parts: Int16Array[]): Uint8Array {
  const n = parts.reduce((a, p) => a + p.length, 0)
  const out = new Uint8Array(n * 2)
  const view = new DataView(out.buffer)
  let o = 0
  for (const p of parts) for (const s of p) { view.setInt16(o, s, true); o += 2 }
  return out
}

function wordsOf(pcm: Int16Array | Float32Array): number[] {
  const out: number[] = []
  let prev = 0
  for (const raw of pcm) {
    const v = Math.round(pcm instanceof Float32Array ? raw * 32768 : raw)
    if (v !== 0 && v !== prev) out.push(v)
    prev = v
  }
  return out
}
const say = (ws: number[]) => ws.map((v) => `w${v}`).join(" ")

/** Scores each whole 512-sample frame: any non-zero sample = speech. Synchronous. */
type FakeVad = DictationStreamDeps["vad"] & { fail?: (e: Error) => void }

function fakeVad(): FakeVad {
  const f: FakeVad = (onProbs: (p: Float32Array) => void, onError: (e: Error) => void): VadStream => {
    let carry: number[] = []
    f.fail = onError
    return {
      push(pcm) {
        carry.push(...pcm)
        const n = Math.floor(carry.length / 512)
        const probs = new Float32Array(n)
        for (let i = 0; i < n; i++) probs[i] = carry.slice(i * 512, (i + 1) * 512).some((s) => s !== 0) ? 0.9 : 0.05
        carry = carry.slice(n * 512)
        if (n) onProbs(probs)
      },
      flush: () => Promise.resolve(),
      close() {},
    }
  }
  return f
}

interface Harness {
  stream: DictationStream
  /** What was sent, `seq` left out (the tests that care read [raw]). */
  events: unknown[]
  raw: StreamEvent[]
  texts: () => string[]
  stt: { words: number[]; context: string; final: boolean }[]
  turns: number
  vad: ReturnType<typeof fakeVad>
  push: (...parts: Int16Array[]) => Promise<void>
}

function harness(over: Partial<DictationStreamDeps> = {}): Harness {
  const events: unknown[] = []
  const raw: StreamEvent[] = []
  const stt: Harness["stt"] = []
  const vad = fakeVad()
  const h: Harness = {
    events,
    raw,
    stt,
    turns: 0,
    vad,
    texts: () => raw.filter((e) => e.type === "text").map((e) => (e as { text: string }).text),
    stream: null as unknown as DictationStream,
    push: async (...parts) => {
      h.stream.pushAudio(bytes(...parts))
      await h.stream.whenIdle()
    },
  }
  h.stream = new DictationStream({
    vad,
    livePasses: true,
    transcribe: async (pcm, o) => {
      const ws = wordsOf(pcm)
      stt.push({ words: ws, context: o.context, final: o.final })
      return say(ws)
    },
    turn: async (audio) => {
      h.turns++
      const ws = wordsOf(audio)
      return (ws[ws.length - 1] ?? 0) >= 100 ? 0.9 : 0.1
    },
    send: (e) => {
      raw.push(e)
      if (e.type === "text") events.push({ type: "text", text: e.text })
      else events.push(e)
    },
    ...over,
  })
  return h
}

describe("DictationStream", () => {
  test("a pause sends the sentence; a finished one is locked and the next pass starts after it", async () => {
    const h = harness()
    await h.push(word(1), word(101), quiet(400))
    expect(h.texts()).toEqual(["w1 w101"])
    await h.push(word(2), word(102), quiet(400))
    expect(h.stt.map((c) => c.words)).toEqual([[1, 101], [2, 102]])
    expect(h.stt[1]!.context).toBe("w1 w101")
    expect(h.texts()).toEqual(["w1 w101", "w1 w101 w2 w102"])
  })

  test("an unfinished sentence is transcribed again, whole, at the next pause", async () => {
    const h = harness()
    await h.push(word(1), quiet(400))
    expect(h.texts()).toEqual(["w1"])
    await h.push(word(101), quiet(400))
    expect(h.stt.map((c) => c.words)).toEqual([[1], [1, 101]])
    expect(h.texts()).toEqual(["w1", "w1 w101"])
    // ...and that one was finished, so the next starts after it.
    await h.push(word(3), quiet(400))
    expect(h.stt[2]!.words).toEqual([3])
  })

  test("a breath is not a pass", async () => {
    const h = harness()
    await h.push(word(1, 150), quiet(400))
    expect(h.stt).toEqual([])
    expect(h.events).toEqual([])
  })

  test("talking without a pause still updates the text, without locking", async () => {
    const h = harness()
    await h.push(word(1, 3000), word(2, 2500))
    expect(h.stt.length).toBe(1)
    expect(h.turns).toBe(0)
    await h.push(quiet(400))
    // The pause redoes the whole stretch, from the same start.
    expect(h.stt[1]!.words).toEqual([1, 2])
  })

  test("Stop right after a pass: no extra transcription", async () => {
    const h = harness()
    await h.push(word(1), word(101), quiet(400))
    await h.stream.stop()
    expect(h.stt.length).toBe(1)
    expect(h.events.slice(-2)).toEqual([{ type: "final", text: "w1 w101" }, { type: "done" }])
  })

  test("Stop while still talking transcribes the rest of the current sentence", async () => {
    const h = harness()
    await h.push(word(1), word(101), quiet(400), word(2))
    await h.stream.stop()
    expect(h.stt[1]).toEqual({ words: [2], context: "w1 w101", final: true })
    expect(h.events.at(-2)).toEqual({ type: "final", text: "w1 w101 w2" })
  })

  test("Stop on an unfinished sentence keeps it", async () => {
    const h = harness()
    await h.push(word(1), quiet(400))
    await h.stream.stop()
    expect(h.stt.length).toBe(1)
    expect(h.events.at(-2)).toEqual({ type: "final", text: "w1" })
  })

  test("Stop waits for the pass that is out", async () => {
    let release!: () => void
    const gate = new Promise<void>((r) => { release = r })
    const h = harness({ transcribe: async (pcm) => { await gate; return say(wordsOf(pcm)) } })
    h.stream.pushAudio(bytes(word(1), word(101), quiet(400)))
    const stopped = h.stream.stop()
    await Bun.sleep(5)
    expect(h.events).toEqual([])
    release()
    await stopped
    expect(h.events).toEqual([{ type: "text", text: "w1 w101" }, { type: "final", text: "w1 w101" }, { type: "done" }])
  })

  test("Stop does not wait for Smart Turn", async () => {
    const h = harness({ turn: () => new Promise<number>(() => {}) })
    h.stream.pushAudio(bytes(word(1), word(101), quiet(400)))
    const t0 = performance.now()
    await h.stream.stop()
    expect(performance.now() - t0).toBeLessThan(500)
    expect(h.events.slice(-2)).toEqual([{ type: "final", text: "w1 w101" }, { type: "done" }])
  })

  test("the user's own edit wins, and dictation carries on after it", async () => {
    const h = harness()
    await h.push(word(1), word(101), quiet(400))
    h.stream.edit("Hello there.")
    await h.push(word(2), word(102), quiet(400))
    expect(h.stt[1]!).toEqual({ words: [2, 102], context: "Hello there.", final: false })
    expect(h.texts().at(-1)).toBe("Hello there. w2 w102")
  })

  test("texts are numbered, and an edit made on an older one resumes after ITS audio", async () => {
    const h = harness()
    await h.push(word(1), word(101), quiet(400))
    await h.push(word(2), word(102), quiet(400))
    const seqs = h.raw.flatMap((e) => (e.type === "text" ? [e.seq] : []))
    expect(seqs).toEqual([1, 2])
    // The user edited text 1 while text 2 was on its way: the words of text 2 are not lost.
    h.stream.edit("Mine.", 1)
    await h.stream.stop()
    expect(h.stt.at(-1)).toEqual({ words: [2, 102], context: "Mine.", final: true })
    expect(h.events.at(-2)).toEqual({ type: "final", text: "Mine. w2 w102" })
  })

  test("an edit that arrives during Stop is ignored, and final still comes", async () => {
    let release!: () => void
    const gate = new Promise<void>((r) => { release = r })
    const h = harness({ transcribe: async (pcm) => { await gate; return say(wordsOf(pcm)) } })
    h.stream.pushAudio(bytes(word(1), word(101), quiet(400)))
    const stopped = h.stream.stop()
    h.stream.edit("Too late.")
    release()
    await stopped
    expect(h.events.slice(-2)).toEqual([{ type: "final", text: "w1 w101" }, { type: "done" }])
  })

  test("an edit made while a pass is out drops that pass's answer", async () => {
    let release!: () => void
    const gate = new Promise<void>((r) => { release = r })
    let calls = 0
    const h = harness({ transcribe: async (pcm) => { if (calls++ === 1) await gate; return say(wordsOf(pcm)) } })
    await h.push(word(1), word(101), quiet(400))
    h.stream.pushAudio(bytes(word(2), word(102), quiet(400)))
    h.stream.edit("Mine.")
    release()
    await h.stream.whenIdle()
    expect(h.texts()).toEqual(["w1 w101"])
    // The words after what the user saw are not lost: Stop picks them up.
    await h.stream.stop()
    expect(h.events.at(-2)).toEqual({ type: "final", text: "Mine. w2 w102" })
  })

  test("a spoken edit replaces the text it was about", async () => {
    const h = harness({ checkEdit: async (text, added) => (added === "w107" ? `${text.split(" ")[0]}` : null) })
    await h.push(word(1), word(101), quiet(400))
    await h.push(word(107), quiet(400))
    await Bun.sleep(1)
    expect(h.texts().at(-1)).toBe("w1")
  })

  test("a spoken edit that lands after Stop comes as a late text before done", async () => {
    let release!: () => void
    const gate = new Promise<void>((r) => { release = r })
    const h = harness({ checkEdit: async () => { await gate; return "Edited." } })
    await h.push(word(1), word(101), quiet(400), word(2), word(102), quiet(400))
    const stopped = h.stream.stop()
    await Bun.sleep(5)
    expect(h.events.at(-1)).toEqual({ type: "final", text: "w1 w101 w2 w102", pendingEdit: true })
    release()
    await stopped
    expect(h.events.slice(-2)).toEqual([{ type: "text", text: "Edited." }, { type: "done" }])
  })

  test("a slow engine gets the whole recording at Stop and no live passes", async () => {
    let whole: number[] = []
    const h = harness({ livePasses: false, transcribeWhole: async (pcm) => { whole = wordsOf(pcm); return "all of it" } })
    await h.push(word(1), word(101), quiet(400), word(2))
    expect(h.stt).toEqual([])
    await h.stream.stop()
    expect(whole).toEqual([1, 101, 2])
    expect(h.events.at(-2)).toEqual({ type: "final", text: "all of it" })
  })

  test("a failed pass keeps its audio for the next one", async () => {
    let calls = 0
    const h = harness({ transcribe: async (pcm) => { if (calls++ === 0) throw new Error("boom"); return say(wordsOf(pcm)) } })
    await h.push(word(1), word(101), quiet(400))
    expect(h.events).toEqual([])
    await h.push(word(2), quiet(400))
    expect(h.texts()).toEqual(["w1 w101 w2"])
  })

  test("a failed final pass falls back to the whole recording", async () => {
    let calls = 0
    const h = harness({
      transcribe: async (pcm) => { if (calls++ === 1) throw new Error("boom"); return say(wordsOf(pcm)) },
      transcribeWhole: async (pcm) => say(wordsOf(pcm)),
    })
    await h.push(word(1), word(101), quiet(400), word(2))
    await h.stream.stop()
    expect(h.events.at(-2)).toEqual({ type: "final", text: "w1 w101 w2" })
  })

  test("a long unfinished sentence is locked anyway", async () => {
    const h = harness({ timing: { maxSentenceMs: 1_500 } })
    await h.push(word(1, 700), quiet(400))
    await h.push(word(2, 700), quiet(400))
    // The first pass (0.7 s of speech + the 0.3 s pause) is under 1.5 s: not locked. The second
    // runs from the same start past 1.5 s: locked although unfinished.
    await h.push(word(3), quiet(400))
    expect(h.stt.map((c) => c.words)).toEqual([[1], [1, 2], [3]])
  })

  test("speech detection failing is a fatal error", async () => {
    const h = harness()
    await h.push(word(1, 100))
    h.vad.fail!(new Error("worker died"))
    expect(h.events).toEqual([{ type: "error", message: "speech detection failed: worker died", fatal: true }])
  })

  test("audio split mid-sample is put back together", async () => {
    const h = harness()
    const b = bytes(word(1), word(101), quiet(400))
    h.stream.pushAudio(b.subarray(0, 1001))
    h.stream.pushAudio(b.subarray(1001))
    await h.stream.whenIdle()
    expect(h.stt[0]!.words).toEqual([1, 101])
  })

  test("cancel: no final, nothing more", async () => {
    let ended = 0
    const h = harness({ endStream: () => { ended++ } })
    await h.push(word(1), word(101), quiet(400))
    h.stream.cancel()
    await h.stream.stop()
    expect(h.raw.map((e) => e.type)).toEqual(["text"])
    expect(ended).toBe(1)
  })
})
