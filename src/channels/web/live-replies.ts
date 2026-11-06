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
