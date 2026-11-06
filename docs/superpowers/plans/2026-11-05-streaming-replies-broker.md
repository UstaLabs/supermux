# Streaming replies — Plan A: core, broker and protocol (card #76)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The broker relays an agent's reply and thinking text while it is written, as coalesced `reply_stream` frames, to apps that opt in and have that chat on screen. The stored message, or the stored reasoning row, carries the same `stream` id and replaces the live text.

**Architecture:** The core already emits `assistant-delta` and `reasoning-delta`.
- The broker's bridge mints a stream id per text block and emits `live-delta` / `live-end` adapter events.
- `main.ts` forwards them to a new `LiveReplies` object owned by the web channel. It keeps each open stream's text and a cursor per socket, and flushes every 50 ms.
- Stored messages and reasoning rows carry `stream`.
- Apps opt in with `subscribe.streams: 1`, so old apps are untouched.

Thinking text is also switched on for Claude (`--thinking-display summarized`) and Codex (`model_reasoning_summary="auto"`).

**Tech Stack:** TypeScript on Bun (`bun test`); supermux-core (`packages/supermux-core`).

Spec: `docs/superpowers/specs/2026-11-05-streaming-replies-design.md`. Plan B (apps) follows once this lands.

**Run tests from the worktree root** (`node_modules` is a symlink to `~/projects/supermux/node_modules`):
- broker tests: `bun test <path>`
- core tests: `cd packages/supermux-core && bun test tests/<file>`

**Box rule:** the box is heavily loaded. Run single test files while iterating. The whole broker suite goes through
`flock -w 3600 /tmp/supermux-heavy.lock systemd-run --user --scope -q -p MemoryMax=8G -- bun test`.

---

## File map

| File | Change |
|---|---|
| `packages/supermux-core/src/codex/normalize.ts` | a second reasoning summary part starts with a `"\n\n"` delta |
| `packages/supermux-core/tests/normalize-codex.test.ts` | test for it |
| `src/channels/web/live-replies.ts` (new) | `LiveReplies`: open streams, per-socket cursors, 50 ms flush, snapshot |
| `src/channels/web/live-replies.test.ts` (new) | unit tests, fake sockets, manual ticks |
| `src/core/agents/types.ts` | `LiveDeltaEvent`, `LiveEndEvent`; `AssistantMessageEvent.stream` |
| `src/core/agents/core-bridge/normalized-bridge.ts` | streams: mint, emit live events, stamp finals |
| `src/core/agents/core-bridge/normalized-bridge.test.ts` | tests |
| `src/core/agents/core-bridge/normalized-activity.ts` | summaries are thinking text; one redacted row per stretch |
| `src/core/agents/core-bridge/normalized-activity.test.ts` | tests |
| `src/core/agents/claude/activity-event.ts` | `ActivityEvent.stream` |
| `src/core/agents/core-bridge/core-adapter.ts` | stamp the reasoning row with the stream the bridge closed |
| `src/core/session-manager/messages.ts` | `Message.stream` (live frame only, not persisted) |
| `src/main.ts` | wire `live-delta` / `live-end` / turn end; `entry.stream` |
| `src/channels/web/index.ts` | `subscribe.streams`, per-socket viewing, `snapshot.streams`, `liveAppend/liveClose/liveEndSession` |
| `src/channels/web/live-replies-ws.test.ts` (new) | real-socket test through the channel |
| `src/core/agents/thinking-args.ts` (new) | `claudeThinkingArgs`, `codexReasoningArgs` (env switches) |
| `src/core/agents/thinking-args.test.ts` (new) | tests |
| `src/core/agents/claude/core-host.ts`, `src/core/agents/codex/core-host.ts` | add those args |

---

### Task 1: Codex summary parts stream as separate paragraphs (core)

**Files:**
- Modify: `packages/supermux-core/src/codex/normalize.ts` (the `item/reasoning/summaryPartAdded` branch, ~line 666)
- Test: `packages/supermux-core/tests/normalize-codex.test.ts`

- [ ] **Step 1: Write the failing test** (inside `describe("codex normalizer", …)`, after the "reasoning deltas" test)

```ts
  test("a second reasoning summary part starts a new paragraph", () => {
    const n = createCodexNormalizer()
    expect(n(native("item/reasoning/summaryPartAdded", { itemId: "r1", summaryIndex: 0 }))).toEqual([])
    expect(n(native("item/reasoning/summaryTextDelta", { itemId: "r1", delta: "**Plan**", summaryIndex: 0 })))
      .toEqual([{ kind: "reasoning-delta", reasoningId: "r1", text: "**Plan**" }])
    expect(n(native("item/reasoning/summaryPartAdded", { itemId: "r1", summaryIndex: 1 })))
      .toEqual([{ kind: "reasoning-delta", reasoningId: "r1", text: "\n\n" }])
    expect(n(native("item/reasoning/summaryTextDelta", { itemId: "r1", delta: "**Check**", summaryIndex: 1 })))
      .toEqual([{ kind: "reasoning-delta", reasoningId: "r1", text: "**Check**" }])
  })
```

- [ ] **Step 2: Run it, expect FAIL** (the third expectation gets `[]`)

Run: `cd packages/supermux-core && bun test tests/normalize-codex.test.ts -t "second reasoning summary part"`

- [ ] **Step 3: Implement**

Replace the `item/reasoning/summaryPartAdded` branch with:

```ts
    if (method === "item/reasoning/summaryPartAdded") {
      const id = str(params.itemId) ?? "reasoning"
      const prev = reasoning.get(id) ?? { text: "", summary: [] }
      // Summary parts are separate paragraphs ("**Drafting …**", "**Checking …**"): streamed, they would run
      // together, so a part after the first starts with a paragraph break.
      const after = prev.summary.some(part => part.length > 0)
      const part = typeof params.summary === "string" ? params.summary : typeof params.text === "string" ? params.text : ""
      if (part) prev.summary = [...prev.summary, part]
      reasoning.set(id, prev)
      return after ? [{ kind: "reasoning-delta", reasoningId: id, text: "\n\n" }] : []
    }
```

- [ ] **Step 4: Run the codex normalizer tests, expect PASS**

Run: `cd packages/supermux-core && bun test tests/normalize-codex.test.ts tests/subagents-codex.test.ts`

- [ ] **Step 5: Commit**

```bash
git add packages/supermux-core/src/codex/normalize.ts packages/supermux-core/tests/normalize-codex.test.ts
git commit -m "fix(core): Codex reasoning summary parts stream as separate paragraphs (#76)"
```

---

### Task 2: `LiveReplies` (broker, web channel)

**Files:**
- Create: `src/channels/web/live-replies.ts`
- Test: `src/channels/web/live-replies.test.ts`

- [ ] **Step 1: Write the failing tests**

