// Digital-silence guard for uploaded dictation audio.
//
// A mic the OS has silently cut off does not error — it delivers exact zeros: macOS TCC without a
// usage string, Windows' microphone privacy toggle, Android 10+ when another app holds the mic.
// Cloud STT models do not return "" for that; they INVENT a fluent sentence (random language with
// no prompt, glossary-flavoured with one). So the broker measures the peak level first and
// never forwards silence to an engine.

import { readFile } from "fs/promises"
import { decodeWav, peakDbOf } from "./wav"

/** Real room noise sits far above this; exact zeros measure -Infinity. */
export const SILENCE_PEAK_DB = -80

/** Peak level of the file in dBFS. */
export type MeasureFn = (audioPath: string) => Promise<number>

const measureWav: MeasureFn = async (audioPath) => peakDbOf(decodeWav(new Uint8Array(await readFile(audioPath))).samples)

/** Peak level in dB, or null when it could not be measured (not a WAV the broker reads). */
export async function peakDb(audioPath: string, measure: MeasureFn = measureWav): Promise<number | null> {
  try {
    return await measure(audioPath)
  } catch {
    return null
  }
}

/** True only when the audio is measurably silent — an unmeasurable file is passed through. */
export async function isDigitalSilence(audioPath: string, measure?: MeasureFn): Promise<boolean> {
  const db = await peakDb(audioPath, measure)
  return db !== null && db <= SILENCE_PEAK_DB
}
