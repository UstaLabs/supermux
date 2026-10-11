import { expect, test } from "bun:test"
import { writeFileSync } from "fs"
import { mergeDictation, parseMergeOutput } from "./dictation-merge"
import type { RealtimeWs } from "./engines/codex-realtime"

function fakeWs(answer: Array<Record<string, unknown>>): { ws: RealtimeWs; sent: any[] } {
  const sent: any[] = []
  const listeners: Record<string, Array<(ev: any) => void>> = {}
  const ws: RealtimeWs = {
    readyState: 1,
    protocol: "realtime",
    send(data: string) {
      const m = JSON.parse(data)
      sent.push(m)
      if (m.type === "response.create") queueMicrotask(() => {
        for (const msg of answer) for (const l of listeners.message ?? []) l({ data: JSON.stringify(msg) })
      })
    },
    close() {},
    addEventListener(type, listener) {
      (listeners[type] ??= []).push(listener)
      if (type === "open") queueMicrotask(() => listener({}))
    },
  }
  return { ws, sent }
}

const pcmOf = (seconds: number) => (_cmd: string, args: string[]) => {
  writeFileSync(args[args.length - 1]!, Buffer.alloc(24000 * 2 * seconds))
  return { exited: Promise.resolve(0) }
}

test("parseMergeOutput: set_text args, JSON in the text stream, or plain text", () => {
  expect(parseMergeOutput('{"text":" A b. "}', "")).toBe("A b.")
  expect(parseMergeOutput(undefined, '{  \n "text": "From text" \n}')).toBe("From text")
  expect(parseMergeOutput(undefined, "Plain answer.")).toBe("Plain answer.")
  expect(parseMergeOutput(undefined, "")).toBeNull()
  expect(parseMergeOutput(undefined, "{broken")).toBeNull()
})

test("merge sends the current text and only the audio after fromMs, and returns the new text", async () => {
  const { ws, sent } = fakeWs([
    { type: "response.function_call_arguments.done", arguments: '{"text":"Meet on Tuesday."}' },
    { type: "response.done" },
  ])
  let mintBody: any
  const r = await mergeDictation({
    audioPath: "/tmp/in.webm",
    fromMs: 1_000,
    currentText: "Meet on Monday. Bring snacks.",
    languages: ["en"],
    readAuth: () => JSON.stringify({ tokens: { access_token: "at" } }),
    spawn: pcmOf(3),
    fetchFn: async (_u, init) => { mintBody = JSON.parse(String(init?.body)); return new Response(JSON.stringify({ value: "ek" })) },
    connectWs: () => ws,
  })
  expect(r).toEqual({ text: "Meet on Tuesday.", durationMs: 3_000 })
  expect(mintBody.session.model).toBe("gpt-realtime")
  expect(mintBody.session.tool_choice).toEqual({ type: "function", name: "set_text" })
  expect(mintBody.session.instructions).toContain("NOT an assistant")
  const item = sent.find((m) => m.type === "conversation.item.create").item
  expect(item.content[0].text).toContain("Meet on Monday. Bring snacks.")
  expect(Buffer.from(item.content[1].audio, "base64").length).toBe(24000 * 2 * 2)
})

test("nothing after fromMs: the text stands, no call is made", async () => {
  let minted = false
  const r = await mergeDictation({
    audioPath: "/tmp/in.webm",
    fromMs: 5_000,
    currentText: "Done.",
    readAuth: () => JSON.stringify({ tokens: { access_token: "at" } }),
    spawn: pcmOf(2),
    fetchFn: async () => { minted = true; return new Response("{}") },
  })
  expect(r).toEqual({ text: "Done.", durationMs: 2_000 })
  expect(minted).toBe(false)
})

test("an error from the model rejects", async () => {
  const { ws } = fakeWs([{ type: "error", error: { message: "nope" } }])
  await expect(mergeDictation({
    audioPath: "/tmp/in.webm",
    currentText: "",
    readAuth: () => JSON.stringify({ tokens: { access_token: "at" } }),
    spawn: pcmOf(1),
    fetchFn: async () => new Response(JSON.stringify({ value: "ek" })),
    connectWs: () => ws,
  })).rejects.toThrow("nope")
})