```ts
// Card #76: LiveReplies keeps the text of replies being written and sends each socket what it has
// not seen yet, for the chats it has on screen.
import { expect, test } from "bun:test"
import { LiveReplies, type LiveSocket } from "./live-replies"

function socket(buffered = 0): LiveSocket & { frames: any[]; buffered: number } {
  const s = {
    frames: [] as any[],
    buffered,
    send(json: string) { s.frames.push(JSON.parse(json)) },
    bufferedAmount() { return s.buffered },
  }
  return s
}

// No timer in tests: ticks are called by hand.
const manual = () => new LiveReplies({ every: () => () => {} })

test("the first delta goes out at once, the rest on the tick, only to sockets watching the chat", () => {
  const live = manual()
  const a = socket(), b = socket(), c = socket()
  live.join(a); live.join(b); live.join(c)
  live.watch(a, ["s1"]); live.watch(b, ["s2"])
  live.append("s1", "k1", "reply", "Hel")
  expect(a.frames).toEqual([{ type: "reply_stream", session: "s1", stream: "k1", at: 0, text: "Hel" }])
  live.append("s1", "k1", "reply", "lo")
  live.append("s1", "k1", "reply", " there")
  expect(a.frames.length).toBe(1)
  live.tick()
  expect(a.frames[1]).toEqual({ type: "reply_stream", session: "s1", stream: "k1", at: 3, text: "lo there" })
  expect(b.frames).toEqual([])
  expect(c.frames).toEqual([])
})

test("thinking frames say so", () => {
  const live = manual()
  const a = socket()
  live.join(a); live.watch(a, ["s1"])
  live.append("s1", "t1", "thinking", "Planning")
  expect(a.frames[0]).toEqual({ type: "reply_stream", session: "s1", stream: "t1", kind: "thinking", at: 0, text: "Planning" })
})

test("a chat that comes on screen mid-reply gets the whole text so far in one frame", () => {
  const live = manual()
  const a = socket()
  live.join(a)
  live.append("s1", "k1", "reply", "one ")
  live.append("s1", "k1", "reply", "two")
  expect(a.frames).toEqual([])
  live.watch(a, ["s1"])
  expect(a.frames).toEqual([{ type: "reply_stream", session: "s1", stream: "k1", at: 0, text: "one two" }])
  live.watch(a, [])
  live.append("s1", "k1", "reply", " three")
  live.tick()
  expect(a.frames.length).toBe(1)
  live.watch(a, ["s1"])
  expect(a.frames[1]).toEqual({ type: "reply_stream", session: "s1", stream: "k1", at: 0, text: "one two three" })
})

test("a congested socket skips ticks and catches up in one frame", () => {
  const live = manual()
  const a = socket()
  live.join(a); live.watch(a, ["s1"])
  live.append("s1", "k1", "reply", "a")
  a.buffered = 1024 * 1024
  live.append("s1", "k1", "reply", "b")
  live.tick()
  live.append("s1", "k1", "reply", "c")
  live.tick()
  expect(a.frames.length).toBe(1)
  a.buffered = 0
  live.tick()
  expect(a.frames[1]).toEqual({ type: "reply_stream", session: "s1", stream: "k1", at: 1, text: "bc" })
})

test("close flushes what is left; a final needs no end frame, a dropped one tells every app", () => {
  const live = manual()
  const a = socket(), b = socket()
  live.join(a); live.join(b); live.watch(a, ["s1"])
  live.append("s1", "k1", "reply", "x")
  live.append("s1", "k1", "reply", "y")
  live.close("k1", true)
  expect(a.frames).toEqual([
    { type: "reply_stream", session: "s1", stream: "k1", at: 0, text: "x" },
    { type: "reply_stream", session: "s1", stream: "k1", at: 1, text: "y" },
  ])
  expect(b.frames).toEqual([])
  live.append("s1", "k2", "reply", "z")
  live.close("k2", false)
  expect(a.frames.at(-1)).toEqual({ type: "reply_stream_end", session: "s1", stream: "k2" })
  expect(b.frames).toEqual([{ type: "reply_stream_end", session: "s1", stream: "k2" }])
  // A late delta for a closed stream does not reopen it.
  live.append("s1", "k1", "reply", "late")
  live.tick()
  expect(live.snapshot(a, ["s1"])).toEqual({})
})

test("endSession ends every stream still open in that session", () => {
  const live = manual()
  const a = socket()
  live.join(a); live.watch(a, ["s1"])
  live.append("s1", "t1", "thinking", "hm")
  live.append("s1", "k1", "reply", "ok")
  live.append("s2", "k9", "reply", "other")
  live.endSession("s1")
  expect(a.frames.filter(f => f.type === "reply_stream_end").map(f => f.stream).sort()).toEqual(["k1", "t1"])
  expect(Object.keys(live.snapshot(a, ["s1", "s2"]))).toEqual(["s2"])
})

test("snapshot hands over open streams and the frames continue from there", () => {
  const live = manual()
  const a = socket()
  live.append("s1", "k1", "reply", "abc")
  live.join(a)
  expect(live.snapshot(a, ["s1", "s9"])).toEqual({ s1: [{ stream: "k1", kind: "reply", text: "abc" }] })
  live.watch(a, ["s1"])
  expect(a.frames).toEqual([])
  live.append("s1", "k1", "reply", "d")
  live.tick()
  expect(a.frames).toEqual([{ type: "reply_stream", session: "s1", stream: "k1", at: 3, text: "d" }])
})

test("a batch never ends between the two halves of a surrogate pair", () => {
  const live = manual()
  const a = socket()
  live.join(a); live.watch(a, ["s1"])
  live.append("s1", "k1", "reply", "ok \ud83d")
  expect(a.frames[0].text).toBe("ok ")
  live.append("s1", "k1", "reply", "\ude00!")
  live.tick()
  expect(a.frames[1]).toEqual({ type: "reply_stream", session: "s1", stream: "k1", at: 3, text: "😀!" })
})

test("the timer runs only while something streams", () => {
  let running = 0
  const live = new LiveReplies({ every: () => { running++; return () => { running-- } } })
  expect(running).toBe(0)
  live.append("s1", "k1", "reply", "a")
  expect(running).toBe(1)
  live.append("s1", "k2", "reply", "b")
  expect(running).toBe(1)
  live.close("k1", true)
  live.close("k2", true)
  live.tick()
  expect(running).toBe(0)
})

test("a socket that left gets nothing, and its send errors never escape", () => {
  const live = manual()
  const a = socket()
  const broken: LiveSocket = { send() { throw new Error("closed") }, bufferedAmount: () => 0 }
  live.join(a); live.join(broken)
  live.watch(a, ["s1"]); live.watch(broken, ["s1"])
  live.leave(a)
  expect(() => live.append("s1", "k1", "reply", "a")).not.toThrow()
  expect(a.frames).toEqual([])
})
```

