// In-process WAV handling for dictation audio — what used to be an `ffmpeg` subprocess.
//
// Every client records 16-bit PCM WAV (Android/desktop `wavFromPcm16`, iOS AVAudioRecorder, the
// web app's Web Audio capture), so the broker only needs to read a RIFF/WAVE file, mix it to mono
// and change its sample rate. Doing that here removes a host binary that was never checked for
// (issue #18: every voice message failed on a host without ffmpeg).

import { readFile } from "fs/promises"

/** A recording the broker cannot read: not WAV, or a WAV encoding it does not decode. */
export class UnsupportedAudioError extends Error {
  constructor(detail: string) {
    super(`unsupported audio: ${detail} (voice dictation expects 16-bit PCM WAV; update the app)`)
    this.name = "UnsupportedAudioError"
  }
}

export interface DecodedAudio {
  sampleRate: number
  /** Mono samples in -1..1. */
  samples: Float32Array
}

const FORMAT_PCM = 1
const FORMAT_FLOAT = 3
const FORMAT_EXTENSIBLE = 0xfffe

const tag = (b: Uint8Array, at: number) => String.fromCharCode(b[at]!, b[at + 1]!, b[at + 2]!, b[at + 3]!)

export function isWav(bytes: Uint8Array): boolean {
  return bytes.length >= 12 && tag(bytes, 0) === "RIFF" && tag(bytes, 8) === "WAVE"
}

/**
 * Decode a RIFF/WAVE file to mono float samples. Integer PCM (8/16/24/32-bit) and 32-bit float are
 * read; channels are averaged. A `data` chunk whose declared size is 0 or past the end of the file
 * — a recording snapshotted before its header was finalized — is read to the end of the file.
 */
export function decodeWav(bytes: Uint8Array): DecodedAudio {
  if (!isWav(bytes)) throw new UnsupportedAudioError("not a WAV file")
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  let fmt: { format: number; channels: number; sampleRate: number; bits: number } | null = null
  let p = 12
  while (p + 8 <= bytes.length) {
    const id = tag(bytes, p)
    const size = view.getUint32(p + 4, true)
    const body = p + 8
    if (id === "fmt ") {
      if (size < 16 || body + 16 > bytes.length) throw new UnsupportedAudioError("truncated fmt chunk")
      let format = view.getUint16(body, true)
      // WAVE_FORMAT_EXTENSIBLE carries the real format in the first two bytes of its SubFormat GUID.
      if (format === FORMAT_EXTENSIBLE && size >= 26 && body + 26 <= bytes.length) format = view.getUint16(body + 24, true)
      fmt = { format, channels: view.getUint16(body + 2, true), sampleRate: view.getUint32(body + 4, true), bits: view.getUint16(body + 14, true) }
    } else if (id === "data") {
      if (!fmt) throw new UnsupportedAudioError("data before fmt chunk")
      const end = size === 0 || body + size > bytes.length ? bytes.length : body + size
      return { sampleRate: fmt.sampleRate, samples: toMonoFloat(view, body, end, fmt) }
    }
    p = body + size + (size & 1) // chunks are word-aligned
  }
  throw new UnsupportedAudioError(fmt ? "no data chunk" : "no fmt chunk")
}

function toMonoFloat(
  view: DataView,
  start: number,
  end: number,
  fmt: { format: number; channels: number; sampleRate: number; bits: number },
): Float32Array {
  const { format, channels, bits } = fmt
  if (channels < 1 || fmt.sampleRate < 1) throw new UnsupportedAudioError("bad fmt chunk")
  const read = sampleReader(view, format, bits)
  const width = bits / 8
  const frame = width * channels
  const frames = Math.floor((end - start) / frame)
  const out = new Float32Array(frames)
  for (let f = 0, at = start; f < frames; f++, at += frame) {
    let sum = 0
    for (let c = 0; c < channels; c++) sum += read(at + c * width)
    out[f] = sum / channels
  }
  return out
}

