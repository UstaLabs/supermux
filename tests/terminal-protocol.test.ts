// Revision-2 wire fixtures. THE SAME STRINGS ARE DECODED BY THE KOTLIN CLIENT:
// `apps/shared/src/commonTest/kotlin/dev/supermux/net/TerminalProtocolTest.kt`
// holds them verbatim, and the last test in this file reads that file and
// fails if a fixture drifts out of one side. A protocol both ends only
// *believe* they share is how revision 1 ended up with a client that matched
// frames by substring.

import { test, expect } from "bun:test"
import { readFileSync } from "fs"
import { join } from "path"
import {
  TERMINAL_PROTOCOL_VERSION,
  TerminalFrameLane,
  decodeClientControl,
  decodeReplyPayload,
  encodeServerControl,
  parseTerminalRevision,
  type TerminalFrameSink,
  type TerminalServerControl,
} from "../src/channels/web/terminal-protocol"

// ── server → client ────────────────────────────────────────────────────────
export const SERVER_FIXTURES = {
  ready: `{"type":"ready","version":2,"epoch":"c-1","replyOwner":false,"ownerGeneration":0}`,
  readyUnknownVersion: `{"type":"ready","version":3,"epoch":"c-1","replyOwner":false,"ownerGeneration":0}`,
  resetA: `{"type":"reset","epoch":"e-a"}`,
  replayStartA: `{"type":"replay-start","epoch":"e-a"}`,
  replayEndA: `{"type":"replay-end","epoch":"e-a"}`,
  resetB: `{"type":"reset","epoch":"e-b"}`,
  replayStartB: `{"type":"replay-start","epoch":"e-b"}`,
  replayEndB: `{"type":"replay-end","epoch":"e-b"}`,
  ownerOn: `{"type":"owner","epoch":"e-a","enabled":true,"ownerGeneration":1}`,
  ownerStaleEpoch: `{"type":"owner","epoch":"e-gone","enabled":true,"ownerGeneration":9}`,
  replayStartStaleEpoch: `{"type":"replay-start","epoch":"e-gone"}`,
  exitNonZero: `{"type":"exit","known":true,"code":3,"signal":null}`,
  exitUnknown: `{"type":"exit","known":false,"code":null,"signal":null}`,
  exitSignalled: `{"type":"exit","known":true,"code":null,"signal":9}`,
  // The word "exit" inside a MESSAGE. Revision 1's client matched
  // `contains("\"type\":\"exit\"")`, so text like this closed the tab on what
  // is really a reconnect.
  failureRecoverableSayingExit: `{"type":"failure","code":"backend-unavailable","recoverable":true,"message":"zmx helper exited before the attach completed"}`,
  failureFatal: `{"type":"failure","code":"target-not-found","recoverable":false,"message":"no such terminal"}`,
  failureUnsupportedRevision: `{"type":"failure","code":"protocol-unsupported","recoverable":false,"message":"terminal protocol \\"1\\" is not supported; this broker speaks revision 2"}`,
  malformed: `{"type":"reset","epoch":`,
  unknownType: `{"type":"bell","epoch":"e-a"}`,
} as const

// ── client → server ────────────────────────────────────────────────────────
export const CLIENT_FIXTURES = {
  resize: `{"type":"resize","cols":120,"rows":40}`,
  focus: `{"type":"focus","focused":true,"cols":120,"rows":40}`,
  blur: `{"type":"focus","focused":false}`,
  // DSR cursor-position answer (ESC [ 24 ; 1 R) for epoch e-a, generation 1.
  reply: `{"type":"reply","epoch":"e-a","ownerGeneration":1,"data":"G1syNDsxUg=="}`,
  close: `{"type":"close"}`,
  resizeNotANumber: `{"type":"resize","cols":"120","rows":40}`,
} as const

/** The bytes of "ok→" — the arrow is 3 bytes and the fixtures split it. */
const UTF8_CHUNK_A = Uint8Array.from([0x6f, 0x6b, 0xe2])
const UTF8_CHUNK_B = Uint8Array.from([0x86, 0x92, 0x0a])

function recorder() {
  const frames: Array<string | Uint8Array> = []
  const closes: Array<{ code: number; reason: string }> = []
  const sink: TerminalFrameSink = {
    text: (payload) => { frames.push(payload) },
    binary: (bytes) => { frames.push(bytes) },
    close: (code, reason) => { closes.push({ code, reason }) },
  }
  return { frames, closes, sink }
}

/** A sink whose binary writes block until the returned release() is called. */
function blockingRecorder() {
  const frames: Array<string | Uint8Array> = []
  const closes: Array<{ code: number; reason: string }> = []
  const pending: Array<() => void> = []
  const sink: TerminalFrameSink = {
    text: (payload) => { frames.push(payload) },
    binary: (bytes) => {
      frames.push(bytes)
      return new Promise<void>((resolve) => { pending.push(resolve) })
    },
    close: (code, reason) => { closes.push({ code, reason }) },
  }
  return { frames, closes, sink, releaseAll: () => { for (const p of pending.splice(0)) p() } }
}

