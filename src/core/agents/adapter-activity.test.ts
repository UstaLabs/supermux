import { test, expect } from "bun:test"
import { toActivityEvents } from "./adapter-activity"

const NOW = 1730000000000
const ISO = new Date(NOW).toISOString()
const WD = "/w"

test("generic started tool uses path/command from detail", () => {
  const ev = { kind: "tool-call", tool: "Read", phase: "started", call_id: "x", detail: { path: "/w/a/b.ts" } } as const
  expect(toActivityEvents("claude", ev, NOW, WD)[0]).toMatchObject({
    ts: ISO, kind: "tool", tool: "Read", title: "Read: a/b.ts", detail: "/w/a/b.ts", phase: "started", callId: "x",
  })
})
