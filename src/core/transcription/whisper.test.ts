import { test, expect } from "bun:test"
import { readFileSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { transcribeAudio } from "./whisper"
import { decodeWav, encodeWav16 } from "./wav"

const dir = tmpdir()
const tmpWav = join(dir, `whisper-test-${process.pid}-16k.wav`)
const tmpOut = join(dir, `whisper-test-${process.pid}`)

/** A 48 kHz upload, so the rewrite to whisper's 16 kHz is visible. */
function clip48k(): string {
  const p = join(dir, `whisper-test-${process.pid}-48k.wav`)
  writeFileSync(p, encodeWav16(new Uint8Array(48000 * 2), 48000)) // 1 s
  return p
}

test("writes a 16 kHz WAV itself, then runs only whisper-cli, and returns trimmed text", async () => {
  const calls: string[][] = []
  const res = await transcribeAudio(clip48k(), {
    model: "/models/ggml-small.bin", lang: "auto",
    spawn: (cmd, args) => { calls.push([cmd, ...args]); return { exited: Promise.resolve(0) } },
    readText: async () => "  hello world \n",
    tmpWav, tmpOut,
  })
  expect(res.text).toBe("hello world")
  expect(calls.length).toBe(1)
  expect(calls[0]!.join(" ")).toContain("whisper-cli")
  expect(calls[0]!.join(" ")).toContain("-m /models/ggml-small.bin")
  expect(calls[0]!.join(" ")).toContain(`-f ${tmpWav}`)
  expect(calls[0]!.join(" ")).toContain("-otxt")
  const written = decodeWav(new Uint8Array(readFileSync(tmpWav)))
  expect(written.sampleRate).toBe(16000)
  expect(written.samples.length).toBe(16000)
})

test("empty/whitespace transcript returns empty string", async () => {
  const res = await transcribeAudio(clip48k(), {
    model: "/m.bin", spawn: () => ({ exited: Promise.resolve(0) }),
    readText: async () => "   \n", tmpWav, tmpOut,
  })
  expect(res.text).toBe("")
})

test("nonzero whisper-cli exit throws", async () => {
  await expect(transcribeAudio(clip48k(), {
    model: "/m.bin", spawn: () => ({ exited: Promise.resolve(1) }),
    readText: async () => "x", tmpWav, tmpOut,
  })).rejects.toThrow(/whisper-cli failed/)
})

test("a recording that is not WAV is refused without spawning anything", async () => {
  const webm = join(dir, `whisper-test-${process.pid}.webm`)
  writeFileSync(webm, new Uint8Array([0x1a, 0x45, 0xdf, 0xa3, 1, 2, 3, 4]))
  const calls: string[][] = []
  await expect(transcribeAudio(webm, {
    model: "/m.bin", spawn: (cmd, args) => { calls.push([cmd, ...args]); return { exited: Promise.resolve(0) } },
    readText: async () => "x", tmpWav, tmpOut,
  })).rejects.toThrow(/expects 16-bit PCM WAV/)
  expect(calls).toEqual([])
})
