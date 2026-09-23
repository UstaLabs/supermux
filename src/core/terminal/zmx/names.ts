// Names and socket location for the zmx workspace backend.
//
// A target name has to be REVERSIBLE: `list(scope)` recovers the scope and the
// terminal id from the names zmx reports, and `closeScope` must kill exactly
// the targets of one scope. Scope strings are arbitrary ("w:<uuid>" for a
// workspace, a free-form session title otherwise) and terminal ids are
// caller-supplied, so both halves are hex-encoded: the alphabet is then
// [0-9a-f_] with `_` as an unambiguous separator, which survives shells, zmx
// and the filesystem, and — unlike an escaping scheme — cannot let scope "a"
// with id "b_c" collide with scope "a_b" with id "c".
//
// Decoding is strict in both directions: hex → UTF-8 → hex must come back
// byte-identical, so a non-canonical or invalid-UTF-8 name is rejected rather
// than silently mapped onto some other scope.
//
// SOCKET NAMING: ZMX IS ONE DAEMON, ONE SOCKET, ONE SESSION
// ---------------------------------------------------------
// Verified against the pinned upstream (see vendor/zmx/README.md §1):
// `socket.zig:createSocket` binds `<socket_dir>/<session_name>` per session,
// one double-forked daemon each, and `zmx list` enumerates that directory and
// probes every socket. There is NO upstream primitive where one socket serves
// many named targets, and adding one would mean re-tagging every message and
// giving up zmx's per-session crash isolation.
//
// `sockaddr_un.sun_path` is 108 bytes on Linux and 104 on macOS — 103 usable
// at the portable worst case. A realistic workspace key ("w:" + a 36-char UUID,
// terminal "main") encodes to a 93-character name, and
// `/run/user/1000/supermux/zmx/<name>` is 121 characters. It does not fit, and
// truncating would collide two workspaces onto one shell — the one outcome we
// refuse.
//
// So the two names are SPLIT:
//
//   * `socketBasename(key)` — short and OPAQUE (22 chars), what zmx sees as
//     the session name and what the socket file is called. Derived (a hash of
//     the key) rather than allocated, so `ensure` is idempotent across broker
//     restarts with nothing stored anywhere.
//   * `encodeName(key)` — the full reversible name, carried in the PROTOCOL
//     and stored by the patched daemon as the label `mux.target`. `zmx list`
//     already reads labels off every socket it probes, so `list` recovers the
//     scope and terminal id from the RUNNING DAEMONS — not from any file the
//     broker has to keep in sync with them, and not from the socket name.
//     That is what makes discovery survive a broker crash.
//
// A 80-bit hash can in principle collide. It is DETECTED, never merged: the
// label carries the full key, so an attach compares what came back against
// `encodeName(key)` and fails rather than handing two workspaces one shell.
// `assertTargetMatches` is that comparison.
import { createHash } from "crypto"
import { chmodSync, lstatSync, mkdirSync, readFileSync, writeFileSync } from "fs"
import { isAbsolute, join } from "path"
import { STATE_DIR } from "../../../shared/paths"
import { WorkspaceTerminalError, type WorkspaceTerminalKey } from "../workspace-backend"

const PREFIX = "muxterm_"

export function encodeName(key: WorkspaceTerminalKey): string {
  const hex = (s: string) => Buffer.from(s, "utf8").toString("hex")
  return `${PREFIX}${hex(key.scope)}_${hex(key.terminalId)}`
}

export function decodeName(name: string): WorkspaceTerminalKey | null {
  const match = /^muxterm_([0-9a-f]+)_([0-9a-f]+)$/.exec(name)
  if (!match) return null
  const decode = (s: string): string | null => {
    if (s.length % 2) return null
    const text = Buffer.from(s, "hex").toString("utf8")
    return Buffer.from(text, "utf8").toString("hex") === s ? text : null
  }
  const scope = decode(match[1]!)
  const terminalId = decode(match[2]!)
  return scope && terminalId ? { scope, terminalId } : null
}

