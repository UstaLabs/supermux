// The zmx implementation of WorkspaceTerminalBackend: what a POSIX workspace
// terminal actually runs on.
//
// One target is one zmx session, one daemon, one socket (see ./names.ts). One
// VIEWER is one helper process (see ./helper.ts). This file is the part that
// knows about BOTH: it owns the per-target registry the daemon cannot see, and
// it is where the contract's event order is produced.
//
// Three things are decided here rather than on the wire:
//
//  1. THE `owner` EVENT. The patched daemon sends `BrokerLease` to the viewer
//     that WON the lease and says nothing to the one that lost it (see
//     vendor/zmx/README.md §5). So a superseded viewer would keep believing it
//     owns the size until a resize is silently dropped. We do not add a
//     message for it: every broker viewer of a target is a helper of THIS
//     process, so the loser is derivable from the winner's `lease` — when a
//     lease lands on viewer X, whoever we last believed owned that target and
//     is not X is told `owner:false`. `owner:true` is never derived; it is
//     always the wire's own `lease` or the absence of one.
//
//     The one case this cannot see is a lease granted to a viewer in another
//     broker process. There is none: a target's socket lives in our private
//     0700 directory, and a stock `zmx attach` cannot take a held lease
//     (`setLeader` refuses).
//
//  2. THE REPLAY EPOCH. The daemon opens a restore boundary with
//     `BrokerReplayStart(epoch)`; the contract also wants a `reset` in front of
//     it, carrying the same epoch, so a client knows to drop what it had. That
//     `reset` is synthesised here, from the epoch the daemon chose — never from
//     a counter of ours, so a re-sync mid-stream is one epoch on both sides.
//
//  3. THE STARTUP ENVIRONMENT. The helper gives the child EXACTLY the
//     environment we hand it — nothing is inherited. So the policy has to live
//     somewhere, and it lives here: a bounded allowlist (below), not the
//     broker's whole `environ`.
//
// Nothing in here ever falls back to tmux. A zmx that cannot be reached is a
// typed `backend-unavailable`, which the client can retry; quietly starting a
// tmux session instead would leave two backends owning one workspace.
import { accessSync, constants, statSync } from "fs"
import { isAbsolute } from "path"
import { makeLogger } from "../../../shared/log"
import { STATE_DIR } from "../../../shared/paths"
import {
  WorkspaceTerminalError,
  type WorkspaceTerminalBackend,
  type WorkspaceTerminalEvent,
  type WorkspaceTerminalKey,
  type WorkspaceTerminalSummary,
  type WorkspaceTerminalViewer,
} from "../workspace-backend"
import { ZmxHelper, type HelperBinaries, type HelperHandlers } from "./helper"
import {
  assertNameFits,
  decodeName,
  encodeName,
  ensureSocketDir,
  targetSocketPath,
  zmxSocketDir,
} from "./names"
import type { HelperCommandBody, HelperEvent } from "./protocol"

const log = makeLogger("terminal.zmx")

/** Geometry an attach starts at, before the first `focus`/`resize` says more.
 * The daemon already knows the real size; this only fills the helper's fields. */
const ATTACH_COLS = 80
const ATTACH_ROWS = 24

/**
 * Host variables a workspace shell may inherit.
 *
 * Today's tmux path gives the shell whatever the tmux SERVER inherited, which
 * is the broker's entire environment — including its tokens, its socket paths
 * and every MUX_* knob. The helper hands the child exactly what we pass and
 * nothing else, so this is where that stops being accidental.
 *
 * The list is what a login-ish interactive shell needs to be itself: who and
 * where the user is, how to find programs, how to render text, and the sockets
 * a developer shell is expected to have (ssh-agent, the display, the session
 * bus). Everything else — the broker's own configuration above all — is left
 * out by construction rather than filtered out by name.
 */
