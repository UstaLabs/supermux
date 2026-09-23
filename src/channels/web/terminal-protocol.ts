// The /ws/term wire, revision 2.
//
// WHY A REVISION AT ALL. Revision 1 was not a protocol, it was a habit: raw
// binary frames for output, a handful of ad-hoc JSON objects the client
// recognised by substring (`t.contains("\"type\":\"exit\"")`), and a `reset`
// the CHANNEL invented at socket-open — outside the backend's own ordering, so
// it could not say which epoch it belonged to and a mid-stream re-sync looked
// exactly like the first attach. A client could not tell "your program ended"
// from "I lost your program", which is the difference between closing a tab and
// reconnecting to it.
//
// Revision 2 says all of it out loud:
//
//   ready(epoch) → reset(e) → replay-start(e) → output* → replay-end(e) → output*
//
// and then at most one of `exit` (the program ended) or `failure` (we lost it).
// `owner` may arrive at any point after `ready`. Binary frames ARE the output
// events: WebSocket ordering is what puts them between the control frames, so
// nothing needs a sequence number — but it is also why every frame of a
// connection must be written through ONE lane (see `TerminalFrameLane`). Two
// callbacks writing to the same socket cannot promise that a `reset` lands in
// front of the bytes it invalidates.
//
// NEGOTIATION. A socket asks for a revision with `?terminalProtocol=2`. An
// unrecognised revision is answered with a `failure` frame and closed — there
// is no dual-mode rendering. Omitting the parameter selects the legacy
// revision-1 framing, which exists only until Plan 4 Tasks 3-5 delete the last
// client that speaks it.

import type { WorkspaceTerminalEvent } from "../../core/terminal/workspace-backend"

/** The revision this broker speaks. Bump only with a matching Kotlin bump. */
export const TERMINAL_PROTOCOL_VERSION = 2

/** Every revision-2 frame the broker sends, in the vocabulary of the wire. */
export type TerminalServerControl =
  /** Always first. `epoch` opens the CONNECTION's epoch, so control frames that
   * arrive before the backend's first `reset` still carry one; each `reset`
   * then opens a new render epoch. Ownership is never assumed: `replyOwner` is
   * false until an `owner` frame says otherwise. */
  | { type: "ready"; version: number; epoch: string; replyOwner: boolean; ownerGeneration: number }
  | { type: "reset"; epoch: string }
  | { type: "replay-start"; epoch: string }
  | { type: "replay-end"; epoch: string }
  | { type: "owner"; epoch: string; enabled: boolean; ownerGeneration: number }
  /** The TARGET PROCESS ended. `known: false` means nobody reaped a status, and
   * `code`/`signal` are then both null — reporting 0 would claim a clean exit
   * no one observed. A signalled exit carries `signal` with a null `code`. */
  | { type: "exit"; known: boolean; code: number | null; signal: number | null }
  /** We lost the target, or never reached it. NOT an exit: a recoverable
   * failure is a reconnect, an exit closes the tab. */
  | { type: "failure"; code: string; recoverable: boolean; message: string }

/** Every revision-2 text frame a viewer sends. Binary frames are user input. */
export type TerminalClientControl =
  | { type: "resize"; cols: number; rows: number }
  | { type: "focus"; focused: boolean; cols: number; rows: number }
  /** A terminal REPLY the viewer's emulator produced (DA/DSR/kitty answers),
   * base64 in `data`. Stamped with the epoch and owner generation it was
   * produced under so a reply to a screen that no longer exists — or to an
   * ownership this viewer has since lost — is dropped instead of being typed
   * into the shell. */
  | { type: "reply"; epoch: string; ownerGeneration: number; data: string }
  | { type: "close" }

export type DecodedClientControl =
  | { ok: true; frame: TerminalClientControl }
  | { ok: false; reason: string }

const MAX_DIMENSION = 5000

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function dimension(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isInteger(value)) return null
  if (value < 0 || value > MAX_DIMENSION) return null
  return value
}

/**
 * Decode one viewer text frame. Deliberately a real decoder: every field is
 * checked and a frame that does not typecheck is REFUSED with a reason, rather
 * than half-applied. Revision 1 read these with `JSON.parse` + `typeof` tests
 * inline in the socket handler, which is how `{"type":"resize"}` with a string
 * `cols` used to reach the pty as `NaN`.
 */
