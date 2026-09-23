// The contract between TerminalManager and whatever keeps a WORKSPACE terminal
// alive between viewers: zmx on POSIX, sessiond/ConPTY on Windows.
//
// Today's tmux path conflates three things this interface deliberately splits:
//
//  1. CREATION vs ATTACHMENT. `tmux new-session -A` attaches if the session
//     exists and creates it otherwise, so a reconnect to a terminal whose shell
//     exited silently RESURRECTS it with a fresh shell — the tab comes back
//     alive and empty, and the exit is never reported. Here `ensure` creates
//     and `attachExisting` only attaches: a UI "new terminal" calls `ensure`,
//     a reconnect calls ONLY `attachExisting`, and a reconnect to a target that
//     died rejects with `target-not-found` instead of respawning it.
//
//  2. REPLAY vs LIVE OUTPUT. tmux hands a reattaching client a redraw with no
//     marker saying where history ends, so a client cannot tell "this is the
//     screen as of now" from "this is the program writing". Every attach here
//     opens an epoch and emits `reset` → `replay-start` → `output`* →
//     `replay-end`, all carrying the SAME epoch string; bytes after
//     `replay-end` are live. A backend that re-syncs mid-stream opens a NEW
//     epoch (a second `reset`), which tells the client to drop what it had.
//
//  3. SIZE OWNERSHIP. tmux picks the size from client activity
//     (`window-size latest`). supermux wants the FOCUSED device to own it, so
//     focus is an explicit call and the backend tells each viewer whether it
//     currently owns the size via the `owner` event.
//
// Viewer writes are split into `write` (user input) and `reply` (a terminal
// REPLY the client's emulator produced — DA/DSR/kitty query answers). They land
// on the same pty, and keeping them apart is what lets the backend apply
// OPPOSITE rules to them:
//
//   * `write` from any viewer reaches the pty. Typing is typing, and it never
//     moves size ownership.
//   * `reply` is OWNER-ONLY. A non-owning viewer's reply is DISCARDED — not
//     queued, not answered later. Every viewer renders the same DA1/DSR query
//     and every one of them answers it, so anything past the first answer is
//     read by the shell as typed input. One query, one answer, from the viewer
//     that owns the size.
//
// A background tab must therefore never be expected to satisfy a query. Both
// calls return false when the byte could not be accepted (viewer gone, queue
// full) so the caller can apply backpressure rather than silently losing
// bytes; `reply` returning true means "handed to the backend", which for a
// non-owner still ends in the drop described above.

/** A workspace terminal's identity. `scope` is TerminalManager's namespace —
 * "w:<workspaceId>" for a workspace terminal (see core/workspace/scope.ts), or
 * a session name for a session-scoped one. */
export type WorkspaceTerminalKey = { scope: string; terminalId: string }

export type WorkspaceTerminalSummary = WorkspaceTerminalKey & { createdAt: number }

/**
 * What a viewer observes. ORDER IS PART OF THE CONTRACT:
 *
 *   reset(e) → replay-start(e) → output* → replay-end(e) → output* …
 *
 * and then at most one terminal event — `exit` (the target's process ended) or
 * `failure` (we lost the target, or never reached it). `owner` may arrive at
 * any point after the first `reset`. A backend never emits `output` before the
 * first `reset`, and never nests epochs.
 */
export type WorkspaceTerminalEvent =
  | { type: "reset"; epoch: string }
  | { type: "replay-start"; epoch: string }
  | { type: "output"; bytes: Uint8Array }
  | { type: "replay-end"; epoch: string }
  | { type: "owner"; enabled: boolean }
  /**
   * The TARGET PROCESS ended. Never synthesised from a lost connection: a
   * closed socket is also what a killed backend, a crashed one and a dropped
   * connection look like, and "your program ended" closes a tab while "I
   * cannot see your program" retries.
   *
   * `known: false` means the backend saw the program end but could not reap a
   * status — the pty closing and the child becoming reapable RACE — so `code`
   * and `signal` are both null. Reporting code 0 there would claim a clean
   * exit nobody observed. A signalled exit carries `signal` with a null
   * `code`, because 0 is a real exit code and "killed by SIGHUP" is not it.
   */
  | { type: "exit"; known: boolean; code: number | null; signal: number | null }
  | { type: "failure"; code: string; recoverable: boolean; message: string }

