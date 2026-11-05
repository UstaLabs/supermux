import { expect, test } from "bun:test"
import type { PerfEvent } from "./perf-log"
import { latestTrace, listTraces, parsePerfLines, renderChat, renderLaunch, renderStart, renderStartSummary, renderWs } from "./timeline"

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

// Card #75: one session start, app and broker, paired by the POST /sessions request id.
const S = "SAAAAAA.1"
const start: PerfEvent[] = [
  { ts: BASE + 20_000, src: "client", event: "start.send", trace: S, t: 20_000, agent: "claude", worktree: true, device: "mac", surface: "desktop" },
  { ts: BASE + 20_040, src: "broker", event: "start.request", trace: S, via: "launcher", agent: "claude", worktree: true, firstMessage: true },
  { ts: BASE + 20_041, src: "broker", event: "start.account", trace: S, ms: 1.2, auto: true, account: "system" },
  { ts: BASE + 20_090, src: "broker", event: "start.repo", trace: S, ms: 48, eligible: true },
  { ts: BASE + 20_110, src: "broker", event: "start.worktree.branches", trace: S, ms: 20, count: 412 },
  { ts: BASE + 21_310, src: "broker", event: "start.worktree.add", trace: S, ms: 1_200, branch: "mux/x" },
  { ts: BASE + 21_610, src: "broker", event: "start.worktree.includes", trace: S, ms: 300, copied: 2 },
  { ts: BASE + 21_611, src: "broker", event: "start.worktree.hook", trace: S, ms: 0.2, present: false },
  { ts: BASE + 21_612, src: "broker", event: "start.worktree", trace: S, ms: 1_521 },
  { ts: BASE + 21_700, src: "broker", event: "start.host.prepared", trace: S, ms: 80, session: "s1" },
  { ts: BASE + 21_900, src: "broker", event: "start.keeper.spawned", trace: S, ms: 150, session: "s1" },
  { ts: BASE + 24_100, src: "broker", event: "start.claude.initialized", trace: S, ms: 2_150, commands: 39, session: "s1" },
  { ts: BASE + 24_120, src: "broker", event: "start.spawned", trace: S, ms: 2_480, session: "s1" },
  { ts: BASE + 24_300, src: "broker", event: "start.telegram", trace: S, ms: 180, where: "spawn" },
  { ts: BASE + 24_305, src: "broker", event: "start.announced", trace: S, ms: 4 },
  { ts: BASE + 24_420, src: "broker", event: "start.telegram", trace: S, ms: 115, where: "create" },
  { ts: BASE + 24_421, src: "broker", event: "start.created", trace: S, ms: 4_380, session: "s1" },
  { ts: BASE + 24_425, src: "broker", event: "http", trace: S, req: "r7", method: "POST", path: "/sessions", route: "/sessions", status: 200, ms: 4_385 },
  { ts: BASE + 24_430, src: "client", event: "http", trace: S, t: 24_430, req: "r7", method: "POST", path: "/sessions", status: 200, ms: 4_420, serverMs: 4_385, device: "mac" },
  { ts: BASE + 24_431, src: "client", event: "start.response", trace: S, t: 24_431, session: "s1", device: "mac" },
  { ts: BASE + 24_433, src: "broker", event: "start.message.delivering", trace: S, chars: 12, session: "s1" },
  { ts: BASE + 24_440, src: "broker", event: "start.claude.prompt_written", trace: S, session: "s1" },
  { ts: BASE + 24_600, src: "client", event: "start.chat.shown", trace: S, t: 24_600, device: "mac" },
  { ts: BASE + 27_440, src: "broker", event: "start.claude.first_stream", trace: S, session: "s1" },
  { ts: BASE + 28_000, src: "broker", event: "start.reply.first", trace: S, chars: 40, session: "s1" },
  { ts: BASE + 28_080, src: "client", event: "start.reply.drawn", trace: S, t: 28_080, device: "mac" },
]