export function decodeClientControl(text: string): DecodedClientControl {
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return { ok: false, reason: "malformed json" }
  }
  if (!isRecord(parsed)) return { ok: false, reason: "not an object" }
  const type = parsed.type
  if (typeof type !== "string") return { ok: false, reason: "missing type" }
  switch (type) {
    case "resize": {
      const cols = dimension(parsed.cols)
      const rows = dimension(parsed.rows)
      if (cols === null || rows === null) return { ok: false, reason: "resize needs integer cols/rows" }
      return { ok: true, frame: { type: "resize", cols, rows } }
    }
    case "focus": {
      if (typeof parsed.focused !== "boolean") return { ok: false, reason: "focus needs a boolean focused" }
      // A blur carries no geometry worth believing; 0 means "unknown" and the
      // manager treats it as "keep the size you have".
      const cols = parsed.cols === undefined ? 0 : dimension(parsed.cols)
      const rows = parsed.rows === undefined ? 0 : dimension(parsed.rows)
      if (cols === null || rows === null) return { ok: false, reason: "focus needs integer cols/rows" }
      return { ok: true, frame: { type: "focus", focused: parsed.focused, cols, rows } }
    }
    case "reply": {
      if (typeof parsed.epoch !== "string" || !parsed.epoch) return { ok: false, reason: "reply needs an epoch" }
      if (typeof parsed.ownerGeneration !== "number" || !Number.isInteger(parsed.ownerGeneration)) {
        return { ok: false, reason: "reply needs an integer ownerGeneration" }
      }
      if (typeof parsed.data !== "string") return { ok: false, reason: "reply needs base64 data" }
      return {
        ok: true,
        frame: { type: "reply", epoch: parsed.epoch, ownerGeneration: parsed.ownerGeneration, data: parsed.data },
      }
    }
    case "close":
      return { ok: true, frame: { type: "close" } }
    default:
      return { ok: false, reason: `unknown frame ${type}` }
  }
}

/** base64 → bytes, without throwing on a viewer's bad payload. */
export function decodeReplyPayload(data: string): Uint8Array | null {
  try {
    const binary = atob(data)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
    return bytes
  } catch {
    return null
  }
}

export function encodeServerControl(frame: TerminalServerControl): string {
  return JSON.stringify(frame)
}

export type RevisionChoice =
  | { ok: true; revision: 1 | 2 }
  | { ok: false; message: string }

/**
 * What `?terminalProtocol=` asked for.
 *
 * Absent selects revision 1 — the legacy framing, kept alive only for the
 * clients Plan 4 Tasks 3-5 delete. Anything that is not a revision this broker
 * speaks is REFUSED by name, because "your client is too old" and "your client
 * is too new" are both actionable and neither looks like a network fault.
 */
export function parseTerminalRevision(raw: string | null): RevisionChoice {
  if (raw === null) return { ok: true, revision: 1 }
  if (raw === String(TERMINAL_PROTOCOL_VERSION)) return { ok: true, revision: 2 }
  return {
    ok: false,
    message: `terminal protocol ${JSON.stringify(raw)} is not supported; this broker speaks revision ${TERMINAL_PROTOCOL_VERSION}`,
  }
}

/** Where a lane puts its frames. One socket, three operations. */
export interface TerminalFrameSink {
  text(payload: string): void
  /** May return a promise: that promise IS the backpressure the backend awaits. */
  binary(bytes: Uint8Array): void | Promise<void>
  close(code: number, reason: string): void
}

/**
 * THE ORDERING LANE.
 *
 * Every frame of one revision-2 connection goes through here, and here is the
 * only place that writes to the socket. Two things fall out of that:
 *
 *  1. ORDER. Backend events arrive from one serialised source, but the frames
 *     they produce are asynchronous (a binary write can return a drain
 *     promise). A promise chain keeps the NEXT frame behind the previous one,
 *     so `reset` can never overtake the output it invalidates and a late
 *     `exit` can never land in front of the bytes the program printed before
 *     it. Revision 1 had a separate `onReset` and `onData` callback and could
 *     promise neither.
 *
 *  2. STATE. The epoch, the owner generation and whether the replay boundary
 *     has closed are derived from the frames as they are written, not tracked
 *     alongside them — so what the lane believes it told the client is what it
 *     actually told the client. `acceptsReply` answers from exactly that.
 */
export class TerminalFrameLane {
  #tail: Promise<void> = Promise.resolve()
  #sink: TerminalFrameSink
  #epoch: string
  #ownerGeneration = 0
  #replyOwner = false
  #replayClosed = false
  #insideReplay = false
  #ready = false
  #finished = false

