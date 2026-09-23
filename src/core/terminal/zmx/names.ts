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
import { chmodSync, lstatSync, mkdirSync, readFileSync, readlinkSync, rmdirSync, unlinkSync, writeFileSync } from "fs"
import { basename, isAbsolute, join, resolve } from "path"
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

/**
 * Is anything running under this pid? Signal 0 asks the kernel without
 * delivering anything. Also the broker's answer for a daemon it is waiting to
 * see die (`backend.ts`, `close`).
 *
 * `EPERM` MEANS ALIVE, NOT DEAD. The kernel raises it for a process that
 * exists but belongs to another uid — the one answer that is positive proof of
 * life — and reading it as `ESRCH` would take over a socket directory whose
 * owner is very much still running, which is the two-broker state the marker
 * exists to prevent. Only `ESRCH` (and a pid that cannot be one) is death.
 */
export function isProcessAlive(pid: number): boolean {
  if (pid <= 0) return false
  try {
    process.kill(pid, 0)
    return true
  } catch (error) {
    return (error as NodeJS.ErrnoException)?.code === "EPERM"
  }
}

/**
 * What a pid is RUNNING: the executable as an absolute path where procfs can
 * say so, the argument vector the kernel recorded, and the working directory
 * those arguments are relative to.
 *
 * Exported for the tests that feed it realistic command lines; nothing else
 * should need it.
 */
export interface ProcessIdentity {
  /** `/proc/<pid>/exe`, or argv[0] when the kernel will not say. */
  readonly executable: string | null
  /** Every argument, argv[0] included. Empty when the blob was unreadable. */
  readonly argv: readonly string[]
  /** `/proc/<pid>/cwd`, so a relative argument can be resolved. */
  readonly cwd: string | null
}

export type ProcessProbe = (pid: number) => ProcessIdentity | null

/** `/proc/<pid>/exe` of a binary replaced since it was exec'd (an upgrade,
 * a `bun upgrade`) reads back with this glued on. */
const DELETED_SUFFIX = " (deleted)"

/**
 * argv[0] out of a `/proc/<pid>/cmdline` blob: the bytes up to the FIRST NUL.
 *
 * The blob is every argument concatenated with NULs, which is exactly why
 * matching a substring against the WHOLE of it was wrong: `bundle install`,
 * `tmux attach` and any script living under a path with "mux" or "bun" in it
 * all contain the needle somewhere, and each one of those made this broker
 * refuse to start.
 */
export function executableFromCmdline(blob: string): string | null {
  const argv0 = argvFromCmdline(blob)[0]
  return argv0 && argv0.length > 0 ? argv0 : null
}

/** Every argument out of a `/proc/<pid>/cmdline` blob. The kernel terminates
 * the last one too, so the trailing empty field is dropped rather than carried
 * around as an argument nothing passed. */
export function argvFromCmdline(blob: string): string[] {
  const argv = blob.split("\0")
  if (argv.length > 0 && argv[argv.length - 1] === "") argv.pop()
  return argv
}

/** procfs answer for `ProcessProbe`. Linux only: everywhere else this returns
 * nothing and the identity check below is a no-op by design. */
function procIdentity(pid: number): ProcessIdentity | null {
  let executable: string | null = null
  try {
    const exe = readlinkSync(`/proc/${pid}/exe`)
    if (exe.length > 0) executable = exe.endsWith(DELETED_SUFFIX) ? exe.slice(0, -DELETED_SUFFIX.length) : exe
  } catch {
    // Unreadable (another uid, or a kernel that hides it): argv[0] will do.
  }
  let argv: string[] = []
  try {
    argv = argvFromCmdline(readFileSync(`/proc/${pid}/cmdline`, "utf8"))
  } catch {
    // Same: a pid we cannot read is a pid we cannot identify.
  }
  if (executable === null) executable = argv[0] ?? null
  if (executable === null && argv.length === 0) return null
  let cwd: string | null = null
  try {
    cwd = readlinkSync(`/proc/${pid}/cwd`)
  } catch {
    // Only needed to resolve a relative entry; absent, we compare basenames.
  }
  return { executable, argv, cwd }
}