- [ ] **Step 2: Run, expect FAIL** (module not found)

Run: `bun test src/channels/web/live-replies.test.ts`

- [ ] **Step 3: Implement `src/channels/web/live-replies.ts`**

```ts
// Card #76: the text of replies (and thinking) while an agent writes them. Only what the apps on screen
// need: each opted-in socket gets, for the chats it has on screen, the text it has not seen yet, at most
// once per tick. Nothing here is stored; the stored message (or reasoning row) carries the same `stream`
// id and takes the live text's place in the app.

export type LiveKind = "reply" | "thinking"

/** What LiveReplies needs from a socket: send a frame, and how many bytes are still queued in it. */
export interface LiveSocket {
  send(json: string): void
  bufferedAmount(): number
}

export type LiveSnapshot = Record<string, { stream: string; kind: LiveKind; text: string }[]>

type Stream = { session: string; kind: LiveKind; text: string }
/** One opted-in socket: the chats it shows, and how far into each stream it has been sent. */
type Viewer = { watching: Set<string>; sent: Map<string, number> }

const TICK_MS = 50
/** A socket this far behind skips ticks: its next frame carries what it missed. */
const BACKPRESSURE_BYTES = 256 * 1024
/** Closed stream ids remembered so a late delta cannot reopen one. */
const CLOSED_MEMORY = 256

/** The end of the text that can be sent now: never between the two halves of a surrogate pair. */
function sendable(text: string): number {
  const last = text.charCodeAt(text.length - 1)
  return last >= 0xd800 && last <= 0xdbff ? text.length - 1 : text.length
}

export class LiveReplies {
  private readonly streams = new Map<string, Stream>()
  private readonly viewers = new Map<LiveSocket, Viewer>()
  private readonly closed = new Set<string>()
  private readonly tickMs: number
  private readonly every: (fn: () => void, ms: number) => () => void
  private stopTimer: (() => void) | undefined

  constructor(opts: { tickMs?: number; every?: (fn: () => void, ms: number) => () => void } = {}) {
    this.tickMs = opts.tickMs ?? TICK_MS
    this.every = opts.every ?? ((fn, ms) => {
      const t = setInterval(fn, ms)
      return () => clearInterval(t)
    })
  }

  /** A socket that asked for live text (`subscribe.streams`). It shows no chat until [watch]. */
  join(socket: LiveSocket): void {
    if (!this.viewers.has(socket)) this.viewers.set(socket, { watching: new Set(), sent: new Map() })
  }

  leave(socket: LiveSocket): void {
    this.viewers.delete(socket)
  }

  /** The chats this socket has on screen. */
  watching(socket: LiveSocket): ReadonlySet<string> {
    return this.viewers.get(socket)?.watching ?? new Set()
  }

  /**
   * The chats on this socket's screen, replacing the last set. A chat that leaves the screen forgets how
   * far it was sent; one that comes on screen gets each open stream's text so far at once (`at: 0`),
   * unless the snapshot already handed it over.
   */
  watch(socket: LiveSocket, sessions: Iterable<string>): void {
    const viewer = this.viewers.get(socket)
    if (!viewer) return
    const next = new Set(sessions)
    for (const [id, stream] of this.streams) {
      if (viewer.watching.has(stream.session) && !next.has(stream.session)) viewer.sent.delete(id)
    }
    viewer.watching = next
    for (const [id, stream] of this.streams) {
      if (next.has(stream.session)) this.sendTo(socket, viewer, id, stream, false)
    }
  }

  /** More text for a stream; the first piece of a new stream goes out at once, the rest on the tick. */
  append(session: string, id: string, kind: LiveKind, text: string): void {
    if (!text || this.closed.has(id)) return
    const open = this.streams.get(id)
    if (open) {
      open.text += text
      return
    }
    const stream: Stream = { session, kind, text }
    this.streams.set(id, stream)
    for (const [socket, viewer] of this.viewers) {
      if (viewer.watching.has(session)) this.sendTo(socket, viewer, id, stream, false)
    }
    if (!this.stopTimer) this.stopTimer = this.every(() => this.tick(), this.tickMs)
  }

  /**
   * The stream ended. Whatever is left goes out first. `final`: its stored message or reasoning row follows
   * with the same id and replaces it in the app; otherwise every opted-in app is told to drop it.
   */
  close(id: string, final: boolean): void {
    const stream = this.streams.get(id)
    if (!stream) return
    for (const [socket, viewer] of this.viewers) {
      if (viewer.watching.has(stream.session)) this.sendTo(socket, viewer, id, stream, true)
      viewer.sent.delete(id)
    }
    this.streams.delete(id)
    this.closed.add(id)
    if (this.closed.size > CLOSED_MEMORY) this.closed.delete(this.closed.values().next().value as string)
    if (!final) {
      const json = JSON.stringify({ type: "reply_stream_end", session: stream.session, stream: id })
      for (const socket of this.viewers.keys()) this.safeSend(socket, json)
    }
  }

  /** The session's turn ended: anything still open there ends without a final. */
  endSession(session: string): void {
    for (const [id, stream] of [...this.streams]) if (stream.session === session) this.close(id, false)
  }

  /** Open streams of these chats, for the snapshot; the socket's frames then continue from there. */
  snapshot(socket: LiveSocket, sessions: Iterable<string>): LiveSnapshot {
    const wanted = new Set(sessions)
    const viewer = this.viewers.get(socket)
    const out: LiveSnapshot = {}
    for (const [id, stream] of this.streams) {
      if (!wanted.has(stream.session)) continue
      ;(out[stream.session] ??= []).push({ stream: id, kind: stream.kind, text: stream.text })
      viewer?.sent.set(id, stream.text.length)
    }
    return out
  }

  /** One flush: each watching socket gets what it has not seen of each open stream. */
  tick(): void {
    for (const [id, stream] of this.streams) {
      for (const [socket, viewer] of this.viewers) {
        if (viewer.watching.has(stream.session)) this.sendTo(socket, viewer, id, stream, false)
      }
    }
    if (this.streams.size === 0 && this.stopTimer) {
      this.stopTimer()
      this.stopTimer = undefined
    }
  }

  stop(): void {
    this.stopTimer?.()
    this.stopTimer = undefined
  }

  private sendTo(socket: LiveSocket, viewer: Viewer, id: string, stream: Stream, all: boolean): void {
    const at = viewer.sent.get(id) ?? 0
    const end = all ? stream.text.length : sendable(stream.text)
    if (end <= at) return
    if (!all && at > 0 && socket.bufferedAmount() > BACKPRESSURE_BYTES) return
    const frame = {
      type: "reply_stream",
      session: stream.session,
      stream: id,
      ...(stream.kind === "thinking" ? { kind: "thinking" } : {}),
      at,
      text: stream.text.slice(at, end),
    }
    if (this.safeSend(socket, JSON.stringify(frame))) viewer.sent.set(id, end)
  }

  private safeSend(socket: LiveSocket, json: string): boolean {
    try {
      socket.send(json)
      return true
    } catch {
      return false
    }
  }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `bun test src/channels/web/live-replies.test.ts`

- [ ] **Step 5: Commit**

```bash
git add src/channels/web/live-replies.ts src/channels/web/live-replies.test.ts
git commit -m "feat(broker): LiveReplies keeps replies being written and sends each app what it hasn't seen (#76)"
```

---

### Task 3: The bridge streams replies and thinking

**Files:**
- Modify: `src/core/agents/types.ts`
- Modify: `src/core/agents/core-bridge/normalized-bridge.ts`
- Test: `src/core/agents/core-bridge/normalized-bridge.test.ts`

- [ ] **Step 1: Write the failing tests** (append to `normalized-bridge.test.ts`; `env()` already exists there)

```ts
// Card #76: live text.
function live(agent = "claude") {
  const events: AgentEvent[] = []
  const b = createNormalizedBridge({ agent, emit: (e) => events.push(e) })
  return { b, events }
}