/** Usable `sockaddr_un.sun_path` characters: min(Linux 108, macOS 104) − NUL. */
export const SOCKET_PATH_MAX = 103

/** A name is a single path component and a protocol field; NAME_MAX (Linux and
 * macOS alike) is the binding limit. A UUID scope with a 64-char terminal id
 * encodes to 213, so this only bites pathological scopes. */
export const TARGET_NAME_MAX = 255

/** Prefix of every socket basename we mint. Lets `list` skip sockets that are
 * not ours without probing them. */
export const SOCKET_BASENAME_PREFIX = "mx"

/** Hex characters of the key digest in a socket basename. 80 bits: at the
 * thousands-of-terminals scale this machine will ever see, a collision is
 * vanishingly unlikely — and is caught rather than silently merged. */
const SOCKET_DIGEST_HEX = 20

/** Length of a minted basename: "mx" + 20 hex = 22. */
export const SOCKET_BASENAME_LEN = SOCKET_BASENAME_PREFIX.length + SOCKET_DIGEST_HEX

/**
 * The zmx session name for a key: short, opaque, and DERIVED rather than
 * allocated, so two brokers (or a broker before and after a restart) compute
 * the same one without sharing state.
 *
 * `scope` and `terminalId` are joined with a NUL, which cannot occur in
 * either, so scope "a"/id "b" and scope "a\0b"/id "" cannot hash alike.
 */
export function socketBasename(key: WorkspaceTerminalKey): string {
  const digest = createHash("sha256")
    .update(key.scope, "utf8")
    .update("\0")
    .update(key.terminalId, "utf8")
    .digest("hex")
  return `${SOCKET_BASENAME_PREFIX}${digest.slice(0, SOCKET_DIGEST_HEX)}`
}

/** True for a basename we minted. Not proof it is ours — only the `mux.target`
 * label is that — but enough to skip a neighbour's session without probing. */
export function isOurSocketBasename(basename: string): boolean {
  return new RegExp(`^${SOCKET_BASENAME_PREFIX}[0-9a-f]{${SOCKET_DIGEST_HEX}}$`).test(basename)
}

/**
 * The encoded name, or a typed error. Call this BEFORE creating anything: a
 * name is never truncated to fit, because two truncated names are one shell
 * shared by two workspaces.
 */
export function assertNameFits(key: WorkspaceTerminalKey): string {
  const name = encodeName(key)
  if (name.length > TARGET_NAME_MAX) {
    throw new WorkspaceTerminalError(
      "name-too-long",
      `terminal name is ${name.length} characters, limit ${TARGET_NAME_MAX} ` +
      `(scope ${key.scope.length} chars, id ${key.terminalId.length} chars)`,
    )
  }
  return name
}

/**
 * Where our private zmx sockets live.
 *
 *  1. `MUX_TERM_ZMX_DIR` — explicit override, matching MUX_TERM_TMUX_SOCKET /
 *     MUX_SOCKETS_DIR; tests and odd deployments need one.
 *  2. `$XDG_RUNTIME_DIR/supermux/zmx` — per-user, 0700 by construction, on
 *     tmpfs (where sockets belong), and SHORT: `/run/user/1000` is 14
 *     characters, which is what keeps us inside the sun_path budget. It is
 *     wiped when the user's last login session ends, which is also when the
 *     shells behind these sockets are killed, so nothing outlives its socket.
 *  3. `<STATE_DIR>/zmx` — macOS, containers and cron-less environments have no
 *     XDG_RUNTIME_DIR. Longer, but still far inside budget for one server
 *     socket, and it inherits STATE_DIR's ownership.
 *
 * Never a shared `/tmp`: a world-writable parent invites a squatted or
 * symlinked socket, and this socket fronts a shell.
 */
