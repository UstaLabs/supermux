/**
 * A yamux session (github.com/hashicorp/yamux spec), the multiplexer frp runs every control and work
 * connection over when `transport.tcpMux` is on (its default, on both sides).
 *
 * Frame header, 12 bytes big-endian: version (0), type, flags, stream id, length.
 * Data carries `length` payload bytes; a window update grants `length` more bytes of send window;
 * a ping's `length` is an opaque value echoed back; a go-away's is an error code.
 */
import { Duplex } from 'node:stream'

const VERSION = 0
const HEADER = 12
const TYPE_DATA = 0
const TYPE_WINDOW_UPDATE = 1
const TYPE_PING = 2
const TYPE_GO_AWAY = 3
const FLAG_SYN = 1
const FLAG_ACK = 2
const FLAG_FIN = 4
const FLAG_RST = 8
/** The receive window every stream starts with (fixed by the spec). */
export const INITIAL_WINDOW = 256 * 1024
/** The largest data frame written, so one stream cannot hold the connection for long. */
const MAX_FRAME = 64 * 1024

export interface YamuxOptions {
  /** Client sessions open odd stream ids, servers even. */
  client: boolean
  /** How far each stream's receive window grows (frp: 6 MB); at least INITIAL_WINDOW. */
  maxStreamWindow: number
  /** Ping interval; 0 turns keepalive off. */
  keepAliveMs: number
  /** How long a ping (or the connection) may go unanswered before the session is closed. */
  timeoutMs: number
}

function header(type: number, flags: number, id: number, length: number): Buffer {
  const h = Buffer.allocUnsafe(HEADER)
  h.writeUInt8(VERSION, 0)
  h.writeUInt8(type, 1)
  h.writeUInt16BE(flags, 2)
  h.writeUInt32BE(id, 4)
  h.writeUInt32BE(length >>> 0, 8)
  return h
}

/** One multiplexed stream: a Duplex whose writes respect the peer's window. */
export class YamuxStream extends Duplex {
  private sendWindow = INITIAL_WINDOW
  private windowWaiters: Array<() => void> = []
  /** What the peer may still send us; grown back toward maxStreamWindow as the reader consumes. */
  private recvWindow = INITIAL_WINDOW
  private synSent = false
  private localFin = false
  private remoteFin = false
  private reset = false

  constructor(private readonly session: YamuxSession, readonly id: number, opened: 'local' | 'remote') {
    super({ allowHalfOpen: true })
    // A stream the peer opened was announced by its SYN; ours announces itself with its first frame.
    if (opened === 'remote') this.synSent = true
  }

  /** The SYN (ours) or ACK (theirs) the first frame we send carries. */
  private openFlags(): number {
    if (this.synSent) return 0
    this.synSent = true
    return FLAG_SYN
  }

  /** Announces the stream now, with the window it grows to (yamux's OpenStream). */
  open(): void {
    this.creditWindow(true)
  }

  override _write(chunk: Buffer, _encoding: BufferEncoding, done: (error?: Error | null) => void): void {
    void this.writeAll(chunk).then(() => done(), done)
  }

  private async writeAll(chunk: Buffer): Promise<void> {
    let offset = 0
    while (offset < chunk.length) {
      while (this.sendWindow === 0) {
        if (this.reset || this.destroyed) throw new Error('yamux stream reset')
        await new Promise<void>(r => this.windowWaiters.push(r))
      }
      if (this.reset || this.destroyed) throw new Error('yamux stream reset')
      const n = Math.min(this.sendWindow, chunk.length - offset, MAX_FRAME)
      this.sendWindow -= n
      const body = chunk.subarray(offset, offset + n)
      offset += n
      await this.session.sendAsync(Buffer.concat([header(TYPE_DATA, this.openFlags(), this.id, n), body]))
    }
  }

  override _final(done: (error?: Error | null) => void): void {
    if (!this.reset) {
      this.localFin = true
      this.session.send(header(TYPE_WINDOW_UPDATE, this.openFlags() | FLAG_FIN, this.id, 0))
    }
    this.maybeForget()
    done()
  }

  override _read(): void {
    this.creditWindow()
  }

  override _destroy(error: Error | null, done: (error?: Error | null) => void): void {
    if (!this.reset && !(this.localFin && this.remoteFin)) {
      this.reset = true
      this.session.send(header(TYPE_WINDOW_UPDATE, this.openFlags() | FLAG_RST, this.id, 0))
    }
    this.wakeWriters()
    this.session.forget(this.id)
    done(error)
  }

