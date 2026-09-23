// The wire between the broker (Bun) and the zmx helper (Zig), version 1.
//
// WHY A HELPER AT ALL. zmx's own IPC is `packed struct { tag: u8, len: u32 }`
// headers followed by NATIVE-ENDIAN extern structs on an AF_UNIX socket, with
// three tail bytes of padding that are real on the wire. Decoding that in
// TypeScript would mean hand-maintaining a second copy of every struct layout
// and re-deriving the padding rules per platform, and getting it silently
// wrong the first time upstream adds a field. So the native protocol is spoken
// by a Zig helper compiled against the PATCHED zmx source's own `ipc.zig`, and
// this file defines the much smaller, explicitly-sized envelope between that
// helper and us.
//
// THE ENVELOPE. Exactly five bytes, then the payload:
//
//     +--------+------------------------+-----------------+
//     | tag u8 | payload length u32 LE  | payload (len B) |
//     +--------+------------------------+-----------------+
//
// Little-endian and 5 bytes on every platform — chosen so it is NOT zmx's
// header (which is 8 bytes with padding and native-endian), because a wire
// this side hand-decodes must have no platform-dependent parts at all.
//
//  tag 1  control   a JSON object: named operations, events, version, epoch
//  tag 2  output    raw pty bytes, helper -> broker
//  tag 3  input     raw user keystrokes, broker -> helper
//  tag 4  reply     raw terminal REPLY bytes (DA/DSR answers), broker -> helper
//
// Input and reply are separate tags for the same reason they are separate tags
// in the patched daemon: a query answer is not typing, and only the FOCUS
// OWNER's answer may reach the pty (see `reply()` in workspace-backend.ts).
//
// Payloads are capped at 64 KiB. Output is chunked by the sender to fit;
// a control message that does not fit is an ERROR, never split, because
// reassembling a split JSON object would mean buffering unbounded text from
// a length field we do not control.
//
// Diagnostics go to the helper's stderr. stdout carries frames and nothing
// else — a stray log line on stdout is a protocol violation, and this decoder
// reports it as one instead of resynchronising.

/** Helper protocol version. Carried as `v` in every control message. */
export const HELPER_PROTOCOL_VERSION = 1

/** Envelope size: one tag byte + a four-byte unsigned little-endian length. */
export const FRAME_HEADER_BYTES = 5

/** Largest payload in one frame (64 KiB). */
export const MAX_FRAME_PAYLOAD = 64 * 1024

/**
 * Most bytes the decoder ever retains between pushes: one header plus one
 * maximum payload. It is a CONSTANT, not a function of the length field on the
 * wire — an oversized length is rejected from the header alone, so a peer
 * cannot make us allocate by claiming 4 GiB.
 */
export const PENDING_MAX = FRAME_HEADER_BYTES + MAX_FRAME_PAYLOAD

export const TAG_CONTROL = 1
export const TAG_OUTPUT = 2
export const TAG_INPUT = 3
export const TAG_REPLY = 4

const TAG_NAMES: Record<number, "control" | "output" | "input" | "reply"> = {
  [TAG_CONTROL]: "control",
  [TAG_OUTPUT]: "output",
  [TAG_INPUT]: "input",
  [TAG_REPLY]: "reply",
}

/** A control message: any JSON object carrying our version in `v`. */
export type HelperControlMessage = { v: number } & Record<string, unknown>

export type DecodedFrame =
  | { type: "control"; message: HelperControlMessage }
  | { type: "output"; bytes: Uint8Array }
  | { type: "input"; bytes: Uint8Array }
  | { type: "reply"; bytes: Uint8Array }

export type ProtocolFailure =
  | "tag"        // a tag byte that is not one of ours
  | "length"     // a payload length over MAX_FRAME_PAYLOAD
  | "json"       // a control payload that is not a JSON object
  | "version"    // a control object whose `v` is not ours
  | "truncated"  // EOF with a partial frame pending
  | "failed"     // pushed into a decoder that already failed
  | "oversize"   // (encoder) a payload that cannot be framed