export const WORKSPACE_ENV_ALLOWLIST: readonly string[] = [
  // Identity and location.
  "HOME", "USER", "LOGNAME", "SHELL", "PWD", "TMPDIR",
  // Program lookup.
  "PATH", "MANPATH", "INFOPATH",
  // Locale and time.
  "LANG", "LANGUAGE", "TZ",
  "LC_ALL", "LC_CTYPE", "LC_MESSAGES", "LC_NUMERIC", "LC_TIME",
  "LC_COLLATE", "LC_MONETARY", "LC_PAPER", "LC_NAME", "LC_ADDRESS",
  "LC_TELEPHONE", "LC_MEASUREMENT", "LC_IDENTIFICATION",
  // XDG base directories — a shell's own config/state lives under these.
  "XDG_RUNTIME_DIR", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME",
  "XDG_STATE_HOME", "XDG_CONFIG_DIRS", "XDG_DATA_DIRS",
  // Sockets an interactive developer shell is expected to find.
  "SSH_AUTH_SOCK", "DISPLAY", "WAYLAND_DISPLAY", "DBUS_SESSION_BUS_ADDRESS",
]

/**
 * Terminal description the client's emulator actually implements.
 *
 * Deliberately NOT inherited: `TERM` is not in the allowlist, so the broker's
 * own (a service manager's `dumb`, or nothing at all) can never become the
 * shell's. Today's tmux path does the same thing by another route — its
 * `default-terminal` overrides whatever the tmux server was started with. A
 * caller's `env` still wins, which is how a workspace could pin something else.
 */
const TERM_DEFAULTS: Record<string, string> = {
  TERM: "xterm-256color",
  COLORTERM: "truecolor",
}

/**
 * The child's complete environment: the allowlisted host variables, the
 * terminal defaults, then the caller's own `env` last.
 *
 * `ensure`'s `env` is an OVERLAY, not a replacement. The caller (TerminalManager)
 * knows workspace-specific variables; it does not know, and should not have to
 * restate, what a shell needs to start.
 */
export function workspaceStartupEnvironment(
  host: Readonly<Record<string, string | undefined>> = process.env,
  overlay: Readonly<Record<string, string>> = {},
): Record<string, string> {
  const env: Record<string, string> = {}
  for (const name of WORKSPACE_ENV_ALLOWLIST) {
    const value = host[name]
    if (typeof value === "string" && value.length > 0) env[name] = value
  }
  for (const [name, value] of Object.entries(TERM_DEFAULTS)) {
    if (env[name] === undefined) env[name] = value
  }
  for (const [name, value] of Object.entries(overlay)) {
    if (typeof value === "string") env[name] = value
  }
  return env
}

/** What `list` gets back from the helper, per socket it probed. */
type ZmxListRow = {
  socket: string
  name: string
  pid: number
  createdAt: number
  clients: number
}

/** Milliseconds from whatever unit a daemon reported its creation time in.
 * zmx stores a unix timestamp; seconds and milliseconds are told apart by
 * magnitude, because no real session was created before 2001 in milliseconds. */
function createdAtMs(value: unknown): number {
  const n = typeof value === "number" && Number.isFinite(value) ? value : 0
  if (n <= 0) return 0
  return n < 1e12 ? Math.round(n * 1000) : Math.round(n)
}

/** The subset of a running helper this backend drives. `ZmxHelper` satisfies
 * it; a test double can too, without a process. */
export interface ZmxHelperFacade {
  send<T = unknown>(command: HelperCommandBody): Promise<T>
  write(bytes: Uint8Array): boolean
  reply(bytes: Uint8Array): boolean
  close(): Promise<void>
  kill(): void
}

export type ZmxHelperLaunch = (handlers: HelperHandlers) => Promise<ZmxHelperFacade>

/** Filesystem questions `ensure` asks before it creates anything. Injected so
 * the contract tests can run without a real shell on the box. */
export interface ZmxProbe {
  isDirectory(path: string): boolean
  isExecutableFile(path: string): boolean
  which(name: string): string | null
}

const defaultProbe: ZmxProbe = {
  isDirectory: path => {
    try { return statSync(path).isDirectory() } catch { return false }
  },
  isExecutableFile: path => {
    try {
      if (!statSync(path).isFile()) return false
      accessSync(path, constants.X_OK)
      return true
    } catch { return false }
  },
  which: name => {
    try { return Bun.which(name) } catch { return null }
  },
}

