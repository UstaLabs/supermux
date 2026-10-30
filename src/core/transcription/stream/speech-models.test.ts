import { afterAll, describe, expect, test } from "bun:test"
import { spawnSync } from "child_process"
import { existsSync, mkdtempSync, readFileSync, rmSync } from "fs"
import { homedir, tmpdir } from "os"
import { join } from "path"
import { decodeWav, resample } from "../wav"
import { WorkerSpeechModels, type FromWorker, type ToWorker, type WorkerLike } from "./speech-models"

/** A fake worker the test drives by hand. */
class FakeWorker implements WorkerLike {
  sent: ToWorker[] = []
  terminated = false
  onmessage: ((ev: MessageEvent<FromWorker>) => void) | null = null
  onerror: ((ev: ErrorEvent) => void) | null = null
  postMessage(msg: ToWorker) { this.sent.push(msg) }
  terminate() { this.terminated = true }
  reply(m: FromWorker) { this.onmessage?.({ data: m } as MessageEvent<FromWorker>) }
}

describe("WorkerSpeechModels (fake workers)", () => {
  test("probabilities arrive in order and flush waits for them", async () => {
    const workers: FakeWorker[] = []
    const models = new WorkerSpeechModels(() => { const w = new FakeWorker(); workers.push(w); return w })
    const got: number[] = []
    const vad = models.openVad((p) => got.push(...p), () => {})
    vad.push(new Int16Array(512))
    vad.push(new Int16Array(512))
    let flushed = false
    const f = vad.flush().then(() => { flushed = true })
    const w = workers[0]!
    expect(w.sent.map((m) => m.op)).toEqual(["init", "open", "push", "push"])
    const id = (w.sent[1] as { id: string }).id
    w.reply({ op: "vad", id, seq: 1, probs: new Float32Array([0.1]) })
    await Promise.resolve()
    expect(flushed).toBe(false)
    w.reply({ op: "vad", id, seq: 2, probs: new Float32Array([0.9]) })
    await f
    expect(got.map((x) => Math.round(x * 10) / 10)).toEqual([0.1, 0.9])
    models.dispose()
  })

  test("a worker that fails to load fails its streams and turn checks, then backs off", async () => {
    const workers: FakeWorker[] = []
    const models = new WorkerSpeechModels(() => { const w = new FakeWorker(); workers.push(w); return w })
    let vadErr: Error | null = null
    models.openVad(() => {}, (e) => { vadErr = e })
    const turn = models.turn(new Float32Array(16_000))
    workers[0]!.reply({ op: "failed", message: "no model" })
    workers[1]!.reply({ op: "failed", message: "no model" })
    expect(vadErr).not.toBeNull()
    await expect(turn).rejects.toThrow(/failed to load/)
    // Within the backoff: no new worker, an immediate failure instead.
    await expect(models.turn(new Float32Array(16_000))).rejects.toThrow(/failed to load/)
    expect(workers.length).toBe(2)
    models.dispose()
  })

  test("idle workers are stopped", async () => {
    const workers: FakeWorker[] = []
    const models = new WorkerSpeechModels(() => { const w = new FakeWorker(); workers.push(w); return w }, 20)
    const vad = models.openVad(() => {}, () => {})
    vad.close()
    await Bun.sleep(60)
    expect(workers[0]!.terminated).toBe(true)
  })
})

// The real worker, real models, real runtime. Slow-ish (the runtime and both models load).
describe("speech models (real)", () => {
  const models = new WorkerSpeechModels()
  afterAll(() => models.dispose())

  const score = async (pcm: Int16Array): Promise<Float32Array> => {
    const probs: number[] = []
    let err: Error | null = null
    const vad = models.openVad((p) => probs.push(...p), (e) => { err = e })
    for (let i = 0; i < pcm.length; i += 1_600) vad.push(pcm.subarray(i, i + 1_600))
    await vad.flush()
    vad.close()
    if (err) throw err
    return Float32Array.from(probs)
  }

  test("silence is not speech", async () => {
    const probs = await score(new Int16Array(16_000))
    expect(probs.length).toBe(31)
    expect(Math.max(...probs)).toBeLessThan(0.5)
  }, 30_000)

  test("Smart Turn answers with a probability", async () => {
    const p = await models.turn(new Float32Array(16_000 * 2))
    expect(p).toBeGreaterThanOrEqual(0)
    expect(p).toBeLessThanOrEqual(1)
  }, 30_000)

  // Speech needs a voice to say it: synthesised with piper where it is installed (not on CI).
  const piperModel = join(homedir(), ".cache/piper-voices/en/en_US-ryan-medium.onnx")
  const havePiper = existsSync(piperModel) && spawnSync("sh", ["-c", "command -v piper"]).status === 0
  test.skipIf(!havePiper)("speech is speech", async () => {
    const dir = mkdtempSync(join(tmpdir(), "speech-models-test-"))
    try {
      const wav = join(dir, "s.wav")
      const r = spawnSync("piper", ["--model", piperModel, "--output_file", wav], { input: "Can you check why the build failed on the Mac?" })
      expect(r.status).toBe(0)
      const audio = decodeWav(new Uint8Array(readFileSync(wav)))
      const f = resample(audio.samples, audio.sampleRate, 16_000)
      const pcm = Int16Array.from(f, (x) => Math.max(-32768, Math.min(32767, Math.round(x * 32767))))
      const probs = await score(pcm)
      const speechFrames = probs.filter((p) => p > 0.5).length
      expect(speechFrames).toBeGreaterThan(probs.length / 2)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  }, 60_000)
})