test("a session start: app and broker on one clock, the broker's HTTP time nested, phases ranked", () => {
  expect(listTraces(start).map((t) => [t.trace, t.kind])).toEqual([[S, "start"]])
  const out = renderStart(start, S)
  expect(out).toContain(`Session start ${S} — launcher, claude`)
  // The broker's half of POST /sessions sits under the app's request line.
  expect(out).toMatch(/app http\s+POST \/sessions · 200 · 4420 ms to headers \(broker 4385 ms\)\n\s+└ broker 4385 ms/)
  expect(out).toContain("git worktree add -b mux/x")
  expect(out).toContain("Telegram menu refresh (create)")
  // Ranked: Claude's boot first, then the model's first token, then git worktree add.
  const ranked = out.split("steps under 10 ms left out):\n")[1]!.split("\n\n")[0]!.split("\n").map((l) => l.trim().split(/\s{2,}/))
  expect(ranked.slice(0, 3)).toEqual([["prompt → model's first output", "3.0 s"], ["Claude boot → initialize", "2.1 s"], ["git worktree add", "1.2 s"]])
  expect(ranked).toContainEqual(["Telegram menu refresh ×2", "295 ms"])
  expect(ranked.map((r) => r[0])).not.toContain("setup hook")
  expect(out).toContain("Summary: created in 4.4 s · app had the answer at 4.4 s · chat on screen at 4.6 s · first reply at 8.0 s · drawn at 8.1 s")
})

test("a start the broker traced alone (an agent's spawn_session) reads on the broker's clock", () => {
  const brokerOnly: PerfEvent[] = start.filter((e) => e.src === "broker" && e.event !== "http").map((e) => ({ ...e, trace: "SBBBBBBB" }))
  brokerOnly[0] = { ...brokerOnly[0]!, via: "spawn_session" }
  const out = renderStart(brokerOnly, "SBBBBBBB")
  expect(out).toContain("Session start SBBBBBBB — spawn_session, claude")
  expect(out).toContain("Summary: created in 4.4 s · first reply at 8.0 s")
})

test("several starts: each step's median, fastest and slowest, largest median first", () => {
  const second: PerfEvent[] = start.map((e) => ({ ...e, trace: "SAAAAAA.2", ...(e.event === "start.worktree.add" ? { ms: 2_400 } : {}), ...(e.event === "start.created" ? { ms: 5_580 } : {}) }))
  const out = renderStartSummary([...start, ...second], [S, "SAAAAAA.2"])
  expect(out).toContain("2 session starts (launcher claude): median, fastest, slowest")
  const lines = out.split("\n")
  const add = lines.find((l) => l.trim().startsWith("git worktree add"))!
  expect(add.trim().split(/\s{2,}/)).toEqual(["git worktree add", "2.4 s", "1.2 s", "2.4 s", "(2)"])
  expect(lines.find((l) => l.trim().startsWith("created (request"))).toContain("(2)")
  expect(lines.find((l) => l.trim().startsWith("Telegram menu refresh ×2"))).toBeDefined()
})

// Card #75: a start now makes the session's row asleep at once and wakes it after the answer.
test("a start born asleep reads: the row, the answer, the app showing it starting, then the wake's worktree", () => {
  const T = "SCCCCCCC"
  const asleep: PerfEvent[] = [
    { ts: BASE + 1_000, src: "broker", event: "start.request", trace: T, via: "launcher", agent: "claude", worktree: true, firstMessage: true },
    { ts: BASE + 1_030, src: "broker", event: "start.row", trace: T, ms: 25, session: "s9", worktree: true },
    { ts: BASE + 1_040, src: "broker", event: "start.created", trace: T, ms: 40, session: "s9" },
    { ts: BASE + 1_100, src: "client", event: "start.agent.starting", trace: T, t: 1_100, detail: "starting" },
    { ts: BASE + 1_900, src: "broker", event: "start.worktree", trace: T, ms: 800, created: true, session: "s9" },
  ]
  const out = renderStart(asleep, T)
  expect(out).toContain("session row made asleep (branch made)")
  expect(out).toContain("the app shows it starting")
  expect(out).toContain("worktree checked out at its first wake")
})
