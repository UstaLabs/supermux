// The dictation stream's speech models, in a worker thread so their inference never runs on the
// broker's main thread: a Smart Turn check takes ~0.4 s on the WebAssembly runtime, and Silero
// scores every 32 ms of every live dictation. One worker per model (`init` names the role):
// sharing one saved ~87 MB, but on the loaded box a Smart Turn check took up to 5 s, and the
// speech frames queued behind it put the pause detector 1.4 s behind the audio (2026-10-31).
//
// The runtime is onnxruntime-web's WebAssembly build, not onnxruntime-node: it works inside a
// `bun build --compile` binary (the `/wasm` entry inlines its glue and the .wasm is embedded as a
// file), identically on every OS and arch the broker ships for. onnxruntime-node's 48 MB native
// library is NOT embedded by `--compile` (measured 2026-10-31).
//
// A compiled binary must list this file as an extra entrypoint (scripts/build-binary.sh).

import { readFileSync } from "fs"
import { extractFeatures } from "@micdrop/smart-turn"
import * as ort from "onnxruntime-web/wasm"
import wasmPath from "onnxruntime-web/ort-wasm-simd-threaded.wasm" with { type: "file" }
import sileroPath from "./models/silero_vad_v5.onnx" with { type: "file" }
import smartTurnPath from "./models/smart-turn-v3.2-cpu.onnx" with { type: "file" }
import type { ToWorker, FromWorker, ModelRole } from "./speech-models"

declare var self: Worker

/** Silero v5 at 16 kHz: 512 new samples per frame, plus the previous frame's last 64 as context. */
const FRAME = 512
const CONTEXT = 64

ort.env.logLevel = "error"
ort.env.wasm.numThreads = 1
ort.env.wasm.wasmBinary = readFileSync(wasmPath)

let silero: ort.InferenceSession | null = null
let smartTurn: ort.InferenceSession | null = null

/** Each dictation's detector state: Silero is recurrent, so its state carries from frame to frame. */
interface VadState {
  state: Float32Array
  context: Float32Array
  /** Samples received but not yet a whole frame. */
  carry: Float32Array
}
const streams = new Map<string, VadState>()

const post = (msg: FromWorker, transfer?: ArrayBuffer[]) => self.postMessage(msg, transfer ?? [])

async function load(role: ModelRole): Promise<void> {
  // The first inference is several times slower than the rest (~1.4 s vs ~0.4 s for Smart Turn
  // on the GMKtec box): pay it here, not at the user's first pause.
  if (role === "vad") {
    silero = await ort.InferenceSession.create(readFileSync(sileroPath))
    await vadFrames({ state: new Float32Array(256), context: new Float32Array(CONTEXT), carry: new Float32Array(0) }, new Float32Array(FRAME))
  } else {
    smartTurn = await ort.InferenceSession.create(readFileSync(smartTurnPath))
    await turnProbability(new Float32Array(16_000))
  }
}

/** Score every whole frame in [samples] (appended to the stream's carry), in order. */
async function vadFrames(s: VadState, samples: Float32Array): Promise<Float32Array> {
  const all = new Float32Array(s.carry.length + samples.length)
  all.set(s.carry, 0)
  all.set(samples, s.carry.length)
  const n = Math.floor(all.length / FRAME)
  const probs = new Float32Array(n)
  const sr = new ort.Tensor("int64", BigInt64Array.from([16_000n]), [])
  for (let i = 0; i < n; i++) {
    const frame = all.subarray(i * FRAME, (i + 1) * FRAME)
    const input = new Float32Array(CONTEXT + FRAME)
    input.set(s.context, 0)
    input.set(frame, CONTEXT)
    const out = await silero!.run({
      input: new ort.Tensor("float32", input, [1, CONTEXT + FRAME]),
      state: new ort.Tensor("float32", s.state, [2, 1, 128]),
      sr,
    })
    probs[i] = (out.output!.data as Float32Array)[0]!
    s.state = new Float32Array(out.stateN!.data as Float32Array)
    s.context = frame.slice(FRAME - CONTEXT)
  }
  s.carry = all.slice(n * FRAME)
  return probs
}

async function turnProbability(audio: Float32Array): Promise<number> {
  const features = extractFeatures(audio)
  const frames = features.length / 80
  const out = await smartTurn!.run({ input_features: new ort.Tensor("float32", features, [1, 80, frames]) })
  const first = out[smartTurn!.outputNames[0]!]!
  return (first.data as Float32Array)[0]!
}

async function handle(msg: ToWorker): Promise<void> {
  switch (msg.op) {
    case "init":
      try {
        await load(msg.role)
        post({ op: "ready" })
      } catch (e) {
        post({ op: "failed", message: e instanceof Error ? e.message : String(e) })
      }
      return
    case "open":
      streams.set(msg.id, { state: new Float32Array(256), context: new Float32Array(CONTEXT), carry: new Float32Array(0) })
      return
    case "close":
      streams.delete(msg.id)
      return
    case "push": {
      const s = streams.get(msg.id)
      if (!s) return
      const f = new Float32Array(msg.pcm.length)
      for (let i = 0; i < f.length; i++) f[i] = msg.pcm[i]! / 32768
      try {
        const probs = await vadFrames(s, f)
        post({ op: "vad", id: msg.id, seq: msg.seq, probs }, [probs.buffer as ArrayBuffer])
      } catch (e) {
        post({ op: "error", id: msg.id, message: e instanceof Error ? e.message : String(e) })
      }
      return
    }
    case "turn": {
      const t0 = performance.now()
      try {
        const p = await turnProbability(msg.audio)
        post({ op: "turn", reqId: msg.reqId, p, ms: Math.round(performance.now() - t0) })
      } catch (e) {
        post({ op: "error", reqId: msg.reqId, message: e instanceof Error ? e.message : String(e) })
      }
      return
    }
  }
}

// One message at a time, in arrival order: a frame's probability depends on the state the frame
// before it left behind, and the runtime is single-threaded anyway.
let queue: Promise<void> = Promise.resolve()
self.onmessage = (ev: MessageEvent<ToWorker>) => {
  queue = queue.then(() => handle(ev.data))
}