/**
 * This process's own entry module — the script `bun` was pointed at — as an
 * absolute path, or null when there is no such thing.
 *
 * Null is the COMPILED case and it is not a failure: a single-file build runs
 * as `supermux-broker`, `process.argv[1]` is absent or the executable itself,
 * and the executable name alone is already an identity no bystander shares.
 * The entry only matters while we run under a generic interpreter.
 */
export function brokerEntry(): string | null {
  const entry = process.argv[1]
  if (!entry || entry === process.execPath) return null
  return resolve(entry)
}

/**
 * True when `pid` is a supermux broker rather than whatever inherited a
 * recycled pid.
 *
 * TWO FACTS, AND NEITHER ALONE IS AN IDENTITY.
 *
 * The first is the executable: `/proc/<pid>/exe` (argv[0] only when the kernel
 * will not say), compared to ours by basename, never as a substring of the
 * command line. That much was already here, and it is what stopped
 * `bundle install`, `bunyan`, `tmux attach` and every path containing "bun" or
 * "mux" from holding the directory hostage.
 *
 * But under an interpreter that fact says only "is this bun". Every unrelated
 * bun program this user runs — a dev server, a formatter, a scratch script, a
 * `bunx` one-liner — answers YES, so a recycled pid landing on any of them
 * makes the broker refuse to start and the user loses every terminal. That is
 * the same lockout, reached through a different door.
 *
 * The second fact closes it, and the marker is where it comes from. We write
 * that file, so a claiming broker records its OWN entry module beside its pid;
 * `claimedEntry` is what a later broker reads back. A pid is ours only if it
 * is still running that exact entry. A recycled pid running some other bun
 * program is not, and is taken over. A genuinely live second broker — from
 * this checkout or another one, which matters because `XDG_RUNTIME_DIR` makes
 * every checkout on a machine share one socket directory — recorded its real
 * entry and still matches, and is refused.
 *
 * Arguments are compared as the kernel recorded them, resolved against the
 * pid's own `cwd` (a unit file may well have exec'd `bun src/main.ts`), with a
 * basename comparison as the fallback when the cwd is unreadable.
 *
 * DELIBERATELY BIASED, STILL. A marker with no recorded entry (one written by
 * an older broker) falls back to the executable alone, because refusing to
 * start over a file we cannot fully read is worse than the second broker it
 * might miss. And where nothing can be known at all — no procfs, so macOS and
 * Windows — we say NO and take the directory over, which makes this whole
 * enforcement a no-op off Linux. See vendor/zmx/README.md §5.
 */
export function looksLikeBroker(
  pid: number,
  claimedEntry?: string | null,
  probe: ProcessProbe = procIdentity,
): boolean {
  const identity = probe(pid)
  if (!identity?.executable) return false
  if (basename(identity.executable) !== basename(process.execPath)) return false
  // No entry to match: either the marker predates them, or we are a compiled
  // binary whose own name is the identity. The executable stands alone.
  if (!claimedEntry || brokerEntry() === null) return true
  return identity.argv.some(argument => isTheSameEntry(argument, claimedEntry, identity.cwd))
}

