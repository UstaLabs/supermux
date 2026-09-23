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
// WHY THE SOCKET IS NOT NAMED AFTER THE TARGET
// --------------------------------------------
// `sockaddr_un.sun_path` is 108 bytes on Linux and 104 on macOS — 103 usable
// characters at the portable worst case. A realistic workspace key
// ("w:" + a 36-char UUID, terminal "main") already encodes to a 93-character
// name, and `/run/user/1000/supermux/zmx/<name>.sock` is 126 characters. There
// is no directory short enough to make a per-target socket fit, and truncating
// the name would collide two workspaces onto one shell — the one outcome we
// refuse. So the encoded name is the TARGET name carried in the protocol, and
// the socket is a single, fixed, short server socket inside our private
// directory. `targetSocketPath` exists for the alternative per-target layout
// and validates the same budget, so if Task 3/4 ever wants one the limit is
// enforced BEFORE creation with a typed error instead of at bind() time.
import { chmodSync, lstatSync, mkdirSync } from "fs"
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

/** Fixed basename of the one server socket every target is reached through. */
export const SERVER_SOCKET_BASENAME = "zmx.sock"

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

/** The one server socket. Validated against sun_path so a long MUX_HOME fails
 * with our error instead of an opaque EINVAL from bind(). */
export function serverSocketPath(dir: string): string {
  return checkedSocketPath(join(dir, SERVER_SOCKET_BASENAME))
}

/** Per-target socket layout (see the header note on why this is not the default). */
export function targetSocketPath(dir: string, key: WorkspaceTerminalKey): string {
  return checkedSocketPath(join(dir, `${assertNameFits(key)}.sock`))
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

/** True only for a name that decodes to EXACTLY this scope. Deliberately not a
 * prefix test — "w:a" must never sweep up "w:ab". */
export function belongsToScope(name: string, scope: string): boolean {
  return decodeName(name)?.scope === scope
}

/** Our targets among whatever zmx reports, oldest-order preserved. Names that
 * are not ours (or are corrupt) are dropped, never guessed at. */
export function keysFromNames(names: readonly string[]): WorkspaceTerminalKey[] {
  const keys: WorkspaceTerminalKey[] = []
  for (const name of names) {
    const key = decodeName(name)
    if (key) keys.push(key)
  }
  return keys
}

/** The subset of `names` that belongs to exactly `scope` — what closeScope kills. */
export function namesInScope(names: readonly string[], scope: string): string[] {
  return names.filter(name => belongsToScope(name, scope))
}