/**
 * A malformed helper stream. Carries the frames decoded BEFORE the bad byte:
 * they were valid, the caller already owes them to its viewer, and dropping
 * them would lose real output on the way to reporting the failure.
 */
export class HelperProtocolError extends Error {
  readonly reason: ProtocolFailure
  readonly decoded: DecodedFrame[]
  constructor(reason: ProtocolFailure, message: string, decoded: DecodedFrame[] = []) {
    super(message)
    this.name = "HelperProtocolError"
    this.reason = reason
    this.decoded = decoded
  }
}

const EMPTY = new Uint8Array(0)

/**
 * Stream decoder. `push` takes whatever the pipe produced — a byte, half a
 * header, nine frames and a fragment — and returns the frames that are now
 * complete, in order.
 *
 * The buffer is bounded: a frame is only retained while it is incomplete, and
 * the only length that can ever be retained has already been checked against
 * MAX_FRAME_PAYLOAD.
 */
export class FrameDecoder {
  #pending: Uint8Array = EMPTY
  #failed: ProtocolFailure | null = null

  /** Bytes currently held for an incomplete frame. Never exceeds PENDING_MAX. */
  get pendingBytes(): number {
    return this.#pending.length
  }

  push(chunk: Uint8Array): DecodedFrame[] {
    if (this.#failed) {
      throw new HelperProtocolError("failed", `helper stream already failed (${this.#failed})`)
    }
    const buffer = this.#pending.length === 0 ? chunk : concatBytes(this.#pending, chunk)
    const frames: DecodedFrame[] = []
    let at = 0
    try {
      while (buffer.length - at >= FRAME_HEADER_BYTES) {
        const tag = buffer[at]!
        const name = TAG_NAMES[tag]
        if (!name) throw this.#fail("tag", `unknown helper frame tag ${tag}`, frames)
        // Read the length as u32 LE by hand rather than through a DataView on
        // `buffer.buffer`: a Uint8Array can be a view into a larger
        // ArrayBuffer at a nonzero offset, and byteOffset bugs here read
        // somebody else's bytes as a length.
        //
        // The top byte is ADDED, not OR'd: `|` coerces to int32, so a length
        // of 0xffffffff would come back as -1 and sail past the cap below.
        const len = (buffer[at + 1]! | (buffer[at + 2]! << 8) | (buffer[at + 3]! << 16)) +
          buffer[at + 4]! * 0x1000000
        if (len > MAX_FRAME_PAYLOAD) {
          throw this.#fail("length", `helper frame payload ${len} exceeds ${MAX_FRAME_PAYLOAD}`, frames)
        }
        const end = at + FRAME_HEADER_BYTES + len
        if (end > buffer.length) break // keep the partial frame, wait for more
        // slice() copies: the frame must not alias a buffer we are about to
        // compact, and the consumer keeps output bytes past this call.
        const payload = buffer.slice(at + FRAME_HEADER_BYTES, end)
        frames.push(name === "control"
          ? { type: "control", message: this.#parseControl(payload, frames) }
          : { type: name, bytes: payload })
        at = end
      }
    } finally {
      this.#pending = at >= buffer.length ? EMPTY : buffer.slice(at)
    }
    return frames
  }

  /** The stream ended. A partial frame here is a truncation, never a frame. */
  end(): void {
    if (this.#failed) return
    if (this.#pending.length > 0) {
      throw this.#fail("truncated", `helper stream ended with ${this.#pending.length} bytes of a partial frame`)
    }
  }

  #parseControl(payload: Uint8Array, decoded: DecodedFrame[]): HelperControlMessage {
    let parsed: unknown
    try {
      parsed = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(payload))
    } catch (error) {
      throw this.#fail("json", `helper control frame is not JSON: ${errorText(error)}`, decoded)
    }
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      throw this.#fail("json", "helper control frame is not a JSON object", decoded)
    }
    const message = parsed as HelperControlMessage
    if (message.v !== HELPER_PROTOCOL_VERSION) {
      // Not negotiable and never downgraded: a peer speaking another version
      // is not speaking this one, and reinterpreting its fields through our
      // shapes is exactly the bug the version field exists to prevent.
      throw this.#fail(
        "version",
        `helper control frame version ${JSON.stringify(message.v)}, expected ${HELPER_PROTOCOL_VERSION}`,
        decoded,
      )
    }
    return message
  }

  #fail(reason: ProtocolFailure, message: string, decoded: DecodedFrame[] = []): HelperProtocolError {
    this.#failed = reason
    return new HelperProtocolError(reason, message, decoded)
  }
}