test("a reply streams under one id and its stored message carries it", () => {
  const { b, events } = live()
  b.handle(env({ kind: "assistant-delta", messageId: "m:1", text: "Hel" }))
  b.handle(env({ kind: "assistant-delta", messageId: "m:1", text: "lo" }))
  b.handle(env({ kind: "assistant-message", messageId: "m:1", text: "Hello" }))
  const stream = (events[0] as any).stream
  expect(typeof stream).toBe("string")
  expect(events).toEqual([
    { kind: "live-delta", stream, streamKind: "reply", text: "Hel" },
    { kind: "live-delta", stream, streamKind: "reply", text: "lo" },
    { kind: "live-end", stream, final: true },
    { kind: "assistant-message", text: "Hello", stream },
  ])
})

test("each text block gets its own stream", () => {
  const { b, events } = live()
  b.handle(env({ kind: "assistant-delta", messageId: "m:1", text: "a" }))
  b.handle(env({ kind: "assistant-message", messageId: "m:1", text: "a" }))
  b.handle(env({ kind: "assistant-delta", messageId: "m:2", text: "b" }))
  const ids = events.filter(e => e.kind === "live-delta").map(e => (e as any).stream)
  expect(ids.length).toBe(2)
  expect(ids[0]).not.toBe(ids[1])
})

test("thinking streams; an empty thinking delta opens nothing; the reasoning final closes it", () => {
  const { b, events } = live()
  b.handle(env({ kind: "reasoning-delta", reasoningId: "m:0", text: "" }))
  expect(events).toEqual([])
  b.handle(env({ kind: "reasoning-delta", reasoningId: "m:0", text: "Plan" }))
  const stream = (events[0] as any).stream
  expect(events[0]).toEqual({ kind: "live-delta", stream, streamKind: "thinking", text: "Plan" })
  expect(b.handle(env({ kind: "reasoning", reasoningId: "m:0", text: "Plan", redacted: false }))).toBe(stream)
  expect(events[1]).toEqual({ kind: "live-end", stream, final: true })
  expect(b.handle(env({ kind: "reasoning", reasoningId: "other", redacted: true }))).toBeUndefined()
})

test("subagent and replayed text never streams", () => {
  const { b, events } = live()
  b.handle(env({ kind: "assistant-delta", messageId: "m:1", text: "child", subagentId: "sub1" }))
  b.handle(env({ kind: "reasoning-delta", reasoningId: "r", text: "child", subagentId: "sub1" }))
  b.handle(env({ kind: "assistant-delta", messageId: "m:2", text: "old", replay: true }))
  expect(events).toEqual([])
})

test("a final the bridge drops (same text again) ends its stream without a final", () => {
  const { b, events } = live()
  b.handle(env({ kind: "assistant-message", messageId: "m:1", text: "same" }))
  b.handle(env({ kind: "assistant-delta", messageId: "m:2", text: "same" }))
  b.handle(env({ kind: "assistant-message", messageId: "m:2", text: "same" }))
  const stream = (events[1] as any).stream
  expect(events.slice(1)).toEqual([
    { kind: "live-delta", stream, streamKind: "reply", text: "same" },
    { kind: "live-end", stream, final: false },
  ])
})

test("flush stores the pending text under its stream and ends the rest", () => {
  const { b, events } = live("grok")
  b.handle(env({ kind: "reasoning-delta", reasoningId: "reasoning:1", text: "hm" }))
  b.handle(env({ kind: "assistant-delta", messageId: "assistant:1", text: "partial" }))
  b.flush()
  const thinking = (events[0] as any).stream
  const reply = (events[1] as any).stream
  expect(events.slice(2)).toEqual([
    { kind: "live-end", stream: reply, final: true },
    { kind: "assistant-message", text: "partial", stream: reply },
    { kind: "live-end", stream: thinking, final: false },
  ])
})
```

- [ ] **Step 2: Run, expect FAIL**

Run: `bun test src/core/agents/core-bridge/normalized-bridge.test.ts`

- [ ] **Step 3: Add the event types** in `src/core/agents/types.ts`

In `AssistantMessageEvent`, after `keyboard?: string[]`:

```ts
  /** Card #76: the live stream this stored message replaces in the app (the bridge's id for its text block). */
  stream?: string
```

After `ActivityCardsEvent`:

```ts
/** Card #76: a piece of the reply (or thinking) being written, before it is stored. */
export type LiveDeltaEvent = { kind: "live-delta"; stream: string; streamKind: "reply" | "thinking"; text: string }
/**
 * Card #76: a live stream ended. `final`: its stored message or reasoning row follows at once with the same
 * `stream` id and replaces it in the app; otherwise nothing replaces it and the apps drop it.
 */
export type LiveEndEvent = { kind: "live-end"; stream: string; final: boolean }
```

Add both to the `AgentEvent` union (after `| ActivityCardsEvent`):

```ts
  | LiveDeltaEvent
  | LiveEndEvent
```

- [ ] **Step 4: Implement in `normalized-bridge.ts`**

Add the import at the top:

```ts
import { randomUUID } from "node:crypto"
```

Inside `createNormalizedBridge`, after `let lastAssistant = ""`:

```ts
  // Card #76: one live stream per text block (the core's message / reasoning id), from its first piece of
  // text until its stored form. The id is ours: the core's are not unique across turns (ACP's
  // "assistant:1" comes back every turn).
  const streams = new Map<string, string>()
  const streamKey = (kind: "reply" | "thinking", coreId: string) => `${kind}\u0000${coreId}`
  function openStream(kind: "reply" | "thinking", coreId: string): string {
    const key = streamKey(kind, coreId)
    let id = streams.get(key)
    if (!id) {
      id = randomUUID().replace(/-/g, "").slice(0, 12)
      streams.set(key, id)
    }
    return id
  }
  function takeStream(kind: "reply" | "thinking", coreId: string): string | undefined {
    const key = streamKey(kind, coreId)
    const id = streams.get(key)
    streams.delete(key)
    return id
  }
  function takeOpen(kind: "reply" | "thinking"): string[] {
    const out: string[] = []
    for (const [key, id] of [...streams]) {
      if (!key.startsWith(`${kind}\u0000`)) continue
      streams.delete(key)
      out.push(id)
    }
    return out
  }
