// Digital-silence guard for uploaded dictation audio.
//
// A mic the OS has silently cut off does not error — it delivers exact zeros: macOS TCC without a
// usage string, Windows' microphone privacy toggle, Android 10+ when another app holds the mic.
// Cloud STT models do not return "" for that; they INVENT a fluent sentence (random language with
// no prompt, glossary-flavoured with one). So the broker measures the peak level first and
// never forwards silence to an engine.

import { spawn as bunSpawn } from "bun"

const FFMPEG_BIN = process.env.MUX_FFMPEG_BIN ?? "ffmpeg"
/** All-zero PCM measures -91 dB in ffmpeg; real room noise sits far above this. */
export const SILENCE_PEAK_DB = -80

export type MeasureFn = (audioPath: string) => Promise<string>

async function ffmpegVolumedetect(audioPath: string): Promise<string> {
  const p = bunSpawn([FFMPEG_BIN, "-hide_banner", "-nostats", "-i", audioPath, "-af", "volumedetect", "-f", "null", "-"], {
    stdout: "ignore",
    stderr: "pipe",
  })
  const err = await new Response(p.stderr).text()
  await p.exited
  return err
}

/** Peak level in dB, or null when it could not be measured (unknown format, no ffmpeg). */
export async function peakDb(audioPath: string, measure: MeasureFn = ffmpegVolumedetect): Promise<number | null> {
  try {
    const m = /max_volume:\s*(-?[\d.]+|-inf)\s*dB/.exec(await measure(audioPath))
    if (!m) return null
    return m[1] === "-inf" ? -Infinity : Number(m[1])
  } catch {
    return null
  }
}

/** True only when the audio is measurably silent — an unmeasurable file is passed through. */
export async function isDigitalSilence(audioPath: string, measure?: MeasureFn): Promise<boolean> {
  const db = await peakDb(audioPath, measure)
  return db !== null && db <= SILENCE_PEAK_DB
}