/** One recorded argument against the entry a marker claims. */
function isTheSameEntry(argument: string, entry: string, cwd: string | null): boolean {
  if (argument.startsWith("-")) return false // an option, never a script
  if (argument === entry) return true
  if (cwd !== null) return resolve(cwd, argument) === entry
  // No cwd to resolve against: the best that remains is the file's own name,
  // which still rules out every bun program that is not running OUR script.
  return basename(argument) === basename(entry)
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
 *
 * READ, DECIDE, WRITE IS THE WHOLE OPERATION, AND ALL THREE MUST BE ONE STEP.
 * An exclusive create (`O_CREAT | O_EXCL`) settles the EMPTY directory on its
 * own — the kernel lets exactly one create win. It settles nothing once a
 * marker exists, which is the case this actually has to survive: a broker that
 * died without cleaning up leaves a STALE marker, and takeover is read the pid
 * → judge it dead → write ours. Two brokers restarting together both read the
 * same dead pid, both judge it dead, and both write. The second write is not
 * even a corruption — the file ends up naming one of them — so both return
 * happily and we are in the two-broker state this exists to prevent.
 *
 * Nothing in JavaScript's run-to-completion helps: the read is I/O, and the
 * peer is another PROCESS regardless. Nor does writing the takeover atomically
 * (a temp file plus `rename`): `rename` is atomic in that no reader sees a
 * half-written marker, but it is not a compare-and-swap, so two renames still
 * both "succeed" and re-reading afterwards can hand BOTH of them their own pid
 * if the second rename lands after the first has already verified.
 *
 * POSIX has no compare-and-swap on file contents, so the read-decide-write is
 * run under a MUTEX instead, and `mkdir` is it: the one call that is both
 * atomic and exclusive everywhere we run, with a failure mode (`EEXIST`) that
 * names the contention rather than hiding it. The critical section is a
 * handful of synchronous syscalls, so the lock is held for microseconds and
 * uncontended in every normal start.
 *
 * A HELD LOCK IS NEVER PERMANENT. A broker killed inside the critical section
 * would otherwise leave a directory no broker can claim again — the lockout
 * this file's own checks call the worse failure. So a lock older than
 * [CLAIM_LOCK_STALE_MS] is broken and retaken: no real holder survives a
 * thousandth of that, and the `mkdir` that follows is still exclusive, so
 * breaking it cannot produce two winners on its own.
 */
export function claimSocketDir(dir: string): string {
  const path = join(dir, SOCKET_DIR_OWNER_FILE)
  // The pid, and the entry module a later broker checks that pid is still
  // running. Recording it is what makes "is this pid ours" answerable at all —
  // see `looksLikeBroker`.
  const mine = `${process.pid}\n${brokerEntry() ?? ""}\n`
  return withClaimLock(dir, () => {
    // Inside the lock, so the pid read here cannot change before the decision
    // below acts on it. That is the entire point of the lock.
    const owner = readOwnerMarker(path)
    if (owner !== undefined && owner.pid !== process.pid
      && isProcessAlive(owner.pid) && looksLikeBroker(owner.pid, owner.entry)) {
      throw new WorkspaceTerminalError(
        "socket-dir-unsafe",
        `zmx socket dir ${dir} belongs to another broker (pid ${owner.pid}); ` +
        "two brokers on one socket directory cannot each tell who owns a terminal's size",
      )
    }
    // Free, ours already, or a dead/nonsense owner. Always an exclusive create,
    // never a write over the old file: `mode` is IGNORED for a path that
    // already exists, so overwriting a marker somebody left 0644 would leave it
    // 0644. Unlinking first is safe here and nowhere else — inside the lock no
    // other claimer can be in the window, which is exactly the property the
    // lock buys. The file being removed is the one just judged, by the only
    // process allowed to be judging it.
    try {
      try {
        writeFileSync(path, mine, { flag: "wx", mode: 0o600 })
      } catch (error) {
        if ((error as NodeJS.ErrnoException)?.code !== "EEXIST") throw error
        unlinkSync(path)
        writeFileSync(path, mine, { flag: "wx", mode: 0o600 })
      }
    } catch (error) {
      throw new WorkspaceTerminalError(
        "socket-dir-unsafe",
        `cannot claim zmx socket dir ${dir}: ${error instanceof Error ? error.message : String(error)}`,
      )
    }
    return dir
  })
}

/**
 * What a marker claims: a pid on the first line and, since brokers started
 * recording it, the entry module that pid was running on the second.
 *
 * Nothing when the file is missing, unreadable or does not lead with a pid —
 * none of which is evidence of a live owner. A missing SECOND line is not the
 * same thing: the marker is a broker's, it simply predates the entry, so the
 * pid stands and `looksLikeBroker` falls back to the executable alone.
 */
function readOwnerMarker(path: string): { pid: number, entry: string | null } | undefined {
  let lines: string[]
  try {
    lines = readFileSync(path, "utf8").split("\n")
  } catch {
    return undefined // missing or unreadable: it says nothing about an owner
  }
  const pid = Number(lines[0]?.trim())
  if (!Number.isInteger(pid) || pid <= 0) return undefined
  const entry = lines[1]?.trim()
  return { pid, entry: entry ? entry : null }
}

/** The claim's mutex, a sibling of the marker so it lives and dies with the
 * directory it guards. A directory, not a file: `mkdir` is the exclusive
 * create, and a directory here is also skipped by `zmx list`, which probes the
 * sockets it finds. */
export const SOCKET_DIR_CLAIM_LOCK = ".broker-owner.lock"

/** How long a claim will wait for a peer's critical section. Microseconds is
 * the real figure; this is the bound before we call the directory contended. */
const CLAIM_LOCK_WAIT_MS = 2_000

/** Between attempts. Short, because the wait is normally zero. */
const CLAIM_LOCK_POLL_MS = 2

/** A lock this old was abandoned by a process that died mid-claim. Four orders
 * of magnitude above the real hold time, so breaking it cannot race a live
 * holder in any realistic scheduling. */
const CLAIM_LOCK_STALE_MS = 10_000

function withClaimLock<T>(dir: string, claim: () => T): T {
  const lock = join(dir, SOCKET_DIR_CLAIM_LOCK)
  const deadline = Date.now() + CLAIM_LOCK_WAIT_MS
  for (;;) {
    try {
      mkdirSync(lock, { mode: 0o700 })
      break
    } catch (error) {
      const code = (error as NodeJS.ErrnoException)?.code
      if (code !== "EEXIST") {
        throw new WorkspaceTerminalError(
          "socket-dir-unsafe",
          `cannot lock zmx socket dir ${dir}: ${error instanceof Error ? error.message : String(error)}`,
        )
      }
      breakStaleClaimLock(lock)
      if (Date.now() >= deadline) {
        throw new WorkspaceTerminalError(
          "socket-dir-unsafe",
          `zmx socket dir ${dir} is locked by another claim after ${CLAIM_LOCK_WAIT_MS} ms ` +
          `(remove ${SOCKET_DIR_CLAIM_LOCK} if no broker is starting)`,
        )
      }
      sleepSync(CLAIM_LOCK_POLL_MS)
    }
  }
  try {
    return claim()
  } finally {
    // The lock outliving its critical section is the lockout; release it even
    // when the claim threw, and never let the release mask that throw.
    try { rmdirSync(lock) } catch { /* already gone: someone broke it as stale */ }
  }
}

/** Remove a lock nobody can still be holding. Silent on every error: losing
 * this race to another breaker, or to the holder's own release, is the same
 * outcome — the lock is gone and the `mkdir` above decides who gets it next. */
function breakStaleClaimLock(lock: string): void {
  try {
    if (Date.now() - lstatSync(lock).mtimeMs < CLAIM_LOCK_STALE_MS) return
    rmdirSync(lock)
  } catch { /* gone, or not ours to break */ }
}

/** A synchronous pause, because the claim is synchronous. `Bun.sleepSync`
 * where it exists; a short spin otherwise, which is honest for two-millisecond
 * waits that in practice never happen. */
function sleepSync(ms: number): void {
  const bun = (globalThis as { Bun?: { sleepSync?: (ms: number) => void } }).Bun
  if (typeof bun?.sleepSync === "function") {
    bun.sleepSync(ms)
    return
  }
  const until = Date.now() + ms
  while (Date.now() < until) { /* spin */ }
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