export interface ZmxBackendOptions {
  /** Where our sockets live. Default: `zmxSocketDir()`, prepared 0700. */
  socketDir?: string
  /** How a helper process is started. Default: a real `ZmxHelper`. */
  launch?: ZmxHelperLaunch
  /** Binaries the default launcher execs and verifies. */
  binaries?: HelperBinaries
  /** Host environment the startup allowlist is taken from. */
  hostEnv?: Readonly<Record<string, string | undefined>>
  /** Filesystem probe for cwd/shell validation. */
  probe?: ZmxProbe
  stateDir?: string
}

const keyOf = (key: WorkspaceTerminalKey) => `${encodeName(key)}`

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

/** Anything thrown across this boundary leaves as a typed workspace error. */
function asWorkspaceError(error: unknown, fallbackMessage: string): WorkspaceTerminalError {
  if (error instanceof WorkspaceTerminalError) return error
  return new WorkspaceTerminalError("backend-unavailable", `${fallbackMessage}: ${errorText(error)}`, true)
}

/** Per-target state the daemon cannot hold for us: who is watching, and which
 * of them we last saw take the lease. */
type TargetState = {
  key: WorkspaceTerminalKey
  viewers: Map<string, ZmxViewer>
  /** The viewer we last saw a `lease` for. Null means unowned as far as we know. */
  owner: ZmxViewer | null
  /** True between `close()` deciding to kill and its viewers going away, so the
   * helper EOF that follows is not reported to a client as a failure. */
  closing: boolean
}

export class ZmxWorkspaceBackend implements WorkspaceTerminalBackend {
  readonly #options: ZmxBackendOptions
  readonly #launch: ZmxHelperLaunch
  readonly #probe: ZmxProbe
  readonly #hostEnv: Readonly<Record<string, string | undefined>>
  readonly #targets = new Map<string, TargetState>()
  /** create/close serialisation, per logical target. */
  readonly #chain = new Map<string, Promise<unknown>>()
  #viewerSeq = 0

  constructor(options: ZmxBackendOptions = {}) {
    this.#options = options
    this.#probe = options.probe ?? defaultProbe
    this.#hostEnv = options.hostEnv ?? process.env
    this.#launch = options.launch ?? (handlers => ZmxHelper.launch(handlers, { binaries: options.binaries }))
  }

  // ---- plumbing ----------------------------------------------------------

  /** The socket directory, re-validated on every use: a directory that became
   * group-writable between two calls is not one we hand a shell through. */
  #dir(): string {
    if (this.#options.socketDir) return ensureSocketDir(this.#options.socketDir)
    return ensureSocketDir(zmxSocketDir(this.#hostEnv as NodeJS.ProcessEnv, this.#options.stateDir ?? STATE_DIR))
  }

  /** Run one control command on a helper that never attaches, then stop it.
   * `create`/`list`/`kill` are messages to a daemon, not viewers of one. */
  async #control<T>(run: (helper: ZmxHelperFacade) => Promise<T>): Promise<T> {
    let failure: WorkspaceTerminalError | undefined
    let onFailed: (() => void) | undefined
    const failed = new Promise<never>((_, reject) => {
      onFailed = () => reject(failure ?? new WorkspaceTerminalError("backend-unavailable", "zmx helper failed", true))
    })
    failed.catch(() => undefined) // never an unhandled rejection if `run` wins
    const handlers: HelperHandlers = {
      onOutput: () => {},
      onEvent: () => {},
      onFailure: error => { failure = error; onFailed?.() },
    }
    const helper = await this.#launch(handlers)
    try {
      return await Promise.race([run(helper), failed])
    } finally {
      helper.kill()
    }
  }

  /** Serialise by LOGICAL target, so two `ensure`s cannot both create and a
   * `close` cannot land between a create and the attach that follows it. */
  #serialize<T>(key: WorkspaceTerminalKey, run: () => Promise<T>): Promise<T> {
    const id = keyOf(key)
    const previous = this.#chain.get(id) ?? Promise.resolve()
    const next = previous.catch(() => undefined).then(run)
    // The chain link never rejects — one failed create must not poison every
    // later operation on that target — but the CALLER still sees the failure.
    const tail = next.then(() => undefined, () => undefined)
    this.#chain.set(id, tail)
    void tail.finally(() => {
      // Only drop the tail we installed; a later call may have replaced it.
      if (this.#chain.get(id) === tail) this.#chain.delete(id)
    })
    return next
  }

  #target(key: WorkspaceTerminalKey): TargetState {
    const id = keyOf(key)
    let target = this.#targets.get(id)
    if (!target) {
      target = { key, viewers: new Map(), owner: null, closing: false }
      this.#targets.set(id, target)
    }
    return target
  }

