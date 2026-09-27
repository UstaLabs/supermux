import { describe, expect, test } from "bun:test"
import { mkdtempSync, mkdirSync, readFileSync, rmdirSync, statSync, symlinkSync, writeFileSync, chmodSync, existsSync, utimesSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { randomUUID } from "crypto"
import {
  SOCKET_PATH_MAX,
  TARGET_NAME_MAX,
  assertNameFits,
  belongsToScope,
  claimSocketDir,
  SOCKET_DIR_CLAIM_LOCK,
  SOCKET_DIR_OWNER_FILE,
  decodeName,
  encodeName,
  ensureSocketDir,
  argvFromCmdline,
  executableFromCmdline,
  isProcessAlive,
  looksLikeBroker,
  assertTargetMatches,
  isOurSocketBasename,
  keysFromNames,
  namesInScope,
  SOCKET_BASENAME_LEN,
  socketBasename,
  targetSocketPath,
  zmxSocketDir,
} from "./names"
import { isWorkspaceTerminalError, WorkspaceTerminalError } from "../workspace-backend"
import { workspaceScope } from "../../workspace/scope"

/** The pid line of an owner marker. The rest of the file is the entry module
 * the claiming broker recorded, which `looksLikeBroker` reads back. */
const ownerPidIn = (dir: string): number =>
  Number(readFileSync(join(dir, SOCKET_DIR_OWNER_FILE), "utf8").split("\n")[0]!.trim())

/** `/proc/<pid>/cmdline` is empty until the child has finished exec'ing, and
 * an identity read before then is no identity at all. */
const waitForCmdline = async (pid: number): Promise<void> => {
  for (let attempt = 0; attempt < 200; attempt++) {
    if (readFileSync(`/proc/${pid}/cmdline`, "utf8").length > 0) return
    await Bun.sleep(5)
  }
}

const roundTrip = (scope: string, terminalId: string) =>
  decodeName(encodeName({ scope, terminalId }))

const throwsCode = (fn: () => unknown, code: "name-too-long" | "socket-dir-unsafe" | "protocol") => {
  try {
    fn()
  } catch (error) {
    return isWorkspaceTerminalError(error, code)
  }
  return false
}

/** The thrown error itself, for assertions about `recoverable` — which is the
 * half of a refusal the code alone does not carry. */
const refusalFrom = (fn: () => unknown): WorkspaceTerminalError => {
  try {
    fn()
  } catch (error) {
    if (error instanceof WorkspaceTerminalError) return error
    throw error
  }
  throw new Error("expected a WorkspaceTerminalError, got a return")
}

describe("zmx name encoding", () => {
  test("round-trips the workspace scope convention", () => {
    const id = randomUUID()
    const key = { scope: workspaceScope(id), terminalId: "main" }
    const name = encodeName(key)
    expect(name.startsWith("muxterm_")).toBe(true)
    expect(decodeName(name)).toEqual({ scope: `w:${id}`, terminalId: "main" })
  })

  test("round-trips unicode, punctuation, whitespace and underscores", () => {
    const cases: Array<[string, string]> = [
      ["w:düğün", "türkçe"],
      ["w:日本語", "端末"],
      ["w:🙂🙃", "🚀"],
      ["sess.1:with:colons", "id.with.dots"],
      ["a b\tc", "x y"],
      ["scope_with_underscores", "id_with_underscores"],
      ["w:a/b\\c", "-_~!@#$%^&*()"],
      ["muxterm_deadbeef_cafe", "muxterm_cafe_beef"], // looks like one of ours
    ]
    for (const [scope, terminalId] of cases) {
      expect(roundTrip(scope, terminalId)).toEqual({ scope, terminalId })
    }
  })

  test("the separator cannot be faked — no scope/id boundary ambiguity", () => {
    // Without hex, scope "a" + id "b_c" and scope "a_b" + id "c" would produce
    // the same string. Hex-encoding both halves keeps the `_` unambiguous.
    expect(encodeName({ scope: "a", terminalId: "b_c" }))
      .not.toBe(encodeName({ scope: "a_b", terminalId: "c" }))
    expect(roundTrip("a", "b_c")).toEqual({ scope: "a", terminalId: "b_c" })
    expect(roundTrip("a_b", "c")).toEqual({ scope: "a_b", terminalId: "c" })
  })

  test("rejects malformed, foreign and empty names", () => {
    const bad = [
      "",
      "muxterm",
      "muxterm_",
      "muxterm__",              // both fields empty
      "muxterm_61_",            // empty id
      "muxterm__61",            // empty scope
      "muxterm_616_6161",       // odd-length hex
      "muxterm_61_6",           // odd-length hex
      "muxterm_6G_6161",        // not hex
      "muxterm_6A_6161",        // uppercase hex is not our spelling
      "muxterm_61_62_63",       // an extra separator
      "_muxterm_61_62",         // prefix not anchored
      "muxterm_61_62 ",         // trailing space
      "notours_61_62",
      "muxterm_ff_6161",        // invalid UTF-8
      "muxterm_c0af_6161",      // overlong encoding of "/"
      "muxterm_eda0bd_6161",    // CESU-8 surrogate half
      "muxterm_61_fe",          // invalid UTF-8 in the id
    ]
    for (const name of bad) expect(decodeName(name)).toBeNull()
  })

  test("a name that decodes is byte-exact — no lossy replacement characters", () => {
    // Buffer.toString("utf8") replaces bad bytes with U+FFFD, which would map
    // MANY names onto one key. The re-encode check is what blocks that.
    const lossy = encodeName({ scope: "�", terminalId: "x" })
    expect(decodeName(lossy)).toEqual({ scope: "�", terminalId: "x" })
    expect(decodeName("muxterm_ff_78")).toBeNull()
  })
})

describe("zmx scope discovery", () => {
  const alpha = { scope: "w:alpha", terminalId: "main" }
  const alphaSecond = { scope: "w:alpha", terminalId: "second" }
  const alphabet = { scope: "w:alphabet", terminalId: "main" }
  const names = [
    encodeName(alpha),
    encodeName(alphaSecond),
    encodeName(alphabet),
    "muxterm_zz_zz",        // corrupt
    "zmx-internal",         // not ours
    "muxterm_773a616c706861_main", // old tmux-style name: raw (non-hex) id
  ]

  test("belongsToScope is an exact match, never a prefix", () => {
    expect(belongsToScope(encodeName(alpha), "w:alpha")).toBe(true)
    expect(belongsToScope(encodeName(alphabet), "w:alpha")).toBe(false)
    expect(belongsToScope(encodeName(alpha), "w:alphabet")).toBe(false)
    expect(belongsToScope("muxterm_zz_zz", "w:alpha")).toBe(false)
  })

  test("closeScope's target set is only that scope's decoded names", () => {
    expect(namesInScope(names, "w:alpha")).toEqual([encodeName(alpha), encodeName(alphaSecond)])
    expect(namesInScope(names, "w:alphabet")).toEqual([encodeName(alphabet)])
    expect(namesInScope(names, "w:")).toEqual([])
    expect(namesInScope(names, "")).toEqual([])
  })

  test("keysFromNames drops anything it cannot decode", () => {
    expect(keysFromNames(names)).toEqual([alpha, alphaSecond, alphabet])
  })
})

describe("zmx name and socket-path limits", () => {
  test("a realistic workspace key fits the name limit with room to spare", () => {
    const key = { scope: workspaceScope(randomUUID()), terminalId: "x".repeat(64) }
    const name = assertNameFits(key)
    expect(name.length).toBe(213)
    expect(name.length).toBeLessThan(TARGET_NAME_MAX)
  })

  test("an oversized key is refused BEFORE creation, never truncated", () => {
    const key = { scope: "w:" + "s".repeat(80), terminalId: "x".repeat(64) }
    expect(encodeName(key).length).toBeGreaterThan(TARGET_NAME_MAX)
    expect(throwsCode(() => assertNameFits(key), "name-too-long")).toBe(true)
    // Two oversized keys that share a prefix must not collapse onto one name.
    const other = { scope: "w:" + "s".repeat(80) + "!", terminalId: "x".repeat(64) }
    expect(encodeName(key)).not.toBe(encodeName(other))
  })

  test("the encoded name is too long to BE a socket path — hence the split", () => {
    // The measurement behind the layout: zmx is one socket per session
    // (<socket_dir>/<session_name>), and a real workspace key encodes to 93
    // characters. There is no directory short enough to make that fit, so the
    // socket gets an opaque hash and the real name travels in the protocol.
    const key = { scope: workspaceScope(randomUUID()), terminalId: "main" }
    expect(encodeName(key).length).toBe(93)
    expect("/run/user/1000/supermux/zmx/".length + 93).toBeGreaterThan(SOCKET_PATH_MAX)
  })

  test("a minted socket basename is short, opaque and derived from the key", () => {
    const key = { scope: workspaceScope(randomUUID()), terminalId: "main" }
    const name = socketBasename(key)
    expect(name.length).toBe(SOCKET_BASENAME_LEN)
    expect(isOurSocketBasename(name)).toBe(true)
    // Derived, not allocated: a restarted broker computes the same one with
    // nothing stored anywhere.
    expect(socketBasename({ ...key })).toBe(name)
    // ...and it leaks nothing about the workspace.
    expect(name.includes(key.scope)).toBe(false)
  })

  test("distinct keys get distinct basenames, including the separator case", () => {
    const seen = new Set<string>()
    for (const key of [
      { scope: "a", terminalId: "b" },
      { scope: "a\u0000b", terminalId: "" },
      { scope: "", terminalId: "a\u0000b" },
      { scope: "ab", terminalId: "" },
      { scope: "", terminalId: "ab" },
    ]) seen.add(socketBasename(key))
    expect(seen.size).toBe(5)
  })

  test("a socket basename that is not ours is not claimed", () => {
    expect(isOurSocketBasename("someone-elses-session")).toBe(false)
    expect(isOurSocketBasename("mx")).toBe(false)
    expect(isOurSocketBasename("mx" + "g".repeat(20))).toBe(false)
    expect(isOurSocketBasename("mx" + "a".repeat(21))).toBe(false)
  })

  test("a target socket path fits sun_path in our private directory", () => {
    const dir = mkdtempSync(join(tmpdir(), "zmx-names-"))
    const key = { scope: workspaceScope(randomUUID()), terminalId: "main" }
    const path = targetSocketPath(dir, key)
    expect(path.endsWith(`/${socketBasename(key)}`)).toBe(true)
    expect(path.length).toBeLessThanOrEqual(SOCKET_PATH_MAX)
  })

  test("a directory too deep for sun_path fails with our error, not bind()'s", () => {
    const dir = "/" + "d".repeat(SOCKET_PATH_MAX)
    const key = { scope: "w:x", terminalId: "main" }
    expect(throwsCode(() => targetSocketPath(dir, key), "name-too-long")).toBe(true)
  })

  test("an unstorable key is refused before a socket path is minted for it", () => {
    // The label has to be storable before the socket is worth creating, or we
    // get a shell nothing can ever find again.
    const key = { scope: "w:" + "s".repeat(80), terminalId: "x".repeat(64) }
    expect(throwsCode(() => targetSocketPath("/run/user/1000/supermux/zmx", key), "name-too-long")).toBe(true)
  })

  test("a session whose label is not ours is refused, never attached to", () => {
    // Sharing one shell between two workspaces is the failure the whole naming
    // scheme exists to prevent, so a mismatch is an error and not an attach.
    const key = { scope: "w:alpha", terminalId: "main" }
    expect(() => assertTargetMatches(key, encodeName(key))).not.toThrow()
    expect(throwsCode(() => assertTargetMatches(key, undefined), "protocol")).toBe(true)
    expect(throwsCode(
      () => assertTargetMatches(key, encodeName({ scope: "w:beta", terminalId: "main" })),
      "protocol",
    )).toBe(true)
    expect(throwsCode(() => assertTargetMatches(key, "garbage"), "protocol")).toBe(true)
  })
})

describe("zmx broker identity", () => {
  /** A procfs stand-in: what `/proc/<pid>/cmdline` would hold, NUL-separated
   * exactly as the kernel writes it, read as argv[0] plus the argument vector
   * the way the real probe does when `/proc/<pid>/exe` is unreadable. `cwd` is
   * null, so a relative argument falls back to its basename — which is the
   * unreadable-cwd case the real probe also has to survive. */
  const fromCmdlines = (blobs: Record<number, string>, cwd: string | null = null) =>
    (pid: number) => {
      const blob = blobs[pid]
      if (blob === undefined) return null
      return { executable: executableFromCmdline(blob), argv: argvFromCmdline(blob), cwd }
    }

  test("a pid running something else entirely is NOT a broker", () => {
    // Every one of these contains "bun" or "mux" somewhere in the joined
    // cmdline, which is what the substring test matched. A recycled pid
    // running any of them used to make the broker refuse to start with
    // `socket-dir-unsafe` — the lockout the design calls the worse failure.
    const blobs: Record<number, string> = {
      101: "bundle\0install\0",
      102: "/usr/bin/bundle\0exec\0rspec\0",
      103: "bunyan\0-o\0short\0",
      104: "tmux\0attach\0-t\0main\0",
      105: "/usr/bin/tmux\0new-session\0",
      106: "/bin/bash\0/home/x/mux-notes/run.sh\0",
      107: "/usr/bin/python3\0/opt/bunker/tools/report.py\0",
      108: "/home/x/.bun/install/cache/somepkg/bin/tool\0--watch\0", // a path with "bun" in it
      109: "node\0/srv/app/server.js\0--mux-port=9898\0",
    }
    for (const pid of Object.keys(blobs).map(Number)) {
      expect(looksLikeBroker(pid, null, fromCmdlines(blobs))).toBe(false)
    }
  })

  test("a pid running OUR OWN executable is a broker", () => {
    // A second broker is the same program we are, so the first half of the
    // identity is "runs what we run" — argv[0]'s basename, exactly, never a
    // substring of the arguments.
    const blobs: Record<number, string> = {
      201: `${process.execPath}\0/srv/supermux/dist/main.js\0`,
      202: `${process.execPath}\0`,
    }
    for (const pid of Object.keys(blobs).map(Number)) {
      expect(looksLikeBroker(pid, null, fromCmdlines(blobs))).toBe(true)
    }
    // ...and this process, through the real procfs probe.
    if (process.platform === "linux") expect(looksLikeBroker(process.pid)).toBe(true)
  })

  test("ANOTHER BUN APP IS NOT OUR BROKER — the executable alone is not an identity", () => {
    // Under an interpreter, "runs what we run" says only "is this bun". Every
    // unrelated bun program this user runs answers yes, so a recycled pid
    // landing on any of them made the broker refuse to start and the user lost
    // every terminal — the same lockout as the old substring test, reached
    // through a different door.
    //
    // The marker is what closes it: a broker records its own entry module
    // beside its pid, and a pid is ours only while it is still running THAT.
    const entry = "/srv/supermux/src/main.ts"
    const others: Record<number, string> = {
      301: `${process.execPath}\0-e\0setTimeout(() => {}, 30_000)\0`,       // bun -e
      302: `${process.execPath}\0run\0dev\0`,                               // somebody's dev server
      303: `${process.execPath}\0/home/x/scratch/scrape.ts\0`,              // a scratch script
      304: `${process.execPath}\0x\0prettier\0--write\0.\0`,                // bunx
      305: `${process.execPath}\0/srv/other-app/src/main.js\0`,             // a DIFFERENT main
      306: `${process.execPath}\0--inspect\0/srv/supermux/src/shim.ts\0`,   // our repo, other entry
    }
    for (const pid of Object.keys(others).map(Number)) {
      expect(looksLikeBroker(pid, entry, fromCmdlines(others))).toBe(false)
    }

    // A real second broker still is one — including one started from another
    // checkout, which matters because XDG_RUNTIME_DIR gives every checkout on
    // a machine the same socket directory. It recorded its own entry, and it
    // is still running it.
    const brokers: Record<number, string> = {
      311: `${process.execPath}\0${entry}\0`,
      312: `${process.execPath}\0--smol\0${entry}\0--port\x009898\0`,
    }
    for (const pid of Object.keys(brokers).map(Number)) {
      expect(looksLikeBroker(pid, entry, fromCmdlines(brokers))).toBe(true)
    }
    // ...and one the unit file exec'd with a RELATIVE entry, resolved against
    // the pid's own working directory rather than guessed at.
    expect(looksLikeBroker(
      321, entry, fromCmdlines({ 321: `${process.execPath}\0src/main.ts\0` }, "/srv/supermux"),
    )).toBe(true)
    expect(looksLikeBroker(
      321, entry, fromCmdlines({ 321: `${process.execPath}\0src/main.ts\0` }, "/srv/other-app"),
    )).toBe(false)
  })

  test("a marker with no recorded entry falls back to the executable", () => {
    // Written by a broker that predates the entry line. Refusing to start over
    // a file we cannot fully read would be worse than the second broker this
    // might miss, so the older, looser check stands for it.
    const blobs = { 401: `${process.execPath}\0/srv/supermux/src/main.ts\0` }
    expect(looksLikeBroker(401, null, fromCmdlines(blobs))).toBe(true)
    expect(looksLikeBroker(401, undefined, fromCmdlines(blobs))).toBe(true)
  })

  test("a live process we may not SIGNAL is alive, not dead", () => {
    // `kill(pid, 0)` raises EPERM for a process owned by another uid. That is
    // positive proof of life, and reading it as ESRCH took over a socket
    // directory whose owner was very much still running.
    expect(isProcessAlive(1)).toBe(true)          // init: exists, and not ours
    expect(isProcessAlive(process.pid)).toBe(true)
    expect(isProcessAlive(2_147_483_646)).toBe(false)
    expect(isProcessAlive(0)).toBe(false)
    expect(isProcessAlive(-1)).toBe(false)
  })

  test("an unreadable or empty procfs answer is not a broker", () => {
    expect(looksLikeBroker(1234, null, () => null)).toBe(false)
    expect(looksLikeBroker(1234, null, fromCmdlines({ 1234: "" }))).toBe(false)
    expect(looksLikeBroker(1234, null, fromCmdlines({ 1234: "\0\0" }))).toBe(false)
    expect(argvFromCmdline("")).toEqual([])
    expect(argvFromCmdline("bun\0-e\0x\0")).toEqual(["bun", "-e", "x"])
    expect(executableFromCmdline("")).toBeNull()
  })
})

describe("zmx socket directory", () => {
  test("prefers the explicit override", () => {
    expect(zmxSocketDir({ MUX_TERM_ZMX_DIR: "/srv/zmx", XDG_RUNTIME_DIR: "/run/user/1000" }, "/state"))
      .toBe("/srv/zmx")
  })

  test("uses XDG_RUNTIME_DIR when it is absolute — short, per-user, tmpfs", () => {
    expect(zmxSocketDir({ XDG_RUNTIME_DIR: "/run/user/1000" }, "/state"))
      .toBe("/run/user/1000/supermux/zmx")
  })

  test("falls back to the state dir when XDG_RUNTIME_DIR is absent or relative", () => {
    expect(zmxSocketDir({}, "/state")).toBe("/state/zmx")
    expect(zmxSocketDir({ XDG_RUNTIME_DIR: "" }, "/state")).toBe("/state/zmx")
    expect(zmxSocketDir({ XDG_RUNTIME_DIR: "relative/path" }, "/state")).toBe("/state/zmx")
  })

  test("creates the directory 0700 regardless of umask, and repairs a loose one", () => {
    const root = mkdtempSync(join(tmpdir(), "zmx-dir-"))
    const dir = join(root, "supermux", "zmx")
    expect(ensureSocketDir(dir)).toBe(dir)
    expect(statSync(dir).mode & 0o777).toBe(0o700)

    chmodSync(dir, 0o777)
    ensureSocketDir(dir)
    expect(statSync(dir).mode & 0o777).toBe(0o700)
  })

  test("claiming the socket dir records this broker and takes over a dead one", () => {
    const dir = mkdtempSync(join(tmpdir(), "zmx-claim-"))
    const marker = join(dir, SOCKET_DIR_OWNER_FILE)

    expect(claimSocketDir(dir)).toBe(dir)
    expect(ownerPidIn(dir)).toBe(process.pid)
    // Idempotent for the process that already holds it: a second backend over
    // the same directory is a restart in a test, not a second broker.
    expect(claimSocketDir(dir)).toBe(dir)

    // A pid nothing is running under is a broker that died without cleaning up.
    writeFileSync(marker, "2147483646\n")
    expect(claimSocketDir(dir)).toBe(dir)
    expect(ownerPidIn(dir)).toBe(process.pid)

    // ...and so is an unreadable or nonsense marker: it says nothing about a
    // live owner, and refusing on it would lock the user out for a typo.
    writeFileSync(marker, "not-a-pid\n")
    expect(claimSocketDir(dir)).toBe(dir)
  })

  test("the marker is created exclusively, and 0600 — including a TAKEOVER", () => {
    const dir = mkdtempSync(join(tmpdir(), "zmx-claim-"))
    claimSocketDir(dir)
    expect(statSync(join(dir, SOCKET_DIR_OWNER_FILE)).mode & 0o777).toBe(0o600)

    // `mode` is IGNORED for a path that already exists, so a takeover that
    // wrote over the old file would inherit whatever permissions it had. The
    // directory is 0700, so this is defence in depth rather than a hole — but
    // a marker that silently stopped being 0600 is the kind of thing nobody
    // notices until the directory's mode is the only thing left protecting it.
    writeFileSync(join(dir, SOCKET_DIR_OWNER_FILE), "2147483646\n", { mode: 0o644 })
    chmodSync(join(dir, SOCKET_DIR_OWNER_FILE), 0o644)
    expect(claimSocketDir(dir)).toBe(dir)
    expect(statSync(join(dir, SOCKET_DIR_OWNER_FILE)).mode & 0o777).toBe(0o600)
  })

  /**
   * TWO BROKERS CLAIMING ONE DIRECTORY, RUN FOR REAL.
   *
   * Two real processes released by a wall-clock barrier. A single process
   * cannot assert this: the window being closed is between two syscalls, and
   * JS run-to-completion rules out nothing when the peer is another process.
   *
   * Each child claims, records its answer, and stays alive until BOTH have
   * answered — so a loser finds a LIVE owner in the marker, exactly as a
   * second broker would during a restart.
   */
  const raceForTheClaim = async (dir: string) => {
    const namesModule = join(import.meta.dir, "names.ts")
    const script = join(dir, "claimer.ts")
    writeFileSync(script, `
import { readdirSync, writeFileSync } from "fs"
import { join } from "path"
import { claimSocketDir } from ${JSON.stringify(namesModule)}
const dir = process.env.RACE_DIR!
const startAt = Number(process.env.RACE_START)
while (Date.now() < startAt) { /* barrier: both processes claim in the same instant */ }
let result = "lost"
try { claimSocketDir(dir); result = "won" } catch (error) { result = \`lost:\${(error as { code?: string }).code}\` }
writeFileSync(join(dir, \`result-\${process.pid}\`), result)
const deadline = Date.now() + 10_000
while (Date.now() < deadline &&
  readdirSync(dir).filter(name => name.startsWith("result-")).length < 2) { /* spin */ }
console.log(result)
`)
    const startAt = Date.now() + 1500
    const claimers = [0, 1].map(() => Bun.spawn([process.execPath, script], {
      env: { ...process.env, RACE_DIR: dir, RACE_START: String(startAt) },
      stdout: "pipe", stderr: "pipe",
    }))
    const outcomes = await Promise.all(claimers.map(async child => {
      const [out, err] = await Promise.all([
        new Response(child.stdout).text(), new Response(child.stderr).text(),
      ])
      await child.exited
      return out.trim() || `no answer: ${err}`
    }))
    return { outcomes, pids: claimers.map(child => child.pid) }
  }

  /** Exactly one "won", one refusal, and the marker naming the winner — never
   * whoever happened to write last. */
  const expectExactlyOneWinner = (
    dir: string, race: { outcomes: string[], pids: number[] },
  ) => {
    expect(race.outcomes.filter(outcome => outcome === "won")).toHaveLength(1)
    expect(race.outcomes.filter(outcome => outcome.startsWith("lost:socket-dir-unsafe"))).toHaveLength(1)
    expect(race.pids).toContain(ownerPidIn(dir))
  }

  // procfs only, like the refusal it asserts: off Linux `looksLikeBroker` says
  // "not a broker" by design, so the loser would take the directory over.
  test.skipIf(process.platform !== "linux")(
    "two brokers claiming at the same instant: exactly ONE wins", async () => {
      // The EMPTY-directory race. Reading the marker, deciding and then writing
      // it left a window in which two brokers starting together both saw "no
      // owner" and both won — the two-broker state the lock exists to prevent.
      const dir = mkdtempSync(join(tmpdir(), "zmx-race-"))
      expectExactlyOneWinner(dir, await raceForTheClaim(dir))
    }, 30_000)

  // procfs only, for the same reason as the empty-directory race above.
  test.skipIf(process.platform !== "linux")(
    "two brokers taking over ONE stale marker: exactly ONE wins", async () => {
      // THE OTHER HALF OF THE RACE, AND THE ONE THE EXCLUSIVE CREATE NEVER
      // COVERED. `O_CREAT | O_EXCL` settles an empty directory, and nothing
      // else: the moment a marker exists the create fails for EVERYONE and the
      // claim falls through to read the pid → judge it dead → write ours. Two
      // brokers restarting together read the same dead pid, both judge it
      // dead, and both write. The file ends up naming one of them, so nothing
      // looks broken — and both processes return believing they own the
      // directory, which is precisely the state `owner:false` in backend.ts is
      // deduced from and therefore the state that must be impossible.
      //
      // A crashed broker is not a rare setup, either: it is what every
      // unclean shutdown leaves behind, and a supervisor restarting two
      // workers is how two claimers arrive at once.
      const dir = mkdtempSync(join(tmpdir(), "zmx-race-stale-"))
      // The marker of a broker that died without cleaning up. The pid is at
      // the top of the range, where nothing is ever running.
      writeFileSync(join(dir, SOCKET_DIR_OWNER_FILE), "2147483646\n", { mode: 0o600 })

      expectExactlyOneWinner(dir, await raceForTheClaim(dir))
      // ...and the loser's refusal is about the WINNER, not about the corpse
      // both of them found: a broker that refused over a pid nothing is
      // running under would be refusing for a reason that cannot be acted on.
      expect(ownerPidIn(dir)).not.toBe(2147483646)
    }, 30_000)

  test("the claim lock is released, even when the claim is refused", () => {
    // A lock that outlives its critical section is the lockout this file's
    // own checks call the worse failure — so it must be gone whether the
    // claim returned or threw, and a claim must never find one left behind.
    const dir = mkdtempSync(join(tmpdir(), "zmx-lock-"))
    claimSocketDir(dir)
    expect(existsSync(join(dir, SOCKET_DIR_CLAIM_LOCK))).toBe(false)

    // An abandoned lock — a broker killed inside the critical section — is
    // broken once it is old enough, rather than locking the directory forever.
    mkdirSync(join(dir, SOCKET_DIR_CLAIM_LOCK))
    const longAgo = new Date(Date.now() - 600_000)
    utimesSync(join(dir, SOCKET_DIR_CLAIM_LOCK), longAgo, longAgo)
    expect(claimSocketDir(dir)).toBe(dir)
    expect(existsSync(join(dir, SOCKET_DIR_CLAIM_LOCK))).toBe(false)
  })

  // procfs only: `looksLikeBroker` cannot tell a broker from a recycled pid
  // without it, and deliberately takes the directory over rather than locking
  // the user out. So this asserts the Linux behaviour, where the check exists.
  test.skipIf(process.platform !== "linux")(
    "a socket dir held by ANOTHER live broker is refused, not shared", async () => {
      // The `owner:false` deduction in backend.ts is only sound while ONE
      // process owns every broker viewer of a target; two brokers on one
      // directory would each miss the other's leases. A live process running
      // our own entry module is what a lingering old broker looks like during
      // a restart — and the marker names that entry, because the broker that
      // wrote the marker recorded it.
      const dir = mkdtempSync(join(tmpdir(), "zmx-claim-"))
      const entry = join(dir, "broker-entry.ts")
      writeFileSync(entry, "setTimeout(() => {}, 30_000)\n")
      const stand_in = Bun.spawn([process.execPath, entry], {
        stdin: "ignore", stdout: "ignore", stderr: "ignore",
      })
      try {
        await waitForCmdline(stand_in.pid)
        writeFileSync(join(dir, SOCKET_DIR_OWNER_FILE), `${stand_in.pid}\n${entry}\n`)
        const refusal = refusalFrom(() => claimSocketDir(dir))
        expect(refusal.code).toBe("socket-dir-unsafe")

        // ...AND THE CLIENT IS TOLD TO COME BACK. This is the one refusal
        // under this code that clears itself: the overlap it names is what a
        // broker restart looks like from the new process, so the OLD broker is
        // seconds from exiting and the next claim wins. Marked unrecoverable,
        // the failure frame ends the terminal on the client
        // (`TerminalClient.kt`: `if (!event.recoverable) finish(Failed)`) and
        // the user's terminals stay dead across a restart they did not notice.
        expect(refusal.recoverable).toBe(true)
        expect(refusal.toEvent()).toMatchObject({ type: "failure", code: "socket-dir-unsafe", recoverable: true })
      } finally {
        stand_in.kill()
      }
    })

  test("a directory locked by another claim is refused RECOVERABLY, not for good", () => {
    // The other half of contention: a peer inside its critical section. The
    // lock is young, so it is not broken as stale, and the wait runs out —
    // a wait measured in seconds against a hold measured in microseconds, so
    // whatever is holding it is either about to release it or about to be
    // broken as stale. Neither is a reason to stop reconnecting.
    const dir = mkdtempSync(join(tmpdir(), "zmx-locked-"))
    mkdirSync(join(dir, SOCKET_DIR_CLAIM_LOCK))
    try {
      const refusal = refusalFrom(() => claimSocketDir(dir))
      expect(refusal.code).toBe("socket-dir-unsafe")
      expect(refusal.recoverable).toBe(true)
    } finally {
      try { rmdirSync(join(dir, SOCKET_DIR_CLAIM_LOCK)) } catch {}
    }
  }, 15_000)

  test("a socket dir we cannot own is refused PERMANENTLY — a human has to act", () => {
    // The contrast that keeps `recoverable` meaningful. Ownership and mode are
    // facts about the filesystem; retrying changes nothing, and a client that
    // reconnected forever over one would hide it.
    const root = mkdtempSync(join(tmpdir(), "zmx-perm-"))
    const file = join(root, "zmx")
    writeFileSync(file, "")
    expect(refusalFrom(() => ensureSocketDir(file)).recoverable).toBe(false)
  })

  // procfs only: without it `looksLikeBroker` already answers "not a broker"
  // for everything, so there is no tightening left to demonstrate.
  test.skipIf(process.platform !== "linux")(
    "A DIFFERENT BUN APP IS NOT OUR BROKER — the directory is taken over, not refused",
    async () => {
      // THE LOCKOUT THIS CLOSES, WITH REAL PROCESSES. "Runs the same
      // executable we do" is, under an interpreter, only "is this bun". The
      // user's dev server, formatter, scratch script and `bunx` one-liner all
      // answered yes, so a recycled pid landing on any of them made the broker
      // refuse to start with `socket-dir-unsafe` and the user lost every
      // terminal — for a process that has nothing to do with supermux.
      //
      // The marker's recorded entry is the second fact that settles it: this
      // pid is bun, it is alive, and it is NOT running what the marker says a
      // broker was running, so it is a corpse's pid handed to a stranger.
      const dir = mkdtempSync(join(tmpdir(), "zmx-stranger-"))
      const stranger = Bun.spawn([process.execPath, "-e", "setTimeout(() => {}, 30_000)"], {
        stdin: "ignore", stdout: "ignore", stderr: "ignore",
      })
      try {
        await waitForCmdline(stranger.pid)
        writeFileSync(
          join(dir, SOCKET_DIR_OWNER_FILE),
          `${stranger.pid}\n/srv/supermux/src/main.ts\n`,
        )
        expect(claimSocketDir(dir)).toBe(dir)
        expect(ownerPidIn(dir)).toBe(process.pid)
      } finally {
        stranger.kill()
      }
    })

  test("a symlinked socket dir is refused WITHOUT chmodding whatever it points at", () => {
    // `chmod` follows symlinks, and the chmod ran BEFORE the lstat. So a path
    // replaced by a link to somebody else's directory had that directory set to
    // 0700 — a write to a path we were about to refuse, by a process that had
    // not yet established it owned anything. The checks come first now.
    const root = mkdtempSync(join(tmpdir(), "zmx-link-"))
    const elsewhere = join(root, "elsewhere")
    mkdirSync(elsewhere, { mode: 0o755 })
    chmodSync(elsewhere, 0o755) // mkdir's mode is masked by umask; be explicit
    const link = join(root, "zmx")
    symlinkSync(elsewhere, link)

    expect(throwsCode(() => ensureSocketDir(link), "socket-dir-unsafe")).toBe(true)
    // Untouched. Before the reorder this was 0700.
    expect(statSync(elsewhere).mode & 0o777).toBe(0o755)
  })

  test("an existing directory is still tightened to 0700 once it has passed the checks", () => {
    // The chmod is not redundant and must not be lost in the reorder: mkdir's
    // mode is masked by umask and IGNORED for a directory that already exists,
    // so an inherited 0755 stays 0755 without it — and the mode check below
    // would then refuse the directory the broker just made.
    const root = mkdtempSync(join(tmpdir(), "zmx-tighten-"))
    const dir = join(root, "zmx")
    mkdirSync(dir, { mode: 0o755 })
    chmodSync(dir, 0o755)
    expect(ensureSocketDir(dir)).toBe(dir)
    expect(statSync(dir).mode & 0o777).toBe(0o700)
  })

  test("refuses a path that is not a directory we can own", () => {
    const root = mkdtempSync(join(tmpdir(), "zmx-dir-"))
    const file = join(root, "zmx")
    writeFileSync(file, "")
    expect(throwsCode(() => ensureSocketDir(file), "socket-dir-unsafe")).toBe(true)

    const nested = join(root, "sub")
    mkdirSync(nested)
    chmodSync(nested, 0o500) // read-only parent: creating our leaf must fail loudly
    expect(throwsCode(() => ensureSocketDir(join(nested, "zmx")), "socket-dir-unsafe")).toBe(true)
    chmodSync(nested, 0o700)
  })
})