function sampleReader(view: DataView, format: number, bits: number): (at: number) => number {
  if (format === FORMAT_FLOAT && bits === 32) return (at) => view.getFloat32(at, true)
  if (format === FORMAT_PCM) {
    if (bits === 16) return (at) => view.getInt16(at, true) / 32768
    if (bits === 8) return (at) => (view.getUint8(at) - 128) / 128
    if (bits === 24) return (at) => (((view.getUint8(at + 2) << 24) | (view.getUint8(at + 1) << 16) | (view.getUint8(at) << 8)) >> 8) / 8388608
    if (bits === 32) return (at) => view.getInt32(at, true) / 2147483648
  }
  throw new UnsupportedAudioError(`WAV format ${format} at ${bits} bits`)
}

/**
 * Change the sample rate. Upsampling interpolates linearly; downsampling averages each output
 * sample's input window, which is the low-pass that keeps speech from aliasing.
 */
export function resample(samples: Float32Array, from: number, to: number): Float32Array {
  if (from === to || samples.length === 0) return samples
  const ratio = from / to
  const out = new Float32Array(Math.floor(samples.length / ratio))
  if (ratio < 1) {
    for (let i = 0; i < out.length; i++) {
      const x = i * ratio
      const j = Math.floor(x)
      const t = x - j
      out[i] = samples[j]! * (1 - t) + (samples[Math.min(j + 1, samples.length - 1)]! * t)
    }
  } else {
    for (let i = 0; i < out.length; i++) {
      const a = Math.floor(i * ratio)
      const b = Math.min(Math.max(a + 1, Math.floor((i + 1) * ratio)), samples.length)
      let sum = 0
      for (let j = a; j < b; j++) sum += samples[j]!
      out[i] = sum / (b - a)
    }
  }
  return out
}

/** Float samples → 16-bit signed little-endian PCM. */
export function toPcm16(samples: Float32Array): Uint8Array {
  const out = new Uint8Array(samples.length * 2)
  const view = new DataView(out.buffer)
  for (let i = 0; i < samples.length; i++) {
    // The inverse of decodeWav's /32768, so 16-bit audio passes through bit-exact.
    view.setInt16(i * 2, Math.max(-32768, Math.min(32767, Math.round(samples[i]! * 32768))), true)
  }
  return out
}

/** Mono 16-bit PCM behind a canonical 44-byte WAV header. */
export function encodeWav16(pcm: Uint8Array, sampleRate: number): Uint8Array {
  const out = new Uint8Array(44 + pcm.length)
  const view = new DataView(out.buffer)
  const str = (at: number, s: string) => { for (let i = 0; i < s.length; i++) out[at + i] = s.charCodeAt(i) }
  str(0, "RIFF"); view.setUint32(4, 36 + pcm.length, true); str(8, "WAVE")
  str(12, "fmt "); view.setUint32(16, 16, true); view.setUint16(20, FORMAT_PCM, true); view.setUint16(22, 1, true)
  view.setUint32(24, sampleRate, true); view.setUint32(28, sampleRate * 2, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true)
  str(36, "data"); view.setUint32(40, pcm.length, true)
  out.set(pcm, 44)
  return out
}

/** Peak level in dBFS; -Infinity for all-zero audio. */
export function peakDbOf(samples: Float32Array): number {
  let peak = 0
  for (let i = 0; i < samples.length; i++) {
    const a = Math.abs(samples[i]!)
    if (a > peak) peak = a
  }
  return peak === 0 ? -Infinity : 20 * Math.log10(peak)
}

/** Read a WAV file as mono 16-bit PCM at [sampleRate] — what the streaming STT engines send. */
export async function readPcm16(audioPath: string, sampleRate: number): Promise<Buffer> {
  const { samples, sampleRate: from } = decodeWav(new Uint8Array(await readFile(audioPath)))
  const pcm = toPcm16(resample(samples, from, sampleRate))
  return Buffer.from(pcm.buffer, pcm.byteOffset, pcm.byteLength)
}
