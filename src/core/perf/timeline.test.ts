import { expect, test } from "bun:test"
import type { PerfEvent } from "./perf-log"
import { latestTrace, listTraces, parsePerfLines, renderChat, renderLaunch, renderWs } from "./timeline"

// One launch as it lands in perf.jsonl: the app's events (src client, t = ms since launch, ts on the
// broker's clock) and the broker's (src broker), paired by req.
const BASE = 1_762_000_000_000
const launch: PerfEvent[] = [
  { ts: BASE + 0, src: "client", event: "process.start", trace: "LAAAAAA", t: 0, uptimeMs: 412, device: "mac", surface: "desktop", version: "0.17.1" },
  { ts: BASE + 118, src: "client", event: "host.init", trace: "LAAAAAA", t: 118, device: "mac", surface: "desktop" },
  { ts: BASE + 120, src: "client", event: "ws.connecting", trace: "LAAAAAA", t: 120, req: "w1", attempt: 0, device: "mac" },
  { ts: BASE + 150, src: "broker", event: "ws.upgrade", trace: "LAAAAAA", req: "w1", device: "mac", authMs: 0.4 },
  { ts: BASE + 161, src: "client", event: "ws.open", trace: "LAAAAAA", t: 161, req: "w1", handshakeMs: 41, device: "mac" },
  { ts: BASE + 162, src: "client", event: "ws.subscribe.sent", trace: "LAAAAAA", t: 162, req: "w1", device: "mac" },
  { ts: BASE + 870, src: "broker", event: "snapshot.built", trace: "LAAAAAA", req: "w1", device: "mac", ms: 702, bytes: 2_430_000, sessions: 150, workspaces: 141, archivedWorkspaces: 1435,
    laps: { sessions: 4, perSession: 61, attachments: 2, workspaces: 25, archivedWorkspaces: 233, projects: 98, rest: 3, stringify: 41, send: 30 },
    keyBytes: { subagents: 881_000, archivedWorkspaces: 767_000, logs: 229_000, sessions: 105_000 } },
  { ts: BASE + 1001, src: "client", event: "ws.snapshot.arrived", trace: "LAAAAAA", t: 1001, req: "w1", chars: 2_430_000, sinceSubscribeMs: 839, device: "mac" },
  { ts: BASE + 1186, src: "client", event: "ws.snapshot.decoded", trace: "LAAAAAA", t: 1186, req: "w1", decodeMs: 185, device: "mac" },
  { ts: BASE + 1231, src: "client", event: "state.snapshot.reduced", trace: "LAAAAAA", t: 1231, ms: 45, sessions: 150, device: "mac" },
  { ts: BASE + 1240, src: "client", event: "http", trace: "LAAAAAA", t: 1240, req: "r5", method: "GET", path: "/archived-sessions", status: 200, bytes: 431_000, ms: 260, serverMs: 159, device: "mac" },
  { ts: BASE + 1300, src: "broker", event: "http", trace: "LAAAAAA", req: "r5", method: "GET", path: "/archived-sessions", route: "/archived-sessions", status: 200, bytes: 431_000, ms: 159, device: "mac" },
  { ts: BASE + 1290, src: "client", event: "http.body", trace: "LAAAAAA", t: 1290, req: "r5", chars: 431_000, bodyMs: 20, decodeMs: 30, device: "mac" },
  { ts: BASE + 1622, src: "client", event: "ui.sessions.drawn", trace: "LAAAAAA", t: 1622, sessions: 150, device: "mac" },
  { ts: BASE + 1625, src: "client", event: "ui.workspaces.drawn", trace: "LAAAAAA", t: 1625, workspaces: 141, device: "mac" },
  { ts: BASE + 1625, src: "client", event: "ui.agents.drawn", trace: "LAAAAAA", t: 1625, agents: 12, pictures: 0, device: "mac" },
  { ts: BASE + 3100, src: "client", event: "avatars.loaded", trace: "LAAAAAA", t: 3100, fetched: 12, ms: 1400, device: "mac" },
]