test("revision negotiation: 2 is spoken, absent is legacy, anything else is refused by name", () => {
  expect(parseTerminalRevision("2")).toEqual({ ok: true, revision: 2 })
  expect(parseTerminalRevision(null)).toEqual({ ok: true, revision: 1 })
  const old = parseTerminalRevision("1")
  expect(old.ok).toBe(false)
  if (!old.ok) {
    expect(encodeServerControl({ type: "failure", code: "protocol-unsupported", recoverable: false, message: old.message }))
      .toBe(SERVER_FIXTURES.failureUnsupportedRevision)
  }
  expect(parseTerminalRevision("banana").ok).toBe(false)
  expect(TERMINAL_PROTOCOL_VERSION).toBe(2)
})

test("a fresh connection is ready → reset → replay-start → output* → replay-end → output*", async () => {
  const { frames, sink } = recorder()
  const lane = new TerminalFrameLane(sink, "c-1")
  await lane.ready()
  await lane.event({ type: "reset", epoch: "e-a" })
  await lane.event({ type: "replay-start", epoch: "e-a" })
  await lane.event({ type: "output", bytes: UTF8_CHUNK_A })
  await lane.event({ type: "output", bytes: UTF8_CHUNK_B })
  await lane.event({ type: "replay-end", epoch: "e-a" })
  await lane.event({ type: "output", bytes: Uint8Array.from([0x24]) })

  expect(frames[0]).toBe(SERVER_FIXTURES.ready)
  expect(frames[1]).toBe(SERVER_FIXTURES.resetA)
  expect(frames[2]).toBe(SERVER_FIXTURES.replayStartA)
  expect(frames[3]).toEqual(UTF8_CHUNK_A)
  expect(frames[4]).toEqual(UTF8_CHUNK_B)
  expect(frames[5]).toBe(SERVER_FIXTURES.replayEndA)
  expect(frames[6]).toEqual(Uint8Array.from([0x24]))
  // A replay with no output at all is legal: the boundary still closes.
  const empty = recorder()
  const lane2 = new TerminalFrameLane(empty.sink, "c-2")
  await lane2.ready()
  await lane2.event({ type: "reset", epoch: "e-a" })
  await lane2.event({ type: "replay-start", epoch: "e-a" })
  await lane2.event({ type: "replay-end", epoch: "e-a" })
  expect(empty.frames.filter((f) => typeof f !== "string")).toEqual([])
  expect(empty.frames[3]).toBe(SERVER_FIXTURES.replayEndA)
})

test("a reset queued behind a stalled output frame still lands after it", async () => {
  const { frames, sink, releaseAll } = blockingRecorder()
  const lane = new TerminalFrameLane(sink, "c-1")
  await lane.ready()
  void lane.event({ type: "output", bytes: UTF8_CHUNK_A })
  // Queued while the write above is still stalled on the socket's drain. A
  // second callback writing straight to the socket would overtake it here.
  const reset = lane.event({ type: "reset", epoch: "e-b" })
  await new Promise((r) => setTimeout(r, 0))
  expect(frames).toEqual([SERVER_FIXTURES.ready, UTF8_CHUNK_A])
  releaseAll()
  await reset
  expect(frames).toEqual([SERVER_FIXTURES.ready, UTF8_CHUNK_A, SERVER_FIXTURES.resetB])
  expect(lane.epoch).toBe("e-b")
})

test("exit carries known/code/signal and closes; failure never masquerades as one", async () => {
  const a = recorder()
  const lane = new TerminalFrameLane(a.sink, "c-1")
  await lane.event({ type: "exit", known: true, code: 3, signal: null })
  expect(a.frames).toEqual([SERVER_FIXTURES.exitNonZero])
  expect(a.closes).toEqual([{ code: 1000, reason: "terminal exited" }])
  // A second terminal event after the connection is finished is dropped.
  await lane.event({ type: "failure", code: "backend-unavailable", recoverable: true, message: "late" })
  expect(a.frames.length).toBe(1)

  const unreaped = recorder()
  await new TerminalFrameLane(unreaped.sink, "c-2").event({ type: "exit", known: false, code: null, signal: null })
  expect(unreaped.frames[0]).toBe(SERVER_FIXTURES.exitUnknown)

  const signalled = recorder()
  await new TerminalFrameLane(signalled.sink, "c-3").event({ type: "exit", known: true, code: null, signal: 9 })
  expect(signalled.frames[0]).toBe(SERVER_FIXTURES.exitSignalled)

  const lost = recorder()
  const lostLane = new TerminalFrameLane(lost.sink, "c-4")
  await lostLane.event({
    type: "failure",
    code: "backend-unavailable",
    recoverable: true,
    message: "zmx helper exited before the attach completed",
  })
  expect(lost.frames).toEqual([SERVER_FIXTURES.failureRecoverableSayingExit])
  // Crucially: not an exit frame, however much the text talks about exiting.
  expect(lost.frames.some((f) => typeof f === "string" && JSON.parse(f).type === "exit")).toBe(false)
})

