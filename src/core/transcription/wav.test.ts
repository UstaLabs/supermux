import { test, expect } from "bun:test"
import { decodeWav, encodeWav16, isWav, peakDbOf, resample, toPcm16, UnsupportedAudioError } from "./wav"

/** A WAV with an arbitrary fmt chunk, for the encodings the broker's own encoder never writes. */
function wav(opts: { format?: number; channels?: number; rate?: number; bits?: number; data: Uint8Array; dataSize?: number; extraChunk?: boolean }): Uint8Array {
  const { format = 1, channels = 1, rate = 16000, bits = 16, data } = opts
  const extra = opts.extraChunk ? 8 + 3 + 1 : 0 // odd-sized LIST chunk + pad byte
  const out = new Uint8Array(44 + extra + data.length)
  const v = new DataView(out.buffer)
  const str = (at: number, s: string) => { for (let i = 0; i < s.length; i++) out[at + i] = s.charCodeAt(i) }
  str(0, "RIFF"); v.setUint32(4, out.length - 8, true); str(8, "WAVE")
  str(12, "fmt "); v.setUint32(16, 16, true); v.setUint16(20, format, true); v.setUint16(22, channels, true)
  v.setUint32(24, rate, true); v.setUint32(28, rate * channels * bits / 8, true); v.setUint16(32, channels * bits / 8, true); v.setUint16(34, bits, true)
  let p = 36
  if (opts.extraChunk) { str(p, "LIST"); v.setUint32(p + 4, 3, true); p += 12 }
  str(p, "data"); v.setUint32(p + 4, opts.dataSize ?? data.length, true)
  out.set(data, p + 8)
  return out
}

const s16 = (...xs: number[]) => { const b = new Uint8Array(xs.length * 2); const v = new DataView(b.buffer); xs.forEach((x, i) => v.setInt16(i * 2, x, true)); return b }

test("16-bit mono round-trips through encodeWav16/decodeWav", () => {
  const pcm = s16(0, 16384, -16384, 32767, -32768)
  const d = decodeWav(encodeWav16(pcm, 16000))
  expect(d.sampleRate).toBe(16000)
  expect(Array.from(d.samples)).toEqual([0, 0.5, -0.5, 32767 / 32768, -1])
  expect(toPcm16(d.samples)).toEqual(pcm)
})

test("stereo is averaged to mono", () => {
  const d = decodeWav(wav({ channels: 2, data: s16(16384, 0, -16384, -16384) }))
  expect(Array.from(d.samples)).toEqual([0.25, -0.5])
})

test("32-bit float, 8-bit and 24-bit PCM decode", () => {
  const f = new Uint8Array(8); new DataView(f.buffer).setFloat32(0, 0.25, true); new DataView(f.buffer).setFloat32(4, -1, true)
  expect(Array.from(decodeWav(wav({ format: 3, bits: 32, data: f })).samples)).toEqual([0.25, -1])
  expect(Array.from(decodeWav(wav({ bits: 8, data: new Uint8Array([128, 192, 0]) })).samples)).toEqual([0, 0.5, -1])
  expect(Array.from(decodeWav(wav({ bits: 24, data: new Uint8Array([0, 0, 0x40, 0, 0, 0xc0]) })).samples)).toEqual([0.5, -0.5])
})

test("a mid-recording snapshot with an unfinalized data size reads to the end of the file", () => {
  expect(decodeWav(wav({ data: s16(1, 2, 3), dataSize: 0 })).samples.length).toBe(3)
  expect(decodeWav(wav({ data: s16(1, 2, 3), dataSize: 0xffffffff })).samples.length).toBe(3)
  // A half-written trailing frame is dropped, not misread.
  expect(decodeWav(wav({ data: new Uint8Array([0, 0, 0]), dataSize: 0 })).samples.length).toBe(1)
})

test("chunks before data (odd-sized, padded) are skipped", () => {
  expect(Array.from(decodeWav(wav({ data: s16(16384), extraChunk: true })).samples)).toEqual([0.5])
})

test("webm, mp4 and compressed WAV are refused with an actionable error", () => {
  const webm = new Uint8Array([0x1a, 0x45, 0xdf, 0xa3, 0, 0, 0, 0, 0, 0, 0, 0])
  expect(isWav(webm)).toBe(false)
  expect(() => decodeWav(webm)).toThrow(UnsupportedAudioError)
  expect(() => decodeWav(webm)).toThrow(/expects 16-bit PCM WAV/)
  expect(() => decodeWav(wav({ format: 0x55, bits: 16, data: s16(1) }))).toThrow(/WAV format 85/)
})

test("resample keeps duration and a DC level, in both directions", () => {
  const one = new Float32Array(16000).fill(0.5)
  const up = resample(one, 16000, 24000)
  expect(up.length).toBe(24000)
  expect(up.every((x) => Math.abs(x - 0.5) < 1e-6)).toBe(true)
  const down = resample(new Float32Array(48000).fill(0.5), 48000, 16000)
  expect(down.length).toBe(16000)
  expect(down.every((x) => Math.abs(x - 0.5) < 1e-6)).toBe(true)
  expect(resample(one, 16000, 16000)).toBe(one)
})

test("downsampling averages away content above the new Nyquist", () => {
  // A 24 kHz tone at 48 kHz is +1,-1,+1,…; at 16 kHz it must not survive as a loud alias.
  const tone = Float32Array.from({ length: 4800 }, (_, i) => (i % 2 ? -1 : 1))
  const down = resample(tone, 48000, 16000)
  expect(Math.max(...Array.from(down, Math.abs))).toBeLessThanOrEqual(1 / 3 + 1e-6)
})

test("peakDbOf: silence is -Infinity, full scale is 0 dB", () => {
  expect(peakDbOf(new Float32Array(100))).toBe(-Infinity)
  expect(peakDbOf(Float32Array.from([0, -1, 0.5]))).toBe(0)
  expect(peakDbOf(Float32Array.from([0.1]))).toBeCloseTo(-20, 5)
})
