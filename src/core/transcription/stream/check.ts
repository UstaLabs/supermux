// `supermux __speech-models-check`: start both speech-model workers the way a dictation does and
// score one stretch of audio with each — proof that a binary carries the worker, the WebAssembly
// runtime and both models (scripts/smoke-binary.sh check 6). v0.12.0 compiled, booted and served
// everything while missing the one file every session needed; this is that check for dictation.
// Prints one JSON line; exits 0 when both models answered.

import { WorkerSpeechModels } from "./speech-models"

const TIMEOUT_MS = 60_000

export async function runSpeechModelsCheck(): Promise<number> {
  const t0 = performance.now()
  const models = new WorkerSpeechModels()
  const timeout = new Promise<never>((_, reject) => setTimeout(() => reject(new Error(`no answer in ${TIMEOUT_MS} ms`)), TIMEOUT_MS).unref?.())
  try {
    const result = await Promise.race([timeout, (async () => {
      const probs: number[] = []
      let vadError: Error | null = null
      const vad = models.openVad((p) => probs.push(...p), (e) => { vadError = e })
      vad.push(new Int16Array(512 * 4))
      await vad.flush()
      vad.close()
      if (vadError) throw vadError
      const turn = await models.turn(new Float32Array(16_000))
      return { vadFrames: probs.length, turn }
    })()])
    const ok = result.vadFrames === 4 && result.turn >= 0 && result.turn <= 1
    console.log(JSON.stringify({ ok, ...result, ms: Math.round(performance.now() - t0) }))
    return ok ? 0 : 1
  } catch (e) {
    console.log(JSON.stringify({ ok: false, error: e instanceof Error ? e.message : String(e) }))
    return 1
  } finally {
    models.dispose()
  }
}