function concatBytes(a: Uint8Array, b: Uint8Array): Uint8Array {
  const out = new Uint8Array(a.length + b.length)
  out.set(a, 0)
  out.set(b, a.length)
  return out
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

/** Frame `payload` under `tag`. Throws rather than truncating an oversize payload. */
export function encodeFrame(tag: number, payload: Uint8Array): Uint8Array {
  if (payload.length > MAX_FRAME_PAYLOAD) {
    throw new HelperProtocolError(
      "oversize",
      `helper frame payload ${payload.length} exceeds ${MAX_FRAME_PAYLOAD}`,
    )
  }
  const out = new Uint8Array(FRAME_HEADER_BYTES + payload.length)
  out[0] = tag
  out[1] = payload.length & 0xff
  out[2] = (payload.length >>> 8) & 0xff
  out[3] = (payload.length >>> 16) & 0xff
  out[4] = (payload.length >>> 24) & 0xff
  out.set(payload, FRAME_HEADER_BYTES)
  return out
}

export function encodeControl(message: Record<string, unknown>): Uint8Array {
  return encodeFrame(TAG_CONTROL, new TextEncoder().encode(JSON.stringify(message)))
}

export function encodeInput(bytes: Uint8Array): Uint8Array {
  return encodeFrame(TAG_INPUT, bytes)
}

export function encodeReply(bytes: Uint8Array): Uint8Array {
  return encodeFrame(TAG_REPLY, bytes)
}

/** Split a buffer into frame-sized pieces, in order. Empty in, nothing out. */
export function* outputChunks(bytes: Uint8Array): Generator<Uint8Array> {
  for (let at = 0; at < bytes.length; at += MAX_FRAME_PAYLOAD) {
    yield bytes.subarray(at, Math.min(at + MAX_FRAME_PAYLOAD, bytes.length))
  }
}

// ---------------------------------------------------------------------------
//  Control vocabulary
// ---------------------------------------------------------------------------
//
// Commands are broker -> helper and carry an `id` the helper echoes back in
// exactly one `ok` or `error`. Events are helper -> broker and are unsolicited.
//
// ONE HELPER PROCESS IS ONE VIEWER. `attach` binds the process to a single
// target for its lifetime; killing the helper detaches that viewer and nothing
// else. Destroying a target is `kill`, a separate, explicit command.

export type HelperCommand =
  /** Create the target if it is not already running. Never attaches. */
  | {
    v: number; id: number; op: "create"
    /** The `mux.target` label value: the reversible encodeName(key). */
    name: string
    /** Absolute path of the session socket (short, opaque basename). */
    socket: string
    /** argv of the program to run. NOT a shell command string. */
    argv: string[]
    /** The child's complete environment. Nothing is inherited implicitly. */
    env: Record<string, string>
    cwd: string
    cols: number; rows: number
  }
  /** Attach THIS helper process to an existing target as one broker viewer. */
  | { v: number; id: number; op: "attach"; name: string; socket: string; cols: number; rows: number }
  /** Enumerate our sockets in `dir` and read each one's `mux.target` label. */
  | { v: number; id: number; op: "list"; dir: string }
  /** Claim (`active`) or release the focus lease, with the claimant's geometry. */
  | { v: number; id: number; op: "focus"; active: boolean; cols: number; rows: number }
  /** Report geometry under the lease we currently hold. Dropped if we do not. */
  | { v: number; id: number; op: "resize"; cols: number; rows: number }
  /** Drop this viewer. The target survives; the helper then exits. */
  | { v: number; id: number; op: "detach" }
  /**
   * Destroy a target. Explicit, and never a side effect of helper exit.
   *
   * `name` is REQUIRED and is not decoration: a socket basename is a 20-hex
   * hash of the key, so the path alone cannot prove which daemon answers it.
   * The helper reads the daemon's `mux.target` label and refuses a kill it
   * cannot match — destroying a hash collision would destroy another
   * workspace's shell, which is the one outcome this naming scheme exists to
   * prevent (see names.ts, `assertTargetMatches`).
   */
  | { v: number; id: number; op: "kill"; socket: string; name: string }

/**
 * A command without its envelope. `Omit` over a union collapses to the keys
 * every member shares, which would quietly accept `{op:"focus", socket:...}`;
 * distributing it keeps each op's own fields.
 */
export type HelperCommandBody = HelperCommand extends infer C
  ? (C extends HelperCommand ? Omit<C, "v" | "id"> : never)
  : never

export type HelperEvent =
  /** First frame the helper ever writes: who it is and what it was built from. */
  | { v: number; ev: "hello"; abi: number; zmx: string; patch: string; helper: string }
  | { v: number; ev: "ok"; id: number; result?: unknown }
  /**
   * A PARTIAL result for `id`, ahead of that request's `ok`.
   *
   * Only `list` produces these. The listing used to be built as one control
   * frame, and a frame over `MAX_PAYLOAD` is refused by the helper's own
   * framer — logged and dropped, so the `ok` never came, the broker waited out
   * its send timeout and killed the helper. During a close, where `list` is
   * what decides whether a shell is gone.
   *
   * The rows are concatenated in arrival order and handed to the waiter with
   * the `ok`'s own rows appended last. A request that fails part way through
   * gets an `error` and no `ok`, so a partial listing can never be resolved as
   * a complete one.
   */
  | { v: number; ev: "chunk"; id: number; result: unknown[] }
  | { v: number; ev: "error"; id: number | null; code: string; message: string }
  /** The patched daemon accepted our BrokerHello. */
  | { v: number; ev: "welcome"; version: number; leaseGen: string; pendingMax: number; snapshotMax: number }
  /** We hold the focus lease under `gen`, at the geometry the shell believes. */
  | { v: number; ev: "lease"; gen: string; cols: number; rows: number }
  /** We released the lease. (Losing it to another viewer is silent — see helper.ts.) */
  | { v: number; ev: "blur" }
  | { v: number; ev: "replay-start"; epoch: string; bytes: number }
  | { v: number; ev: "replay-end"; epoch: string; bytes: number }
  /**
   * The TARGET PROCESS ended. `known: false` means the daemon saw the pty
   * close but could not reap a status — the shell is gone, but neither `code`
   * nor `signal` is a fact. Never synthesised from EOF.
   */
  | { v: number; ev: "exit"; known: boolean; code: number | null; signal: number | null }
  /** Handshake/protocol failure reported by the daemon. */
  | { v: number; ev: "failure"; code: number; name: string; message: string }
  /** The daemon dropped this viewer, and why. The target may still be running. */
  | { v: number; ev: "detached"; code: number; reason: string; message: string }
  /**
   * We lost the socket. NOT an exit: "the shell ended" closes a tab, "I cannot
   * see the shell" retries. The helper exits after this, saying nothing about
   * the target's fate.
   */
  | { v: number; ev: "lost"; message: string }

/** Detach reasons the patched daemon can send (ipc.BrokerDetachReason). */
export const DETACH_REASONS: Record<number, string> = {
  1: "resync_required",
  2: "daemon_shutdown",
  3: "snapshot_overflow",
}

/** Failure codes the patched daemon can send (ipc.BrokerFailureCode). */
export const FAILURE_CODES: Record<number, string> = {
  1: "unsupported_version",
  2: "protocol",
  3: "snapshot_overflow",
  4: "backend_unavailable",
}

/** Narrow a decoded control message to an event by its `ev` discriminator. */
export function asEvent(message: HelperControlMessage): HelperEvent | null {
  return typeof message.ev === "string" ? (message as HelperEvent) : null
}
