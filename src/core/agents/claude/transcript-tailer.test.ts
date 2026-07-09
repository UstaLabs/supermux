// src/core/agents/claude/transcript-tailer.test.ts
import { test, expect } from "bun:test"
import { TranscriptTailer } from "./transcript-tailer"
import { writeFileSync, appendFileSync, mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"

const TS = "2026-05-29T18:40:14.188Z"
function toolLine(cmd: string): string {
  return JSON.stringify({ type: "assistant", timestamp: TS, message: { content: [{ type: "tool_use", name: "Bash", input: { command: cmd } }] } }) + "\n"
}

test("ingest emits one event per complete line", () => {
  const got: string[] = []
  const t = new TranscriptTailer({ path: "/x", onEvent: (e) => got.push(e.title) })
  t.ingest(toolLine("a") + toolLine("b"))
  expect(got).toEqual(["Bash: a", "Bash: b"])
})

test("ingest buffers a partial line until its newline arrives", () => {
  const got: string[] = []
  const t = new TranscriptTailer({ path: "/x", onEvent: (e) => got.push(e.title) })
  const full = toolLine("hello")
  t.ingest(full.slice(0, 10))
  expect(got).toEqual([])
  t.ingest(full.slice(10))
  expect(got).toEqual(["Bash: hello"])
})

test("ingest skips malformed lines without throwing", () => {
  const got: string[] = []
  const t = new TranscriptTailer({ path: "/x", onEvent: (e) => got.push(e.title) })
  t.ingest("garbage\n" + toolLine("ok"))
  expect(got).toEqual(["Bash: ok"])
})

test("onLine receives every complete raw line before parsing", () => {
  const lines: string[] = []
  const t = new TranscriptTailer({ path: "/x", onEvent: () => {}, onLine: (l) => lines.push(l) })
  t.ingest('{"type":"user","message":{"content":"<task-notification>x</task-notification>"}}\npartial')
  expect(lines).toEqual(['{"type":"user","message":{"content":"<task-notification>x</task-notification>"}}'])
  t.ingest(" tail\n")
  expect(lines).toHaveLength(2)
})

test("readDelta reads incrementally, then handles truncation/rotation", () => {
  const dir = mkdtempSync(join(tmpdir(), "tailer-"))
  const path = join(dir, "t.jsonl")
  const got: string[] = []
  const t = new TranscriptTailer({ path, onEvent: (e) => got.push(e.title) })
  // watching must be true for readDelta to run; start() also calls readDelta once.
  t.start()
  // file does not exist yet -> no events
  expect(got).toEqual([])
  // first append, then a manual poll
  writeFileSync(path, toolLine("one"))
  ;(t as any).readDelta()
  expect(got).toEqual(["Bash: one"])
  // second append -> only the delta is read (offset advanced)
  appendFileSync(path, toolLine("two"))
  ;(t as any).readDelta()
  expect(got).toEqual(["Bash: one", "Bash: two"])
  // truncate/rotate: smaller file resets offset and re-reads from 0
  writeFileSync(path, toolLine("three"))
  ;(t as any).readDelta()
  expect(got).toEqual(["Bash: one", "Bash: two", "Bash: three"])
  t.stop()
  rmSync(dir, { recursive: true, force: true })
})