```

Replace `emitAssistant` with:

```ts
  const emitAssistant = (text: string, stream?: string) => {
    const trimmed = text.trim()
    pendingAssistant = ""
    if (!trimmed || trimmed === lastAssistant) {
      // Nothing is stored for it, so nothing will replace the live text: the apps drop it.
      if (stream) opts.emit({ kind: "live-end", stream, final: false })
      return
    }
    lastAssistant = trimmed
    // The live text's last piece goes out before the stored message, which then replaces it.
    if (stream) opts.emit({ kind: "live-end", stream, final: true })
    opts.emit({ kind: "assistant-message", text: trimmed, ...(stream ? { stream } : {}) })
  }
```

Change `function handle(event: CoreNormalizedEvent): void {` to return the thinking stream a reasoning final closed:

```ts
  /** Returns the live thinking stream a `reasoning` final closed, so its activity row can carry the id. */
  function handle(event: CoreNormalizedEvent): string | undefined {
```

(the existing early `return` statements stay as they are: they return `undefined`.)

Replace the two assistant branches:

```ts
    if (kind === "assistant-delta") {
      pendingAssistant += event.text
      if (event.text) opts.emit({ kind: "live-delta", stream: openStream("reply", event.messageId), streamKind: "reply", text: event.text })
      return
    }
    if (kind === "assistant-message") {
      emitAssistant(event.text, takeStream("reply", event.messageId))
      return
    }
    if (kind === "reasoning-delta") {
      // Redacted thinking (Claude without --thinking-display) sends empty pieces: those open nothing.
      if (event.text) opts.emit({ kind: "live-delta", stream: openStream("thinking", event.reasoningId), streamKind: "thinking", text: event.text })
      return
    }
    if (kind === "reasoning") {
      const stream = takeStream("thinking", event.reasoningId)
      if (stream) opts.emit({ kind: "live-end", stream, final: true })
      return stream
    }
```

In the antigravity warning branch, replace `if (pendingAssistant) emitAssistant(pendingAssistant)` with:

```ts
      if (pendingAssistant) emitAssistant(pendingAssistant, takeOpen("reply")[0])
```

Replace `flush`:

```ts
  function flush(): void {
    const open = takeOpen("reply")
    if (pendingAssistant) emitAssistant(pendingAssistant, open.shift())
    // Whatever still streams has no stored form coming: the apps drop it.
    for (const stream of [...open, ...takeOpen("thinking")]) opts.emit({ kind: "live-end", stream, final: false })
  }
```

- [ ] **Step 5: Run the bridge tests, expect PASS**

Run: `bun test src/core/agents/core-bridge/normalized-bridge.test.ts`

- [ ] **Step 6: Commit**

```bash
git add src/core/agents/types.ts src/core/agents/core-bridge/normalized-bridge.ts src/core/agents/core-bridge/normalized-bridge.test.ts
git commit -m "feat(broker): the bridge streams replies and thinking under their own ids (#76)"
```

---

### Task 4: Thinking rows: summaries are thinking text, one redacted row per stretch

**Files:**
- Modify: `src/core/agents/core-bridge/normalized-activity.ts` (the `reasoning` branch of `rowsFor`, ~line 427)
- Test: `src/core/agents/core-bridge/normalized-activity.test.ts`

- [ ] **Step 1: Write the failing tests** (append; use the file's existing helper for building a `NormalizedEvent` if it has one, else this one)

```ts
// Card #76.
function reasoningEvent(body: Record<string, unknown>) {
  return { event: { kind: "reasoning", ...body } } as any
}

test("Claude's thinking progress makes one redacted row per stretch of thinking, not one per update", () => {
  const a = createNormalizedActivity({ workdir: "/w" })
  expect(a.handle(reasoningEvent({ reasoningId: "u1", redacted: true }), 1).length).toBe(1)
  expect(a.handle(reasoningEvent({ reasoningId: "u2", redacted: true }), 2)).toEqual([])
  expect(a.handle(reasoningEvent({ reasoningId: "u3", redacted: true }), 3)).toEqual([])
  a.handle({ event: { kind: "assistant-message", messageId: "m", text: "hi" } } as any, 4)
  expect(a.handle(reasoningEvent({ reasoningId: "u4", redacted: true }), 5).length).toBe(1)
})

test("a summary is the thinking text (Codex withholds the raw thinking)", () => {
  const a = createNormalizedActivity({ workdir: "/w" })
  const rows = a.handle(reasoningEvent({ reasoningId: "r1", redacted: true, summary: ["**Plan**", "**Check**"] }), 1)
  expect(rows).toEqual([{ ts: new Date(1).toISOString(), kind: "reasoning", title: "Thinking", detail: "**Plan**\n\n**Check**" }])
})
```

- [ ] **Step 2: Run, expect FAIL**

Run: `bun test src/core/agents/core-bridge/normalized-activity.test.ts`

- [ ] **Step 3: Implement**

At the top of `createNormalizedActivity`'s body (next to its other state):

```ts
  // Card #76: Claude reports thinking progress (system/thinking_tokens) once per thinking update, each an
  // empty reasoning body. One redacted row stands for a stretch of thinking: the next one waits until
  // something else happened in that thread (a reply, a tool, a plan). Keyed by subagent ("" = the parent).
  const redactedOpen = new Set<string>()
```

At the start of `rowsFor`, after `if (!body) return []`:

```ts
    const thread = body.subagentId ?? ""
    if (body.kind !== "reasoning" && body.kind !== "reasoning-delta") redactedOpen.delete(thread)
```

Replace the `reasoning` branch with:

```ts
    if (body.kind === "reasoning" || body.kind === "reasoning-delta") {
      if (body.kind === "reasoning-delta") return []
      // Codex withholds its raw thinking (the core marks it redacted) but may write summaries: those ARE the
      // thinking the user sees, joined as the stream showed them.
      const text = body.text ?? (body.summary?.length ? body.summary.join("\n\n") : "")
      if (!text) {
        if (redactedOpen.has(thread)) return []
        redactedOpen.add(thread)
        return [{ ts: new Date(now).toISOString(), kind: "reasoning", title: "Thinking (redacted)" }]
      }
      return [{
        ts: new Date(now).toISOString(),
        kind: "reasoning",
        title: "Thinking",
        detail: clip(text, DETAIL_MAX).text,
      }]
    }
```

- [ ] **Step 4: Run, expect PASS** (the whole file, so the existing reasoning tests still hold)

Run: `bun test src/core/agents/core-bridge/normalized-activity.test.ts`

If an existing test expected one redacted row per `reasoning` body in a row, it encoded the old behaviour: update it to expect one row per stretch and say so in the commit.

- [ ] **Step 5: Commit**

```bash
git add src/core/agents/core-bridge/normalized-activity.ts src/core/agents/core-bridge/normalized-activity.test.ts
git commit -m "fix(broker): one redacted thinking row per stretch; Codex summaries show as thinking (#76)"
```

---

### Task 5: Stored rows carry the stream; `main.ts` relays live events

**Files:**
- Modify: `src/core/agents/claude/activity-event.ts`
- Modify: `src/core/agents/core-bridge/core-adapter.ts` (the `session.event` branch, ~line 1193)
- Modify: `src/core/session-manager/messages.ts` (`Message`)
- Modify: `src/main.ts` (`onAssistantMessage` ~line 1347, `wireAdapterEvents` ~line 1767)
- Test: `src/core/agents/core-bridge/core-adapter.test.ts`

- [ ] **Step 1: Write the failing adapter test.** Find a test in `core-adapter.test.ts` that feeds `session.event`s into an adapter and collects `activity` events. Copy its setup, then feed:

```ts
  // Card #76: the reasoning row says which live thinking it replaces.
  feed({ kind: "reasoning-delta", reasoningId: "m:0", text: "Plan" })
  feed({ kind: "reasoning", reasoningId: "m:0", text: "Plan", redacted: false })
  const live = emitted.filter(e => e.kind === "live-delta")
  const row = emitted.filter(e => e.kind === "activity").flatMap(e => e.events).find(r => r.kind === "reasoning")
  expect(row.stream).toBe(live[0].stream)
```

Here `feed`/`emitted` stand for that test's own helpers: use its names. Listen for `live-delta` the same way it listens for `activity`.

- [ ] **Step 2: Run, expect FAIL** (`row.stream` undefined)

Run: `bun test src/core/agents/core-bridge/core-adapter.test.ts`

- [ ] **Step 3: Implement**

`activity-event.ts`, inside `ActivityEvent` after `subagentId?: string`:

```ts
  /** Card #76: on a reasoning row, the live thinking stream it replaces in the app. */
  stream?: string
```

`core-adapter.ts`, replace

```ts
      this.bridge.handle(event.event)
      const env = event.event
      const cards = this.activity.handle({ ...env, event: env }, Date.now())
```

with

```ts
      const thinkingStream = this.bridge.handle(event.event)
      const env = event.event
      const cards = this.activity.handle({ ...env, event: env }, Date.now())
      // Card #76: the stored thinking takes the live thinking's place in the app.
      if (thinkingStream) for (const card of cards) if (card.kind === "reasoning") card.stream = thinkingStream
```

`messages.ts`, inside `Message` after `error?: boolean`:

```ts
  /** Card #76: the live stream this reply replaces in the app. Sent with the live frame only; never stored. */
  stream?: string
```

`main.ts`, in `onAssistantMessage`:
- add `stream?: string` to the `ev` parameter type;
- in the `entry` literal, after `error: ev.error,`, add:

```ts
        ...(ev.stream ? { stream: ev.stream } : {}),
```

`main.ts`, in `wireAdapterEvents`, after the `adapter.on("assistant-message", …)` block:

```ts
  // Card #76: the reply (and thinking) while it is written, to the apps that show this chat.
  adapter.on("live-delta", (ev: LiveDeltaEvent) => webChannel?.liveAppend(sessionId, ev.stream, ev.streamKind, ev.text))
  adapter.on("live-end", (ev: LiveEndEvent) => webChannel?.liveClose(ev.stream, ev.final))
```

and in the existing `adapter.on("turn-complete", …)` handler, as its first line:

```ts
    webChannel?.liveEndSession(sessionId)
```

Add `LiveDeltaEvent, LiveEndEvent` to the existing type import from `./core/agents/types` in `main.ts`. `webChannel.liveAppend` etc. arrive in Task 6; until then `bun build` complains. That is expected, and Task 6 follows directly.

- [ ] **Step 4: Run, expect PASS**

Run: `bun test src/core/agents/core-bridge/core-adapter.test.ts src/core/agents/core-bridge/normalized-bridge.test.ts`

- [ ] **Step 5: Commit** (together with Task 6, because `main.ts` needs the channel methods to type-check)

---

### Task 6: The web channel: opt-in, per-socket viewing, snapshot, frames

**Files:**
- Modify: `src/channels/web/index.ts`
- Test: `src/channels/web/live-replies-ws.test.ts` (new)

- [ ] **Step 1: Write the failing real-socket test** `src/channels/web/live-replies-ws.test.ts`

```ts
// Card #76: live text through the real channel. Only an app that asked (`subscribe.streams`) and shows the
// chat gets reply_stream frames; an old app gets none; a reconnect gets open streams in its snapshot.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function boot(): Promise<string> {
  const dir = mkdtempSync(join(tmpdir(), "mux-live-"))
  const devicesFile = join(dir, "devices.json")
  const opts: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [{ id: "a", name: "alpha", workdir: "/w", mute: false, connected: true }] as any,
    getSessionLog: () => [],
    getSessionActivity: () => [],
    getSessionCommands: () => [],
    getSessionCommandsResolved: () => true,
    setMute: () => {},
    onSendFromWeb: () => {},
  }
  channel = new WebChannel(opts)
  await channel.start()
  return new DeviceStore(devicesFile).mint("test-device").token
}

