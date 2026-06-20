import { test, expect } from "bun:test"
import { transcribeAudio } from "./whisper"

test("runs ffmpeg then whisper-cli and returns trimmed text", async () => {
  const calls: string[][] = []
  const res = await transcribeAudio("/tmp/in.webm", {
    model: "/models/ggml-small.bin", lang: "auto",
    spawn: (cmd, args) => { calls.push([cmd, ...args]); return { exited: Promise.resolve(0) } },
    readText: async () => "  hello world \n",
    tmpWav: "/tmp/x.wav", tmpOut: "/tmp/x",
  })
  expect(res.text).toBe("hello world")
  expect(calls[0]!.join(" ")).toContain("ffmpeg")
  expect(calls[0]!.join(" ")).toContain("-ar 16000")
  expect(calls[0]!.join(" ")).toContain("-ac 1")
  expect(calls[1]!.join(" ")).toContain("whisper-cli")
  expect(calls[1]!.join(" ")).toContain("-m /models/ggml-small.bin")
  expect(calls[1]!.join(" ")).toContain("-otxt")
})

test("empty/whitespace transcript returns empty string", async () => {
  const res = await transcribeAudio("/tmp/in.webm", {
    model: "/m.bin", spawn: () => ({ exited: Promise.resolve(0) }),
    readText: async () => "   \n", tmpWav: "/tmp/x.wav", tmpOut: "/tmp/x",
  })
  expect(res.text).toBe("")
})

test("nonzero ffmpeg exit throws", async () => {
  await expect(transcribeAudio("/tmp/in.webm", {
    model: "/m.bin", spawn: () => ({ exited: Promise.resolve(1) }),
    readText: async () => "x", tmpWav: "/tmp/x.wav", tmpOut: "/tmp/x",
  })).rejects.toThrow()
})