  #forget(target: TargetState): void {
    if (target.viewers.size > 0) return
    const id = keyOf(target.key)
    if (this.#targets.get(id) === target) this.#targets.delete(id)
  }

  // ---- the contract ------------------------------------------------------

  async ensure(key: WorkspaceTerminalKey, options: {
    cwd: string
    shell: string
    env: Record<string, string>
    cols: number
    rows: number
  }): Promise<void> {
    // Name and socket path first: both can be too long, and a target we could
    // never find again must not be created in order to discover that.
    const name = assertNameFits(key)
    const dir = this.#dir()
    const socket = targetSocketPath(dir, key)
    const cwd = this.#checkCwd(options.cwd)
    const argv = this.#shellArgv(options.shell)
    const env = workspaceStartupEnvironment(this.#hostEnv, { ...options.env, SHELL: argv[0]!, PWD: cwd })
    const cols = clampDimension(options.cols, ATTACH_COLS)
    const rows = clampDimension(options.rows, ATTACH_ROWS)

    await this.#serialize(key, async () => {
      await this.#control(helper => helper.send({ op: "create", name, socket, argv, env, cwd, cols, rows }))
      log.info("zmx_target_ensured", { scope: key.scope, terminalId: key.terminalId, socket })
    })
  }

  async attachExisting(
    key: WorkspaceTerminalKey,
    viewerId: string,
    emit: (event: WorkspaceTerminalEvent) => Promise<void>,
  ): Promise<WorkspaceTerminalViewer> {
    const name = assertNameFits(key)
    const socket = targetSocketPath(this.#dir(), key)
    const target = this.#target(key)
    if (target.closing) {
      this.#forget(target)
      throw new WorkspaceTerminalError("target-not-found", `workspace terminal ${name} is closing`)
    }

    // The viewer exists before the helper does, so events arriving between the
    // attach ack and this function returning are delivered in order rather
    // than racing a half-built object.
    const viewer = new ZmxViewer(this, target, `${viewerId}#${++this.#viewerSeq}`, viewerId, emit)
    const helper = await this.#launch(viewer.handlers).catch(error => {
      throw asWorkspaceError(error, "cannot start the zmx helper")
    })
    viewer.bind(helper)
    try {
      await helper.send({ op: "attach", name, socket, cols: ATTACH_COLS, rows: ATTACH_ROWS })
    } catch (error) {
      viewer.discard()
      throw asWorkspaceError(error, `cannot attach to workspace terminal ${name}`)
    }
    // Attaching is not instant. A close may have landed while we were in
    // flight; the target is gone and this viewer must not outlive it.
    if (target.closing || this.#targets.get(keyOf(key)) !== target) {
      viewer.discard()
      throw new WorkspaceTerminalError("target-not-found", `workspace terminal ${name} was closed while attaching`)
    }
    target.viewers.set(viewer.id, viewer)
    return viewer
  }

  async list(scope: string): Promise<WorkspaceTerminalSummary[]> {
    const rows = await this.#list()
    const out: WorkspaceTerminalSummary[] = []
    for (const row of rows) {
      const key = decodeName(row.name)
      if (!key || key.scope !== scope) continue
      out.push({ scope: key.scope, terminalId: key.terminalId, createdAt: createdAtMs(row.createdAt) })
    }
    // Oldest first. Ties keep a stable order so a repeated list does not
    // reshuffle a client's tab strip.
    out.sort((a, b) => a.createdAt - b.createdAt || a.terminalId.localeCompare(b.terminalId))
    return out
  }

  async exists(key: WorkspaceTerminalKey): Promise<boolean> {
    const name = encodeName(key)
    return (await this.#list()).some(row => row.name === name)
  }

  async close(key: WorkspaceTerminalKey): Promise<void> {
    const name = assertNameFits(key)
    const socket = targetSocketPath(this.#dir(), key)
    const target = this.#target(key)
    target.closing = true
    // Viewers go first: their helpers are about to see the daemon hang up, and
    // a close we asked for must not reach a client as "I lost your terminal".
    for (const viewer of [...target.viewers.values()]) viewer.discard()
    this.#forget(target)
    await this.#serialize(key, async () => {
      // `name` makes the kill identity-checked: a socket basename is a hash,
      // and killing a collision would destroy another workspace's shell.
      await this.#control(helper => helper.send({ op: "kill", socket, name }))
      log.info("zmx_target_closed", { scope: key.scope, terminalId: key.terminalId })
    })
  }

  async closeScope(scope: string): Promise<void> {
    const rows = await this.#list()
    for (const row of rows) {
      const key = decodeName(row.name)
      // EXACT scope. `decodeName` is reversible, so "w:a" can never match
      // "w:ab" the way a prefix test on the socket name would.
      if (!key || key.scope !== scope) continue
      await this.close(key)
    }
  }

  async shutdownViewers(): Promise<void> {
    const targets = [...this.#targets.values()]
    this.#targets.clear()
    await Promise.all(targets.flatMap(target =>
      [...target.viewers.values()].map(viewer => viewer.detach())))
  }

  // ---- internals viewers call back into -----------------------------------

  /** A `lease` landed on `winner`: whoever we believed owned this target and
   * is not the winner has just lost it, and the daemon will not say so. */
  async noteLease(target: TargetState, winner: ZmxViewer): Promise<void> {
    const previous = target.owner
    target.owner = winner
    if (previous && previous !== winner) await previous.noteOwner(false)
    await winner.noteOwner(true)
  }

  noteDetached(target: TargetState, viewer: ZmxViewer): void {
    if (target.viewers.get(viewer.id) === viewer) target.viewers.delete(viewer.id)
    if (target.owner === viewer) target.owner = null
    this.#forget(target)
  }

  /** The target's process ended: it is GONE, and nothing attaches to it again. */
  noteExit(target: TargetState): void {
    target.closing = true
    this.#forget(target)
  }

  async #list(): Promise<ZmxListRow[]> {
    const dir = this.#dir()
    const rows = await this.#control(helper => helper.send<unknown>({ op: "list", dir }))
    if (!Array.isArray(rows)) {
      throw new WorkspaceTerminalError("protocol", "zmx helper answered list with a non-array")
    }
    return rows.filter((row): row is ZmxListRow =>
      typeof row === "object" && row !== null && typeof (row as ZmxListRow).name === "string")
  }

  #checkCwd(cwd: string): string {
    if (!cwd || !isAbsolute(cwd)) {
      throw new WorkspaceTerminalError("backend-unavailable", `workspace directory must be absolute, got ${JSON.stringify(cwd)}`)
    }
    if (!this.#probe.isDirectory(cwd)) {
      throw new WorkspaceTerminalError("backend-unavailable", `workspace directory ${cwd} does not exist`)
    }
    return cwd
  }

  /**
   * The shell as an ARGV, never a command string: the helper execve()s this
   * vector, and a shell path with a space in it must not become two words.
   *
   * A login shell where the shell has one, because that is what a workspace
   * terminal is today — tmux runs `default-shell` with an empty
   * `default-command`, which is tmux's login-shell path.
   */
  #shellArgv(shell: string): string[] {
    if (!shell) throw new WorkspaceTerminalError("backend-unavailable", "no shell configured for this workspace")
    const path = shell.includes("/") ? shell : this.#probe.which(shell)
    if (!path) {
      throw new WorkspaceTerminalError("backend-unavailable", `shell ${shell} was not found on PATH`)
    }
    if (!this.#probe.isExecutableFile(path)) {
      throw new WorkspaceTerminalError("backend-unavailable", `shell ${path} is not an executable file`)
    }
    return LOGIN_CAPABLE_SHELLS.has(path.slice(path.lastIndexOf("/") + 1)) ? [path, "-l"] : [path]
  }
}