  /**
   * Grows the peer's window back to the maximum less what is still buffered here, as yamux's
   * sendWindowUpdate does: only once that is half the maximum, unless a SYN/ACK must go out anyway.
   */
  private creditWindow(force = false): void {
    if (this.reset) return
    const max = this.session.maxStreamWindow
    const delta = Math.max(0, max - this.readableLength - this.recvWindow)
    const flags = this.synSent ? 0 : FLAG_SYN
    if (!force && flags === 0 && delta < max / 2) return
    if (this.remoteFin && flags === 0) return
    this.recvWindow += delta
    this.session.send(header(TYPE_WINDOW_UPDATE, this.openFlags(), this.id, delta))
  }

  private wakeWriters(): void {
    const waiters = this.windowWaiters
    this.windowWaiters = []
    for (const w of waiters) w()
  }

  private maybeForget(): void {
    if (this.localFin && this.remoteFin) this.session.forget(this.id)
  }

  /** @internal A frame for this stream arrived. */
  receive(type: number, flags: number, length: number, body: Buffer | undefined): void {
    if (flags & FLAG_RST) {
      this.reset = true
      this.wakeWriters()
      this.destroy(new Error('yamux stream reset by peer'))
      return
    }
    if (type === TYPE_WINDOW_UPDATE && length > 0) {
      this.sendWindow += length
      this.wakeWriters()
    }
    if (type === TYPE_DATA && body && body.length > 0) {
      if (body.length > this.recvWindow) {
        this.session.fail(new Error('yamux peer exceeded the receive window'))
        return
      }
      this.recvWindow -= body.length
      this.push(body)
    }
    if (flags & FLAG_FIN && !this.remoteFin) {
      this.remoteFin = true
      this.push(null)
      this.maybeForget()
    }
  }

  /** @internal The session ended under it: quietly when it was closed on purpose. */
  abort(error: Error | undefined): void {
    this.reset = true
    this.wakeWriters()
    this.destroy(error)
  }
}

/** A yamux session over one connection (a TLS socket to frps). */
export class YamuxSession {
  private readonly streams = new Map<number, YamuxStream>()
  private nextId: number
  /** Received bytes not yet parsed, and the header of a frame whose body has not fully arrived. */
  private chunks: Buffer[] = []
  private queued = 0
  private frame?: { type: number; flags: number; id: number; length: number }
  private pingTimer?: ReturnType<typeof setInterval>
  private pendingPing?: { value: number; timer: ReturnType<typeof setTimeout> }
  private pingValue = 0
  private closedError?: Error
  private readonly closedListeners: Array<(error: Error) => void> = []
  /** Streams the peer opens; frps never does, so by default they are refused. */
  onStream?: (stream: YamuxStream) => void
  readonly maxStreamWindow: number

  constructor(private readonly conn: Duplex, private readonly options: YamuxOptions) {
    this.nextId = options.client ? 1 : 2
    this.maxStreamWindow = Math.max(INITIAL_WINDOW, options.maxStreamWindow)
    conn.on('data', (chunk: Buffer) => this.onData(chunk))
    conn.on('error', (error: Error) => this.fail(error))
    conn.on('close', () => this.fail(new Error('connection closed')))
    conn.on('end', () => this.fail(new Error('connection ended')))
    if (options.keepAliveMs > 0) {
      this.pingTimer = setInterval(() => this.ping(), options.keepAliveMs)
      this.pingTimer.unref?.()
    }
  }

  get closed(): boolean { return this.closedError !== undefined }

  /** Calls `listener` once the session ends (at once when it already has). */
  onClose(listener: (error: Error) => void): void {
    if (this.closedError) listener(this.closedError)
    else this.closedListeners.push(listener)
  }

  open(): YamuxStream {
    if (this.closedError) throw this.closedError
    const id = this.nextId
    this.nextId += 2
    const stream = new YamuxStream(this, id, 'local')
    this.streams.set(id, stream)
    stream.open()
    return stream
  }

  /** Ends the session: tells the peer (go-away), resets every stream, closes the connection. */
  close(): void {
    if (this.closedError) return
    try { this.conn.write(header(TYPE_GO_AWAY, 0, 0, 0)) } catch { /* already gone */ }
    this.fail(new Error('session closed'), true)
  }

  /** @internal */
  send(frame: Buffer): void {
    if (this.closedError) return
    this.conn.write(frame)
  }