test("owner frames stamp the live epoch and a rising generation", async () => {
  const { frames, sink } = recorder()
  const lane = new TerminalFrameLane(sink, "c-1")
  await lane.ready()
  await lane.event({ type: "reset", epoch: "e-a" })
  await lane.event({ type: "replay-start", epoch: "e-a" })
  await lane.event({ type: "replay-end", epoch: "e-a" })
  await lane.event({ type: "owner", enabled: true })
  expect(frames.at(-1)).toBe(SERVER_FIXTURES.ownerOn)
  expect(lane.replyOwner).toBe(true)
  expect(lane.ownerGeneration).toBe(1)
  await lane.event({ type: "owner", enabled: false })
  expect(JSON.parse(String(frames.at(-1)))).toEqual({ type: "owner", epoch: "e-a", enabled: false, ownerGeneration: 2 })
})

test("a reply is accepted only past replay-end, while owning, on the live epoch and generation", async () => {
  const { sink } = recorder()
  const lane = new TerminalFrameLane(sink, "c-1")
  const reply = (epoch: string, generation: number) =>
    ({ type: "reply", epoch, ownerGeneration: generation, data: "G1syNDsxUg==" }) as const
  await lane.ready()
  await lane.event({ type: "reset", epoch: "e-a" })
  await lane.event({ type: "replay-start", epoch: "e-a" })
  // An answer produced while the replay is still being drawn is an answer to
  // HISTORY; the shell would read it as typing.
  await lane.event({ type: "owner", enabled: true })
  expect(lane.acceptsReply(reply("e-a", 1))).toBe(false)
  await lane.event({ type: "replay-end", epoch: "e-a" })
  expect(lane.acceptsReply(reply("e-a", 1))).toBe(true)
  // Stale epoch, stale generation, and a viewer that has lost the size.
  expect(lane.acceptsReply(reply("e-gone", 1))).toBe(false)
  expect(lane.acceptsReply(reply("e-a", 0))).toBe(false)
  await lane.event({ type: "owner", enabled: false })
  expect(lane.acceptsReply(reply("e-a", 2))).toBe(false)
})

test("viewer control frames decode by shape, not by substring", () => {
  expect(decodeClientControl(CLIENT_FIXTURES.resize)).toEqual({ ok: true, frame: { type: "resize", cols: 120, rows: 40 } })
  expect(decodeClientControl(CLIENT_FIXTURES.focus)).toEqual({ ok: true, frame: { type: "focus", focused: true, cols: 120, rows: 40 } })
  expect(decodeClientControl(CLIENT_FIXTURES.blur)).toEqual({ ok: true, frame: { type: "focus", focused: false, cols: 0, rows: 0 } })
  expect(decodeClientControl(CLIENT_FIXTURES.close)).toEqual({ ok: true, frame: { type: "close" } })
  const reply = decodeClientControl(CLIENT_FIXTURES.reply)
  expect(reply.ok).toBe(true)
  if (reply.ok && reply.frame.type === "reply") {
    expect(reply.frame.epoch).toBe("e-a")
    expect(reply.frame.ownerGeneration).toBe(1)
    expect(decodeReplyPayload(reply.frame.data)).toEqual(new TextEncoder().encode("\u001b[24;1R"))
  }
  // A string where a number belongs used to reach the pty as NaN.
  expect(decodeClientControl(CLIENT_FIXTURES.resizeNotANumber).ok).toBe(false)
  expect(decodeClientControl(SERVER_FIXTURES.malformed)).toEqual({ ok: false, reason: "malformed json" })
  expect(decodeClientControl(`[]`).ok).toBe(false)
  expect(decodeClientControl(SERVER_FIXTURES.unknownType).ok).toBe(false)
  expect(decodeReplyPayload("!!not base64!!")).toBe(null)
})

test("every fixture is decoded by the Kotlin client too", () => {
  const kotlin = readFileSync(
    join(import.meta.dir, "../apps/shared/src/commonTest/kotlin/dev/supermux/net/TerminalProtocolTest.kt"),
    "utf8",
  )
  const missing: string[] = []
  for (const [name, fixture] of [...Object.entries(SERVER_FIXTURES), ...Object.entries(CLIENT_FIXTURES)]) {
    // Kotlin holds these in raw ("""…""") strings, where the fixture appears
    // verbatim; an escaped literal would appear with `\"` for every quote.
    // Accept either spelling, as long as the BYTES are the same.
    const escaped = fixture.replace(/\\/g, "\\\\").replace(/"/g, '\\"')
    if (!kotlin.includes(fixture) && !kotlin.includes(escaped)) missing.push(`${name}: ${fixture}`)
  }
  expect(missing).toEqual([])
})