export function zmxSocketDir(
  env: NodeJS.ProcessEnv = process.env,
  stateDir: string = STATE_DIR,
): string {
  const override = env.MUX_TERM_ZMX_DIR?.trim()
  if (override) return override
  const runtime = env.XDG_RUNTIME_DIR?.trim()
  if (runtime && isAbsolute(runtime)) return join(runtime, "supermux", "zmx")
  return join(stateDir, "zmx")
}

/**
 * Create the directory 0700 if needed and REFUSE to use it unless it is a real
 * directory we own with no group/world bits. mkdir's mode is masked by umask
 * and ignored for an existing directory, so the chmod is not redundant.
 */
export function ensureSocketDir(dir: string): string {
  try {
    mkdirSync(dir, { recursive: true, mode: 0o700 })
    chmodSync(dir, 0o700)
  } catch (error) {
    throw new WorkspaceTerminalError(
      "socket-dir-unsafe",
      `cannot prepare zmx socket dir ${dir}: ${error instanceof Error ? error.message : String(error)}`,
    )
  }
  // lstat, not stat: a symlink here could be repointed at someone else's dir.
  const stat = lstatSync(dir)
  const uid = process.getuid?.()
  if (!stat.isDirectory()) {
    throw new WorkspaceTerminalError("socket-dir-unsafe", `zmx socket dir ${dir} is not a directory`)
  }
  if (uid !== undefined && stat.uid !== uid) {
    throw new WorkspaceTerminalError("socket-dir-unsafe", `zmx socket dir ${dir} is owned by uid ${stat.uid}, not ${uid}`)
  }
  if ((stat.mode & 0o077) !== 0) {
    throw new WorkspaceTerminalError(
      "socket-dir-unsafe",
      `zmx socket dir ${dir} is group/world accessible (mode ${(stat.mode & 0o777).toString(8)})`,
    )
  }
  return dir
}

/** Owner marker inside the socket directory. A plain file, not a socket: `zmx
 * list` enumerates this directory and connects to what it finds, and a regular
 * file is skipped rather than probed. */
export const SOCKET_DIR_OWNER_FILE = ".broker-owner"

function isProcessAlive(pid: number): boolean {
  if (pid <= 0) return false
  try { process.kill(pid, 0); return true } catch { return false }
}

/**
 * True when `pid` looks like a supermux broker rather than whatever inherited
 * a recycled pid. Best effort, and deliberately biased: when we cannot tell
 * (no procfs — macOS), we say NO and take the directory over. Locking a user
 * out of their terminals because a pid was reused is a worse failure than the
 * one this check exists to catch, which is already the rare one.
 */
function looksLikeBroker(pid: number): boolean {
  try {
    const cmdline = readFileSync(`/proc/${pid}/cmdline`, "utf8")
    return cmdline.includes("bun") || cmdline.includes("mux")
  } catch {
    return false
  }
}

/**
 * Claim this socket directory for THIS broker process, and refuse it if
 * another live one holds it.
 *
 * WHY THIS EXISTS. `ZmxWorkspaceBackend` derives `owner:false` — a viewer
 * losing the size lease — from a `BrokerLease` landing on one of ITS OWN
 * viewers, because the daemon only messages the winner. That deduction is
 * sound only while every broker viewer of a target belongs to one process. The
 * private 0700 directory makes a stranger's broker unlikely; it does not make
 * a SECOND OF OURS impossible, and an old broker still draining during a
 * restart is exactly that. Two of them would each see leases move for reasons
 * they cannot observe, and a viewer would keep believing it owned the size.
 *
 * So it is enforced, not assumed. The marker is a pid file rather than a lock
 * the kernel holds, because it also has to be readable: "who has this
 * directory" is the first question when a broker refuses to start.
 */
