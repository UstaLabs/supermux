import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"
import type { Comment } from "../src/core/review/store"

const DEV_PATH = `/tmp/devices-wt-${process.pid}.json`
const PORT = 18797
let ch: WebChannel
let token: string
let comments: Comment[]
let delivered: string[]
let frames: object[]

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  comments = []
  delivered = []
  frames = []
  const store = new DeviceStore(DEV_PATH)
  token = store.mint("test-device").token
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    staticDir: undefined,
    getSessionsSnapshot: () => [{ name: "ana", workdir: "/h", mute: false, connected: true, agent: "claude" as const }],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    reviewAdd: (id, c) => {
      const comment = { id: "c1", sessionId: id, status: "open" as const, ...c }
      comments.push(comment)
      frames.push({ type: "review_comment", sessionId: id, comment })
      return comment
    },
    sendUserMessage: async (_id, text) => { delivered.push(text); return { ok: true } },
    getWalkthrough: (id) => id === "s1" ? {
      id: "w1", sessionId: "s1", title: "Auth", baseSpec: "", revision: 1, createdAt: "t", isCurrent: true,
      steps: [{ id: "st1", walkthroughId: "w1", ord: 0, title: "Login", bodyMd: "x", path: "a.ts", side: "RIGHT" as const, anchorLine: 4, rangeStart: 4, rangeEnd: 8, anchorStatus: "ok" as const }],
    } : undefined,
  })
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

test("GET /sessions/:id/walkthrough returns null when none", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions/missing/walkthrough`, {
    headers: { Cookie: `cmux_token=${token}` },
  })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ walkthrough: null })
})

test("GET /sessions/:id/walkthrough returns the current walkthrough", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions/s1/walkthrough`, {
    headers: { Cookie: `cmux_token=${token}` },
  })
  const body = await res.json() as { walkthrough: { title: string; steps: unknown[] } }
  expect(body.walkthrough.title).toBe("Auth")
  expect(body.walkthrough.steps).toHaveLength(1)
})

test("POST review comment with deliver instant sends the formatted agent message", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/sessions/s1/review/comments`, {
    method: "POST",
    headers: { Cookie: `cmux_token=${token}`, "content-type": "application/json" },
    body: JSON.stringify({
      repo: "", path: "a.ts", side: "RIGHT", anchorLine: 4, anchorContext: "x",
      body: "why this?", deliver: "instant",
    }),
  })
  expect(res.status).toBe(200)
  expect(delivered).toHaveLength(1)
  expect(delivered[0]).toContain("💬 Walkthrough comment c1 on a.ts:4 (step 1 \"Login\"):")
  expect(delivered[0]).toContain("\"why this?\"")
})

test("POST review comment without deliver does not send", async () => {
  await fetch(`http://127.0.0.1:${PORT}/sessions/s1/review/comments`, {
    method: "POST",
    headers: { Cookie: `cmux_token=${token}`, "content-type": "application/json" },
    body: JSON.stringify({
      repo: "", path: "a.ts", side: "RIGHT", anchorLine: 4, anchorContext: "x", body: "batch",
    }),
  })
  expect(delivered).toHaveLength(0)
})