/** Shells whose `-l` means "login shell". Anything else is run bare rather
 * than handed a flag it may read as a filename. */
const LOGIN_CAPABLE_SHELLS = new Set(["sh", "bash", "dash", "zsh", "ksh", "mksh", "fish", "ash"])

function clampDimension(value: number, fallback: number): number {
  return Number.isInteger(value) && value > 0 && value <= 0xffff ? value : fallback
}

/**
 * One viewer: one helper process, one client's view of a target.
 *
 * Event order is produced here and is the contract's, not the wire's:
 * `reset` is synthesised in front of the daemon's `replay-start` (carrying the
 * daemon's own epoch), and `owner` is decided by the backend above.
 */
class ZmxViewer implements WorkspaceTerminalViewer {
  readonly handlers: HelperHandlers
  #helper?: ZmxHelperFacade
  #dead = false
  /** True once we are deliberately dropping this viewer: the helper EOF that
   * follows is then OUR doing and never a `failure` a client should see. */
  #discarding = false
  #epoch: string | null = null
  #insideReplay = false
  /** No reply reaches the pty until the first replay boundary has closed —
   * see `reply()`. */
  #replayClosed = false
  #owner = false
  #cols = ATTACH_COLS
  #rows = ATTACH_ROWS
  /** Events are emitted strictly in order even when `emit` is async. */
  #tail: Promise<void> = Promise.resolve()