  /** @internal Writes `frame`, resolving once the connection takes more (backpressure). */
  sendAsync(frame: Buffer): Promise<void> {
    if (this.closedError) return Promise.reject(this.closedError)
    if (this.conn.write(frame)) return Promise.resolve()
    return new Promise((resolve, reject) => {
      const onDrain = () => { this.conn.off('close', onClose); resolve() }
      const onClose = () => { this.conn.off('drain', onDrain); reject(this.closedError ?? new Error('connection closed')) }
      this.conn.once('drain', onDrain)
      this.conn.once('close', onClose)
    })
  }

  /** @internal */
  forget(id: number): void {
    this.streams.delete(id)
  }

  /** @internal Ends the session on a protocol or connection failure (`closing`: on purpose, see close). */
  fail(error: Error, closing = false): void {
    if (this.closedError) return
    this.closedError = error
    if (this.pingTimer) clearInterval(this.pingTimer)
    if (this.pendingPing) clearTimeout(this.pendingPing.timer)
    for (const stream of [...this.streams.values()]) stream.abort(closing ? undefined : error)
    this.streams.clear()
    // A deliberate close lets the go-away reach the peer first.
    if (closing) this.conn.end(() => this.conn.destroy())
    else this.conn.destroy()
    for (const listener of this.closedListeners.splice(0)) listener(error)
  }

  private ping(): void {
    if (this.closedError || this.pendingPing) return
    const value = ++this.pingValue >>> 0
    const timer = setTimeout(() => this.fail(new Error('yamux keepalive timed out')), this.options.timeoutMs)
    timer.unref?.()
    this.pendingPing = { value, timer }
    this.send(header(TYPE_PING, FLAG_SYN, 0, value))
  }

  private onData(chunk: Buffer): void {
    this.chunks.push(chunk)
    this.queued += chunk.length
    while (!this.closedError) {
      if (!this.frame) {
        if (this.queued < HEADER) return
        const h = this.take(HEADER)
        const version = h.readUInt8(0)
        const type = h.readUInt8(1)
        if (version !== VERSION) return this.fail(new Error(`yamux: unsupported version ${version}`))
        if (type > TYPE_GO_AWAY) return this.fail(new Error(`yamux: unknown frame type ${type}`))
        this.frame = { type, flags: h.readUInt16BE(2), id: h.readUInt32BE(4), length: h.readUInt32BE(8) }
      }
      const { type, flags, id, length } = this.frame
      const bodyLength = type === TYPE_DATA ? length : 0
      if (bodyLength > this.maxStreamWindow) return this.fail(new Error('yamux: data frame larger than the window'))
      if (this.queued < bodyLength) return
      this.frame = undefined
      this.onFrame(type, flags, id, length, bodyLength ? this.take(bodyLength) : undefined)
    }
  }

  /** The next `n` queued bytes: a view when one chunk holds them, else one copy (never re-copying the queue). */
  private take(n: number): Buffer {
    this.queued -= n
    const first = this.chunks[0]!
    if (first.length >= n) {
      if (first.length === n) this.chunks.shift()
      else this.chunks[0] = first.subarray(n)
      return first.subarray(0, n)
    }
    const out = Buffer.allocUnsafe(n)
    let offset = 0
    while (offset < n) {
      const chunk = this.chunks[0]!
      const used = Math.min(chunk.length, n - offset)
      chunk.copy(out, offset, 0, used)
      offset += used
      if (used === chunk.length) this.chunks.shift()
      else this.chunks[0] = chunk.subarray(used)
    }
    return out
  }

  private onFrame(type: number, flags: number, id: number, length: number, body: Buffer | undefined): void {
    if (type === TYPE_PING) {
      if (flags & FLAG_SYN) this.send(header(TYPE_PING, FLAG_ACK, 0, length))
      else if (flags & FLAG_ACK && this.pendingPing?.value === length) {
        clearTimeout(this.pendingPing.timer)
        this.pendingPing = undefined
      }
      return
    }
    if (type === TYPE_GO_AWAY) return this.fail(new Error(`yamux: peer went away (code ${length})`))
    let stream = this.streams.get(id)
    if (!stream) {
      if (!(flags & FLAG_SYN)) return // late frame for a stream already gone
      if (!this.onStream) {
        this.send(header(TYPE_WINDOW_UPDATE, FLAG_RST, id, 0))
        return
      }
      stream = new YamuxStream(this, id, 'remote')
      this.streams.set(id, stream)
      this.send(header(TYPE_WINDOW_UPDATE, FLAG_ACK, id, 0))
      this.onStream(stream)
    }
    stream.receive(type, flags, length, body)
  }
}
