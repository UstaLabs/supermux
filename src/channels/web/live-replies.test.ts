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
