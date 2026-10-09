import { test, expect } from "bun:test"
import { writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { isDigitalSilence, peakDb } from "./silence"

const out = (db: string) => async () => `[Parsed_volumedetect_0 @ 0x1] mean_volume: ${db} dB\n[Parsed_volumedetect_0 @ 0x1] max_volume: ${db} dB\n`

test("all-zero audio (-91 dB) is silence; quiet speech is not", async () => {
  expect(await isDigitalSilence("/x.wav", out("-91.0"))).toBe(true)
  expect(await isDigitalSilence("/x.wav", out("-inf"))).toBe(true)
  expect(await isDigitalSilence("/x.wav", out("-38.2"))).toBe(false)
})

test("an unmeasurable file is passed through, never dropped", async () => {
  expect(await peakDb("/x.bin", async () => "Invalid data found when processing input")).toBeNull()
  expect(await isDigitalSilence("/x.bin", async () => { throw new Error("no ffmpeg") })).toBe(false)
})

// A real-binary test, like the Xvfb/scrcpy ones: CI's runner image has no ffmpeg, and without one
// isDigitalSilence correctly answers "unmeasurable, pass it through" (false) — so skip, not fail.
const hasFfmpeg = Bun.which(process.env.MUX_FFMPEG_BIN ?? "ffmpeg") !== null

test.skipIf(!hasFfmpeg)("real ffmpeg: a zero-filled WAV measures as silence", async () => {
  const pcm = Buffer.alloc(16000 * 2) // 1 s of 16 kHz s16le zeros
  const h = Buffer.alloc(44)
  h.write("RIFF", 0); h.writeUInt32LE(36 + pcm.length, 4); h.write("WAVE", 8); h.write("fmt ", 12)
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22); h.writeUInt32LE(16000, 24)
  h.writeUInt32LE(32000, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34); h.write("data", 36); h.writeUInt32LE(pcm.length, 40)
  const p = join(tmpdir(), `silence-test-${process.pid}.wav`)
  writeFileSync(p, Buffer.concat([h, pcm]))
  expect(await isDigitalSilence(p)).toBe(true)
})