  constructor(sink: TerminalFrameSink, connectionEpoch: string) {
    this.#sink = sink
    this.#epoch = connectionEpoch
  }

  get epoch(): string { return this.#epoch }
  get ownerGeneration(): number { return this.#ownerGeneration }
  get replyOwner(): boolean { return this.#replyOwner }
  get replayClosed(): boolean { return this.#replayClosed }
  /** True once `exit`/`failure` went out: the connection is over. */
  get finished(): boolean { return this.#finished }

  /** Queue work behind everything already queued. Never rejects. */
  #enqueue(work: () => void | Promise<void>): Promise<void> {
    const next = this.#tail.then(work).catch(() => undefined)
    this.#tail = next
    return next
  }

  #control(frame: TerminalServerControl): void {
    this.#sink.text(encodeServerControl(frame))
  }

  /** The first frame of every connection. */
  ready(): Promise<void> {
    if (this.#ready) return this.#tail
    this.#ready = true
    return this.#enqueue(() => {
      this.#control({
        type: "ready",
        version: TERMINAL_PROTOCOL_VERSION,
        epoch: this.#epoch,
        replyOwner: this.#replyOwner,
        ownerGeneration: this.#ownerGeneration,
      })
    })
  }

  /**
   * One backend event, as revision-2 frames. This is the ONLY mapping between
   * the two vocabularies; the socket handler does not get to invent frames of
   * its own (which is how the unconditional open-time `reset` happened).
   */
  event(event: WorkspaceTerminalEvent): Promise<void> {
    switch (event.type) {
      case "reset":
        return this.#enqueue(() => {
          // A new epoch voids everything drawn under the old one — including
          // anything the viewer still had queued to send under it.
          this.#epoch = event.epoch
          this.#replayClosed = false
          this.#insideReplay = false
          this.#control({ type: "reset", epoch: event.epoch })
        })
      case "replay-start":
        return this.#enqueue(() => {
          this.#insideReplay = true
          this.#control({ type: "replay-start", epoch: this.#epoch })
        })
      case "replay-end":
        return this.#enqueue(() => {
          this.#insideReplay = false
          this.#replayClosed = true
          this.#control({ type: "replay-end", epoch: this.#epoch })
        })
      case "output":
        // Awaited: a congested socket becomes backpressure on the target
        // rather than an unbounded queue in the broker.
        return this.#enqueue(() => this.#sink.binary(event.bytes))
      case "owner":
        return this.#enqueue(() => {
          this.#replyOwner = event.enabled
          this.#ownerGeneration += 1
          this.#control({ type: "owner", epoch: this.#epoch, enabled: event.enabled, ownerGeneration: this.#ownerGeneration })
        })
      case "exit":
        return this.#enqueue(() => {
          if (this.#finished) return
          this.#finished = true
          this.#control({ type: "exit", known: event.known, code: event.code, signal: event.signal })
          this.#sink.close(1000, "terminal exited")
        })
      case "failure":
        return this.failure(event.code, event.recoverable, event.message)
    }
  }

  /**
   * Something went wrong on OUR side of the target. Sent as a `failure`, never
   * as a fabricated `exit` with code 0: "I cannot see your program" is a
   * reconnect and "your program ended" closes the tab, and revision 1 reported
   * the first as the second.
   */
  failure(code: string, recoverable: boolean, message: string): Promise<void> {
    return this.#enqueue(() => {
      if (this.#finished) return
      this.#finished = true
      this.#control({ type: "failure", code, recoverable, message: message.slice(0, 500) })
      this.#sink.close(recoverable ? 1011 : 1000, code)
    })
  }

  /**
   * May this reply reach the pty?
   *
   * Four ways to say no, and every one of them means "those bytes are not an
   * answer any more, they are keystrokes":
   *  - the replay boundary has not closed (an answer to history),
   *  - this viewer does not own the size (some other viewer already answered),
   *  - the epoch moved (the screen that asked is gone),
   *  - the ownership generation moved (the lease was re-taken since).
   */
  acceptsReply(frame: Extract<TerminalClientControl, { type: "reply" }>): boolean {
    if (!this.#replayClosed || this.#insideReplay) return false
    if (!this.#replyOwner) return false
    if (frame.epoch !== this.#epoch) return false
    if (frame.ownerGeneration !== this.#ownerGeneration) return false
    return true
  }
}