type Client = { ws: WebSocket; frames: any[]; snapshot: Promise<any> }

async function connect(token: string, subscribe: object): Promise<Client> {
  const ws = new WebSocket(`ws://127.0.0.1:${channel!.boundPort}/ws`, { headers: { Authorization: `Bearer ${token}` } } as any)
  await new Promise<void>((resolve, reject) => { ws.onopen = () => resolve(); ws.onerror = reject })
  const frames: any[] = []
  let gotSnapshot!: (f: any) => void
  const snapshot = new Promise<any>((r) => { gotSnapshot = r })
  ws.onmessage = (e) => {
    const f = JSON.parse(String(e.data))
    if (f.type === "snapshot") gotSnapshot(f)
    else frames.push(f)
  }
  ws.send(JSON.stringify({ type: "subscribe", ...subscribe }))
  await snapshot
  return { ws, frames, snapshot }
}

async function until(check: () => boolean, ms = 2000): Promise<void> {
  const end = Date.now() + ms
  while (!check()) {
    if (Date.now() > end) throw new Error("timed out")
    await Bun.sleep(10)
  }
}

const live = (c: Client) => c.frames.filter(f => f.type === "reply_stream" || f.type === "reply_stream_end")

test("an app that asked and shows the chat gets the reply as it is written; an old app gets nothing", async () => {
  const token = await boot()
  const app = await connect(token, { streams: 1 })
  const old = await connect(token, {})
  app.ws.send(JSON.stringify({ type: "viewing", sessions: ["a"], visible: true }))
  old.ws.send(JSON.stringify({ type: "viewing", sessions: ["a"], visible: true }))
  await Bun.sleep(30)
  channel!.liveAppend("a", "k1", "reply", "Hel")
  channel!.liveAppend("a", "k1", "reply", "lo")
  await until(() => live(app).length >= 2)
  expect(live(app)).toEqual([
    { type: "reply_stream", session: "a", stream: "k1", at: 0, text: "Hel" },
    { type: "reply_stream", session: "a", stream: "k1", at: 3, text: "lo" },
  ])
  channel!.liveClose("k1", false)
  await until(() => live(app).length >= 3)
  expect(live(app)[2]).toEqual({ type: "reply_stream_end", session: "a", stream: "k1" })
  await Bun.sleep(80)
  expect(live(old)).toEqual([])
  app.ws.close(); old.ws.close()
})