  constructor(
    private readonly backend: ZmxWorkspaceBackend,
    private readonly target: TargetState,
    readonly id: string,
    private readonly viewerId: string,
    private readonly emit: (event: WorkspaceTerminalEvent) => Promise<void>,
  ) {
    this.handlers = {
      onOutput: bytes => this.#onOutput(bytes),
      onEvent: event => this.#onEvent(event),
      onFailure: error => { void this.#onFailure(error) },
    }
  }

  bind(helper: ZmxHelperFacade): void {
    this.#helper = helper
    if (this.#dead) helper.kill()
  }

  // ---- WorkspaceTerminalViewer -------------------------------------------

  write(bytes: Uint8Array): boolean {
    if (this.#dead || !this.#helper) return false
    return this.#helper.write(bytes)
  }

  reply(bytes: Uint8Array): boolean {
    if (this.#dead || !this.#helper) return false
    // Owner-only, and SAID SO. The daemon drops a non-owner's reply; reporting
    // true here would tell the caller its answer landed when it did not, which
    // is the same lie the lease-loss gap used to tell a superseded viewer.
    if (!this.#owner) {
      log.debug("zmx_reply_dropped_unowned", { viewer: this.viewerId, bytes: bytes.length })
      return false
    }
    // A replay carries the program's own bytes back to a re-attaching viewer,
    // and a query that was already answered once is in there. Whatever this
    // viewer's emulator produces while it is still drawing the replay is an
    // ANSWER TO HISTORY, which the shell would read as typed input.
    if (!this.#replayClosed) {
      log.debug("zmx_reply_dropped_in_replay", { viewer: this.viewerId, bytes: bytes.length })
      return false
    }
    return this.#helper.reply(bytes)
  }

  async resize(cols: number, rows: number): Promise<void> {
    this.#cols = clampDimension(cols, this.#cols)
    this.#rows = clampDimension(rows, this.#rows)
    // A background viewer may keep reporting layout; only the owner's geometry
    // reaches the pty. The daemon enforces this too (owner + live generation);
    // not sending is how a superseded viewer stops pretending it will land.
    if (!this.#owner || this.#dead || !this.#helper) return
    await this.#send({ op: "resize", cols: this.#cols, rows: this.#rows })
  }

  async focus(active: boolean, cols: number, rows: number): Promise<void> {
    this.#cols = clampDimension(cols, this.#cols)
    this.#rows = clampDimension(rows, this.#rows)
    if (this.#dead || !this.#helper) return
    // A claim ALWAYS goes to the daemon, even from the viewer that already
    // holds the lease: a fresh generation is how a viewer says "everything I
    // sent before now is void".
    await this.#send({ op: "focus", active, cols: this.#cols, rows: this.#rows })
  }

  async detach(): Promise<void> {
    if (this.#dead) return
    this.#dead = true
    this.#discarding = true
    this.backend.noteDetached(this.target, this)
    const helper = this.#helper
    this.#helper = undefined
    try { await helper?.close() } catch { helper?.kill() }
  }

  /** Drop this viewer NOW, with no detach handshake and no client event: used
   * when the target is being closed or the attach it belongs to was refused. */
  discard(): void {
    if (this.#dead) return
    this.#dead = true
    this.#discarding = true
    this.backend.noteDetached(this.target, this)
    const helper = this.#helper
    this.#helper = undefined
    try { helper?.kill() } catch {}
  }

  /** Backend-decided ownership. Emitted only on a CHANGE, so a client does not
   * re-render on every lease renewal of a viewer that already owned the size. */
  async noteOwner(enabled: boolean): Promise<void> {
    if (this.#owner === enabled) return
    this.#owner = enabled
    await this.#deliver({ type: "owner", enabled })
  }

  // ---- helper events ------------------------------------------------------

  async #onOutput(bytes: Uint8Array): Promise<void> {
    if (this.#dead) return
    if (this.#epoch === null) {
      // Bytes before any restore boundary. The contract forbids `output`
      // before a `reset`, so open an EMPTY epoch and say so: there is no
      // replay to draw, and what follows is live. The daemon opens a boundary
      // on every broker attachment, so this is a belt on a brace.
      const epoch = `pre-${this.id}`
      this.#epoch = epoch
      await this.#deliver({ type: "reset", epoch })
      await this.#deliver({ type: "replay-start", epoch })
      await this.#deliver({ type: "replay-end", epoch })
      this.#replayClosed = true
    }
    await this.#deliver({ type: "output", bytes })
  }

  async #onEvent(event: HelperEvent): Promise<void> {
    if (this.#dead) return
    switch (event.ev) {
      case "replay-start": {
        // The daemon chose the epoch; the `reset` in front of it carries the
        // same one, so a client can tell a re-sync from a first draw.
        this.#epoch = event.epoch
        this.#insideReplay = true
        this.#replayClosed = false
        await this.#deliver({ type: "reset", epoch: event.epoch })
        await this.#deliver({ type: "replay-start", epoch: event.epoch })
        break
      }
      case "replay-end": {
        if (!this.#insideReplay) break
        this.#insideReplay = false
        this.#replayClosed = true
        await this.#deliver({ type: "replay-end", epoch: event.epoch })
        break
      }
      case "lease":
        await this.backend.noteLease(this.target, this)
        break
      case "blur":
        if (this.target.owner === this) this.target.owner = null
        await this.noteOwner(false)
        break
      case "exit": {
        // The TARGET's process ended. Every viewer of it hears this from its
        // own daemon connection, so each reports its own.
        this.backend.noteExit(this.target)
        await this.#deliver({ type: "exit", known: event.known, code: event.code, signal: event.signal })
        this.#dead = true
        this.#discarding = true
        const helper = this.#helper
        this.#helper = undefined
        try { helper?.kill() } catch {}
        break
      }
      default:
        break
    }
  }

  async #onFailure(error: WorkspaceTerminalError): Promise<void> {
    // A helper that dies while we are deliberately dropping it is not a
    // failure; neither is one that dies after the target's exit was reported.
    if (this.#discarding || this.#dead) return
    this.#dead = true
    this.backend.noteDetached(this.target, this)
    const helper = this.#helper
    this.#helper = undefined
    try { helper?.kill() } catch {}
    await this.#deliver(error.toEvent())
  }

  async #send(command: HelperCommandBody): Promise<void> {
    try {
      await this.#helper?.send(command)
    } catch (error) {
      // A command the helper could not answer is a viewer-level problem; it
      // says nothing about the target, and it is reported the same way any
      // other lost helper is.
      await this.#onFailure(asWorkspaceError(error, "zmx helper refused a command"))
    }
  }

  /** Serialise emission: the helper hands us output and control frames from
   * one stream, and a slow `emit` must not let a later event overtake it. */
  #deliver(event: WorkspaceTerminalEvent): Promise<void> {
    const next = this.#tail.catch(() => undefined).then(() => this.emit(event))
    this.#tail = next.catch(() => undefined)
    return next
  }
}