export function claimSocketDir(dir: string): string {
  const path = join(dir, SOCKET_DIR_OWNER_FILE)
  let owner: number | undefined
  try {
    owner = Number(readFileSync(path, "utf8").trim())
  } catch {
    owner = undefined // no marker, or unreadable: ours to take
  }
  if (owner !== undefined && Number.isInteger(owner) && owner > 0
    && owner !== process.pid && isProcessAlive(owner) && looksLikeBroker(owner)) {
    throw new WorkspaceTerminalError(
      "socket-dir-unsafe",
      `zmx socket dir ${dir} belongs to another broker (pid ${owner}); ` +
      "two brokers on one socket directory cannot each tell who owns a terminal's size",
    )
  }
  try {
    writeFileSync(path, `${process.pid}\n`, { mode: 0o600 })
  } catch (error) {
    throw new WorkspaceTerminalError(
      "socket-dir-unsafe",
      `cannot claim zmx socket dir ${dir}: ${error instanceof Error ? error.message : String(error)}`,
    )
  }
  return dir
}

/**
 * Where this target's daemon listens. Validated against sun_path so a long
 * MUX_HOME fails with OUR typed error instead of an opaque EINVAL from bind(),
 * and BEFORE anything is created.
 */
export function targetSocketPath(dir: string, key: WorkspaceTerminalKey): string {
  // assertNameFits first: the label has to be storable before the socket is
  // worth creating, or we would have a shell nothing can ever find again.
  assertNameFits(key)
  return checkedSocketPath(join(dir, socketBasename(key)))
}

function checkedSocketPath(path: string): string {
  const bytes = Buffer.byteLength(path, "utf8")
  if (bytes > SOCKET_PATH_MAX) {
    throw new WorkspaceTerminalError(
      "name-too-long",
      `unix socket path is ${bytes} bytes, limit ${SOCKET_PATH_MAX}: ${path}`,
    )
  }
  return path
}

/** True only for a `mux.target` label value that decodes to EXACTLY this
 * scope. Deliberately not a prefix test — "w:a" must never sweep up "w:ab". */
export function belongsToScope(name: string, scope: string): boolean {
  return decodeName(name)?.scope === scope
}

/** Our targets among the `mux.target` labels `zmx list` reported, oldest-order
 * preserved. Values that are not ours (or are corrupt) are dropped, never
 * guessed at. This — not the socket name — is how `list` survives a broker
 * restart: the labels live in the running daemons. */
export function keysFromNames(names: readonly string[]): WorkspaceTerminalKey[] {
  const keys: WorkspaceTerminalKey[] = []
  for (const name of names) {
    const key = decodeName(name)
    if (key) keys.push(key)
  }
  return keys
}

/** The subset of label values belonging to exactly `scope` — what closeScope
 * kills. The caller maps each back to its socket via `socketBasename`. */
export function namesInScope(names: readonly string[], scope: string): string[] {
  return names.filter(name => belongsToScope(name, scope))
}

/**
 * Confirm the daemon behind an opaque socket is the target we asked for.
 *
 * The socket name is a hash, so it cannot prove identity on its own; the
 * `mux.target` label can, and this is where the two are reconciled. A mismatch
 * (a hash collision, or somebody else's session under a name shaped like ours)
 * is an error, NEVER an attach: sharing one shell between two workspaces is
 * the failure this whole naming scheme exists to prevent.
 */
export function assertTargetMatches(key: WorkspaceTerminalKey, label: string | undefined): void {
  const expected = encodeName(key)
  if (label === expected) return
  throw new WorkspaceTerminalError(
    "protocol",
    label === undefined
      ? `zmx session ${socketBasename(key)} carries no ${TARGET_LABEL_KEY} label`
      : `zmx session ${socketBasename(key)} is ${label}, not ${expected}`,
  )
}

/** The label key the patched daemon stores the encoded name under. Must match
 * `BROKER_TARGET_LABEL` in vendor/zmx/patches/0001-supermux-session-contract.patch. */
export const TARGET_LABEL_KEY = "mux.target"
