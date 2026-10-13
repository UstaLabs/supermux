import { test, expect } from "bun:test"
import { writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { isDigitalSilence, peakDb } from "./silence"
import { encodeWav16, toPcm16 } from "./wav"

const level = (db: number) => async () => db

test("all-zero audio is silence; quiet speech is not", async () => {
  expect(await isDigitalSilence("/x.wav", level(-Infinity))).toBe(true)
  expect(await isDigitalSilence("/x.wav", level(-91))).toBe(true)
  expect(await isDigitalSilence("/x.wav", level(-38.2))).toBe(false)
})

test("an unmeasurable file is passed through, never dropped", async () => {
  expect(await peakDb("/x.bin", async () => { throw new Error("not a WAV") })).toBeNull()
  const p = join(tmpdir(), `silence-test-${process.pid}.webm`)
  writeFileSync(p, new Uint8Array([0x1a, 0x45, 0xdf, 0xa3, 1, 2, 3, 4]))
  expect(await isDigitalSilence(p)).toBe(false)
})

test("a real zero-filled WAV measures as silence, a quiet one does not", async () => {
  const zeros = join(tmpdir(), `silence-test-${process.pid}-0.wav`)
  writeFileSync(zeros, encodeWav16(new Uint8Array(16000 * 2), 16000)) // 1 s of 16 kHz zeros
  expect(await isDigitalSilence(zeros)).toBe(true)
  const quiet = join(tmpdir(), `silence-test-${process.pid}-1.wav`)
  writeFileSync(quiet, encodeWav16(toPcm16(new Float32Array(1600).fill(0.001)), 16000)) // -60 dBFS
  expect(await isDigitalSilence(quiet)).toBe(false)
})