test("a chat off screen gets nothing until it is shown, then the text so far", async () => {
  const token = await boot()
  const app = await connect(token, { streams: 1 })
  app.ws.send(JSON.stringify({ type: "viewing", session: null, visible: true }))
  await Bun.sleep(30)
  channel!.liveAppend("a", "k1", "reply", "one two")
  await Bun.sleep(80)
  expect(live(app)).toEqual([])
  app.ws.send(JSON.stringify({ type: "viewing", session: "a", visible: true }))
  await until(() => live(app).length >= 1)
  expect(live(app)[0]).toEqual({ type: "reply_stream", session: "a", stream: "k1", at: 0, text: "one two" })
  app.ws.close()
})

test("a reconnect gets the open streams of its on-screen chats in the snapshot", async () => {
  const token = await boot()
  channel!.liveAppend("a", "k1", "reply", "so far")
  channel!.liveAppend("a", "t1", "thinking", "hm")
  const app = await connect(token, { streams: 1, logTail: 1, fullLogs: ["a"] })
  const snap = await app.snapshot
  expect(snap.streams).toEqual({ a: [{ stream: "k1", kind: "reply", text: "so far" }, { stream: "t1", kind: "thinking", text: "hm" }] })
  const old = await connect(token, { logTail: 1, fullLogs: ["a"] })
  expect((await old.snapshot).streams).toBeUndefined()
  app.ws.close(); old.ws.close()
})
```

- [ ] **Step 2: Run, expect FAIL** (`liveAppend` is not a function)

Run: `bun test src/channels/web/live-replies-ws.test.ts`

- [ ] **Step 3: Implement in `src/channels/web/index.ts`**

Import:

```ts
import { LiveReplies, type LiveKind, type LiveSocket } from "./live-replies"
```

In the class fields, next to `wsConnections`:

```ts
  /** Card #76: replies being written, for the apps that asked (`subscribe.streams`). */
  private readonly live = new LiveReplies()
  private readonly liveSockets = new WeakMap<import("bun").ServerWebSocket<WSData>, LiveSocket>()
```

Helper methods on the class:

```ts
  private liveSocket(ws: import("bun").ServerWebSocket<WSData>): LiveSocket {
    let socket = this.liveSockets.get(ws)
    if (!socket) {
      socket = { send: (json) => { ws.send(json) }, bufferedAmount: () => ws.getBufferedAmount() }
      this.liveSockets.set(ws, socket)
    }
    return socket
  }

  /** Card #76: more of a reply (or thinking) being written in `session`. */
  liveAppend(session: string, stream: string, kind: LiveKind, text: string): void {
    this.live.append(session, stream, kind, text)
  }

  /** Card #76: a live stream ended; `final` = its stored form follows with the same id. */
  liveClose(stream: string, final: boolean): void {
    this.live.close(stream, final)
  }

  /** Card #76: the session's turn ended; anything still streaming there is dropped. */
  liveEndSession(session: string): void {
    this.live.endSession(session)
  }
```

In `stop()`, before `this.server?.stop(true)`:

```ts
    this.live.stop()
```

In `onWsClose`, after `this.fss.dropSocket(ws)`:

```ts
    const liveSocket = this.liveSockets.get(ws)
    if (liveSocket) this.live.leave(liveSocket)
```

In the `subscribe` handler:
- right after `const lazyArchived = frame.lazyArchived === true`:

```ts
      // Card #76: `streams: 1` asks for replies while they are written (reply_stream frames) for the chats
      // this socket shows. Old apps never ask, so they never get them.
      const liveSocket = typeof frame.streams === "number" && frame.streams >= 1 ? this.liveSocket(ws) : undefined
      if (liveSocket) this.live.join(liveSocket)
```

- in the `snapshot` object literal, after `...(trimExtras ? { partialExtras } : {}),`:

```ts
        ...(liveSocket ? { streams: this.live.snapshot(liveSocket, logTail !== undefined ? fullLogs : sessions.map((s) => s.id ?? s.name)) } : {}),
```

In the `viewing` handler, the socket's own on-screen set follows the same frames:
- in the multi-chat branch, after `this.opts.viewingTracker?.setSessions(...)`:

```ts
        this.watchLive(ws, visible ? many : [])
```

- after `this.opts.viewingTracker?.update(ws.data.deviceName, { session, visible })`:

```ts
      this.watchLive(ws, session === null || (!visible && session === undefined) ? [] : visible ? [session] : [...this.liveWatching(ws)].filter((s) => s !== session))
```

and the two helpers:

```ts
  /** Card #76: the chats this socket shows, for live text (an app that never asked has no live socket). */
  private watchLive(ws: import("bun").ServerWebSocket<WSData>, sessions: string[]): void {
    const socket = this.liveSockets.get(ws)
    if (socket) this.live.watch(socket, sessions)
  }

  private liveWatching(ws: import("bun").ServerWebSocket<WSData>): ReadonlySet<string> {
    const socket = this.liveSockets.get(ws)
    return socket ? this.live.watching(socket) : new Set()
  }