const chat: PerfEvent[] = [
  { ts: BASE + 9000, src: "client", event: "chat.open", trace: "CAAAAAA.1", t: 9000, session: "5fe66c32-94d9", name: "Tally the TPM", loaded: false, messages: 1, device: "mac" },
  { ts: BASE + 9002, src: "client", event: "chat.fetch", trace: "CAAAAAA.1", t: 9002, session: "5fe66c32-94d9", logs: true, extras: true, device: "mac" },
  { ts: BASE + 9060, src: "broker", event: "http", trace: "CAAAAAA.1", req: "r9", method: "GET", route: "/sessions/:id/messages", path: "/sessions/5fe66c32-94d9/messages", status: 200, bytes: 184_000, ms: 60, count: 200, logMs: 3, enrichMs: 1, device: "mac" },
  { ts: BASE + 9080, src: "client", event: "http", trace: "CAAAAAA.1", t: 9080, req: "r9", method: "GET", path: "/sessions/5fe66c32-94d9/messages", status: 200, bytes: 184_000, ms: 78, serverMs: 60, device: "mac" },
  { ts: BASE + 9102, src: "client", event: "http.body", trace: "CAAAAAA.1", t: 9102, req: "r9", chars: 184_000, bodyMs: 4, decodeMs: 18, device: "mac" },
  { ts: BASE + 9112, src: "client", event: "chat.loaded", trace: "CAAAAAA.1", t: 9112, ms: 110, messages: 200, activity: 0, device: "mac" },
  { ts: BASE + 9130, src: "client", event: "chat.firstFrame", trace: "CAAAAAA.1", t: 9130, items: 1, device: "mac" },
  { ts: BASE + 9135, src: "client", event: "chat.complete", trace: "CAAAAAA.1", t: 9135, items: 212, device: "mac" },
]

test("reads perf.jsonl and skips lines that are not events", () => {
  const text = [JSON.stringify(launch[0]), "", "not json", JSON.stringify(chat[0])].join("\n")
  expect(parsePerfLines(text).map((e) => e.event)).toEqual(["process.start", "chat.open"])
})

test("finds the newest launch and the newest chat open, optionally for one device", () => {
  const other: PerfEvent = { ts: BASE - 50_000, src: "client", event: "host.init", trace: "LBBBBBB", t: 3, device: "fold" }
  const all = [other, ...launch, ...chat]
  expect(latestTrace(all, "launch")).toBe("LAAAAAA")
  expect(latestTrace(all, "launch", "fold")).toBe("LBBBBBB")
  expect(latestTrace(all, "chat")).toBe("CAAAAAA.1")
  expect(listTraces(all).map((t) => t.trace)).toEqual(["LBBBBBB", "LAAAAAA", "CAAAAAA.1"])
})

test("a launch reads top to bottom in app time, with the broker's half under the step that asked for it", () => {
  const out = renderLaunch(launch, "LAAAAAA")
  const lines = out.split("\n")
  const sub = lines.findIndex((l) => l.includes("ws.subscribe.sent"))
  expect(lines[sub + 1]).toContain("broker")
  expect(lines[sub + 1]).toContain("702 ms")
  expect(out).toContain("archivedWorkspaces 233")
  expect(out).toContain("subagents 860 KB")
  // The snapshot's transfer = what the app waited minus what the broker spent.
  expect(out).toMatch(/network ~137 ms/)
  expect(out).toContain("GET /archived-sessions")
  expect(out).toContain("ui.agents.drawn")
  expect(out.indexOf("ui.sessions.drawn")).toBeLessThan(out.indexOf("avatars.loaded"))
  // One line that answers "how long until I see my workspaces and Agents".
  expect(out).toMatch(/workspaces on screen at 1\.6 s/)
  expect(out).toMatch(/Agents at 1\.6 s, their pictures at 3\.1 s/)
})

test("a chat open shows each request with the broker's time and where the rest went", () => {
  const out = renderChat(chat, "CAAAAAA.1")
  expect(out).toContain("Tally the TPM")
  expect(out).toContain("GET /sessions/:id/messages")
  expect(out).toContain("broker 60 ms")
  expect(out).toContain("sqlite 3")
  expect(out).toMatch(/history on screen at 130 ms, all of it at 135 ms/)
})

test("websocket stats add up per frame type over the window", () => {
  const ws: PerfEvent[] = [
    { ts: BASE, src: "broker", event: "ws.frames", periodMs: 60_000, clients: 3, frames: { activity_append: { frames: 120, bytes: 68_000, sends: 360 }, agent_state: { frames: 30, bytes: 6_000, sends: 90 } }, loopLag: { ticks: 120, maxMs: 160, over50ms: 2 } },
    { ts: BASE + 60_000, src: "broker", event: "ws.frames", periodMs: 60_000, clients: 3, frames: { activity_append: { frames: 80, bytes: 40_000, sends: 240 } }, loopLag: { ticks: 120, maxMs: 20, over50ms: 0 } },
    { ts: BASE + 60_000, src: "client", event: "client.stats", trace: "LAAAAAA", t: 60_000, device: "mac", frames: { ActivityAppend: { n: 200, chars: 108_000, decodeMs: 90.5, maxDecodeMs: 4.1 } }, reduce: { ActivityAppend: { n: 200, ms: 40.2, maxMs: 2 } }, recompose: { shell: 410, chat: 380 } },
  ]
  const out = renderWs(ws)
  expect(out).toMatch(/activity_append\s+200 frames\s+105 KB/)
  expect(out).toContain("event loop: worst 160 ms late")
  expect(out).toContain("shell 410")
})