export interface WorkspaceTerminalViewer {
  /** User input. false = not accepted (detached, or the bounded queue is full). */
  write(bytes: Uint8Array): boolean
  /** A terminal reply the client's emulator produced. Same pty, own priority. */
  reply(bytes: Uint8Array): boolean
  /** Report this viewer's geometry. Only the owning viewer's size reaches the pty. */
  resize(cols: number, rows: number): Promise<void>
  /** Claim or release size ownership. The newest claim wins; releasing hands it
   * back to the next-newest claimant, or to the backend's default policy. */
  focus(active: boolean, cols: number, rows: number): Promise<void>
  /** Drop this viewer. The target SURVIVES. Idempotent. */
  detach(): Promise<void>
}

export interface WorkspaceTerminalBackend {
  /**
   * Create the target if it does not exist. Never resurrects a target that
   * existed and exited — callers distinguish the two through known-terminal
   * state, not through this call. Concurrent `ensure`s for one key must yield
   * exactly ONE target.
   */
  ensure(key: WorkspaceTerminalKey, options: {
    cwd: string
    shell: string
    env: Record<string, string>
    cols: number
    rows: number
  }): Promise<void>
  /**
   * Attach a viewer to an EXISTING target. Rejects with a
   * `WorkspaceTerminalError` of code `target-not-found` when there is none —
   * including when the target is closed while this attach is in flight.
   */
  attachExisting(key: WorkspaceTerminalKey, viewerId: string,
    emit: (event: WorkspaceTerminalEvent) => Promise<void>): Promise<WorkspaceTerminalViewer>
  /** Targets in this scope, oldest first. Survives a broker restart: the source
   * of truth is the running targets, not in-process state. */
  list(scope: string): Promise<WorkspaceTerminalSummary[]>
  exists(key: WorkspaceTerminalKey): Promise<boolean>
  /** Destroy one target and its viewers. Idempotent. */
  close(key: WorkspaceTerminalKey): Promise<void>
  /** Destroy every target in EXACTLY this scope — never a neighbouring one. */
  closeScope(scope: string): Promise<void>
  /** Broker shutdown: drop viewers, keep every target running. */
  shutdownViewers(): Promise<void>
}

/**
 * Codes shared by thrown errors and the `failure` event, so a client sees one
 * vocabulary whether a problem happened during attach or mid-stream.
 *
 * - `target-not-found` — no such target (never created, already exited, or
 *   closed while the attach was in flight). NOT recoverable by retrying: the
 *   caller must decide to create a new terminal or drop the tab.
 * - `name-too-long` — the key does not fit the socket-path limit. Raised
 *   BEFORE creation; names are never truncated (that would collide).
 * - `socket-dir-unsafe` — our private socket directory is missing, not a
 *   directory, not ours, or group/world accessible.
 * - `backend-unavailable` — the helper/daemon could not be reached. Recoverable.
 * - `protocol` — the target spoke something we could not decode.
 */
export type WorkspaceTerminalErrorCode =
  | "target-not-found"
  | "name-too-long"
  | "socket-dir-unsafe"
  | "backend-unavailable"
  | "protocol"

export class WorkspaceTerminalError extends Error {
  readonly code: WorkspaceTerminalErrorCode
  readonly recoverable: boolean
  constructor(code: WorkspaceTerminalErrorCode, message: string, recoverable = false) {
    super(message)
    this.name = "WorkspaceTerminalError"
    this.code = code
    this.recoverable = recoverable
  }
  /** Shape this error as the event a viewer would be sent. */
  toEvent(): Extract<WorkspaceTerminalEvent, { type: "failure" }> {
    return { type: "failure", code: this.code, recoverable: this.recoverable, message: this.message }
  }
}

export function isWorkspaceTerminalError(
  error: unknown,
  code?: WorkspaceTerminalErrorCode,
): error is WorkspaceTerminalError {
  return error instanceof WorkspaceTerminalError && (code === undefined || error.code === code)
}