```

- [ ] **Step 4: Run, expect PASS**

Run: `bun test src/channels/web/live-replies-ws.test.ts src/channels/web/live-replies.test.ts src/channels/web/snapshot-slim.test.ts src/channels/web/snapshot-log-tail.test.ts`

- [ ] **Step 5: Type-check the broker** (catches the `main.ts` wiring from Task 5)

Run: `bunx tsc --noEmit -p tsconfig.json 2>&1 | grep -E "live|stream" | head`
Expected: no errors mentioning the new code. If the project's type check is noisy for unrelated reasons, compare with the same command on `dev`.

- [ ] **Step 6: Commit Tasks 5 and 6**

```bash
git add src/core/agents/claude/activity-event.ts src/core/agents/core-bridge/core-adapter.ts src/core/agents/core-bridge/core-adapter.test.ts src/core/session-manager/messages.ts src/main.ts src/channels/web/index.ts src/channels/web/live-replies-ws.test.ts
git commit -m "feat(broker): reply_stream frames for apps that ask and show the chat; stored rows carry the stream (#76)"
```

---

### Task 7: Thinking switches for Claude and Codex

**Files:**
- Create: `src/core/agents/thinking-args.ts`
- Test: `src/core/agents/thinking-args.test.ts`
- Modify: `src/core/agents/claude/core-host.ts:176`, `src/core/agents/codex/core-host.ts:147`

- [ ] **Step 1: Write the failing test**

```ts
import { expect, test } from "bun:test"
import { claudeThinkingArgs, codexReasoningArgs } from "./thinking-args"

test("Claude streams summarized thinking unless told otherwise", () => {
  expect(claudeThinkingArgs({})).toEqual(["--thinking-display", "summarized"])
  expect(claudeThinkingArgs({ MUX_CLAUDE_THINKING_DISPLAY: "highlights" })).toEqual(["--thinking-display", "highlights"])
  expect(claudeThinkingArgs({ MUX_CLAUDE_THINKING_DISPLAY: "none" })).toEqual([])
  expect(claudeThinkingArgs({ MUX_CLAUDE_THINKING_DISPLAY: "bogus" })).toEqual([])
})

test("Codex writes reasoning summaries unless told otherwise", () => {
  expect(codexReasoningArgs({})).toEqual(["-c", 'model_reasoning_summary="auto"'])
  expect(codexReasoningArgs({ MUX_CODEX_REASONING_SUMMARY: "detailed" })).toEqual(["-c", 'model_reasoning_summary="detailed"'])
  expect(codexReasoningArgs({ MUX_CODEX_REASONING_SUMMARY: "none" })).toEqual([])
  expect(codexReasoningArgs({ MUX_CODEX_REASONING_SUMMARY: "bogus" })).toEqual([])
})
```

- [ ] **Step 2: Run, expect FAIL**

Run: `bun test src/core/agents/thinking-args.test.ts`

- [ ] **Step 3: Implement `src/core/agents/thinking-args.ts`**

```ts
// Card #76: an agent's thinking text reaches supermux only when the agent is asked for it. Both switches are
// process flags: they apply when a session's CLI next starts, and a keeper-held session runs on unchanged
// until then. "none" (or any value the CLI does not know) leaves the agent's own default.

const CLAUDE_DISPLAYS = new Set(["summarized", "omitted", "highlights"])
const CODEX_SUMMARIES = new Set(["auto", "concise", "detailed"])

/**
 * Claude Code's hidden `--thinking-display`. Without it, its stream-json carries thinking deltas with no
 * text. A changed display costs one prompt-cache miss on the session's first request after a restart.
 */
export function claudeThinkingArgs(env: Record<string, string | undefined> = process.env): string[] {
  const value = (env.MUX_CLAUDE_THINKING_DISPLAY ?? "summarized").trim()
  return CLAUDE_DISPLAYS.has(value) ? ["--thinking-display", value] : []
}

/** Codex writes reasoning summaries (item/reasoning/summaryTextDelta) only when configured to. */
export function codexReasoningArgs(env: Record<string, string | undefined> = process.env): string[] {
  const value = (env.MUX_CODEX_REASONING_SUMMARY ?? "auto").trim()
  return CODEX_SUMMARIES.has(value) ? ["-c", `model_reasoning_summary=${JSON.stringify(value)}`] : []
}
```

- [ ] **Step 4: Wire it**

`src/core/agents/claude/core-host.ts`: import `{ claudeThinkingArgs } from "../thinking-args"`, and change the `args:` line to:

```ts
        args: [...(await claudeAccountArgs(registration.args ? [...registration.args] : [], {
          sessionHome: typeof registration.extra?.sessionHome === "string" ? registration.extra.sessionHome : claudeSessionHome(String(registration.extra?.sessionName ?? registration.id)),
          account: ctx.account,
        })), ...claudeThinkingArgs()],
```

`src/core/agents/codex/core-host.ts`: import `{ codexReasoningArgs } from "../thinking-args"`, and change the `args:` line to:

```ts
        args: [...(registration.args ? registration.args : BROKER_CODEX_ARGS), ...codexReasoningArgs()],
```

- [ ] **Step 5: Run the switch test and both hosts' tests, expect PASS**

Run: `bun test src/core/agents/thinking-args.test.ts src/core/agents/claude src/core/agents/codex`

A host test that pins the exact argument list gets the new flags appended in its expectation, or sets the env to `none` where it is about something else.

- [ ] **Step 6: Commit**

```bash
git add src/core/agents/thinking-args.ts src/core/agents/thinking-args.test.ts src/core/agents/claude/core-host.ts src/core/agents/codex/core-host.ts
git commit -m "feat(broker): ask Claude and Codex for their thinking text (switchable) (#76)"
```

---

### Task 8: Whole-suite run and a real end-to-end check (no live broker restart)

- [ ] **Step 1: Run the broker suite under the heavy-job lock**

```bash
flock -w 3600 /tmp/supermux-heavy.lock systemd-run --user --scope -q -p MemoryMax=8G -- bun test 2>&1 | tail -30
```

Expected: the same failures as on `dev` (check with `git stash`-free means: run the same command in `~/projects/supermux` only if a failure looks unrelated) and none new.

- [ ] **Step 2: Run the core suite**

```bash
cd packages/supermux-core && flock -w 3600 /tmp/supermux-heavy.lock systemd-run --user --scope -q -p MemoryMax=8G -- bun test tests 2>&1 | tail -15
```

- [ ] **Step 3: End-to-end on a throwaway broker.**
  - Start a broker from this worktree on a spare port, with its own state directory (the `#75` shadow-broker recipe: `~/.mux/previews/perf75/start.sh`). Never restart `mux.service`.
  - Mint a device.
  - Connect a WebSocket with `subscribe {streams: 1}`, create a Claude (Haiku) session and a Codex (luna) session, and send `viewing` for each.
  - Send a prompt that asks for a short paragraph, then record frames.

  Expected per session: `reply_stream` frames at ~50 ms spacing; `kind: "thinking"` frames before the reply where the agent thinks; then `message_append` whose `entry.stream` equals the frames' `stream`; and for thinking, `activity_append` whose `event.stream` equals the thinking frames' `stream`.

- [ ] **Step 4: Note the cost** (the Haiku `usage` cost from the session's result, the Codex tokens) in the card comment.
