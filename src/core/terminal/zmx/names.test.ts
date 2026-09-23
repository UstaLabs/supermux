import { describe, expect, test } from "bun:test"
import { mkdtempSync, mkdirSync, readFileSync, statSync, writeFileSync, chmodSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { randomUUID } from "crypto"
import {
  SOCKET_PATH_MAX,
  TARGET_NAME_MAX,
  assertNameFits,
  belongsToScope,
  claimSocketDir,
  SOCKET_DIR_OWNER_FILE,
  decodeName,
  encodeName,
  ensureSocketDir,
  executableFromCmdline,
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
import { isWorkspaceTerminalError } from "../workspace-backend"
import { workspaceScope } from "../../workspace/scope"

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
   * exactly as the kernel writes it, resolved to argv[0] the way the real
   * probe does when `/proc/<pid>/exe` is unreadable. */
  const fromCmdlines = (blobs: Record<number, string>) =>
    (pid: number) => {
      const blob = blobs[pid]
      return blob === undefined ? null : executableFromCmdline(blob)
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
      expect(looksLikeBroker(pid, fromCmdlines(blobs))).toBe(false)
    }
  })

  test("a pid running OUR OWN executable is a broker", () => {
    // A second broker is the same program we are, so identity is "runs what we
    // run" — argv[0]'s basename, exactly, never a substring of the arguments.
    const blobs: Record<number, string> = {
      201: `${process.execPath}\0/srv/supermux/dist/main.js\0`,
      202: `${process.execPath}\0`,
    }
    for (const pid of Object.keys(blobs).map(Number)) {
      expect(looksLikeBroker(pid, fromCmdlines(blobs))).toBe(true)
    }
    // ...and this process, through the real procfs probe.
    if (process.platform === "linux") expect(looksLikeBroker(process.pid)).toBe(true)
  })

  test("an unreadable or empty procfs answer is not a broker", () => {
    expect(looksLikeBroker(1234, () => null)).toBe(false)
    expect(looksLikeBroker(1234, fromCmdlines({ 1234: "" }))).toBe(false)
    expect(looksLikeBroker(1234, fromCmdlines({ 1234: "\0\0" }))).toBe(false)
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
    expect(readFileSync(marker, "utf8").trim()).toBe(String(process.pid))
    // Idempotent for the process that already holds it: a second backend over
    // the same directory is a restart in a test, not a second broker.
    expect(claimSocketDir(dir)).toBe(dir)

    // A pid nothing is running under is a broker that died without cleaning up.
    writeFileSync(marker, "2147483646\n")
    expect(claimSocketDir(dir)).toBe(dir)
    expect(readFileSync(marker, "utf8").trim()).toBe(String(process.pid))

    // ...and so is an unreadable or nonsense marker: it says nothing about a
    // live owner, and refusing on it would lock the user out for a typo.
    writeFileSync(marker, "not-a-pid\n")
    expect(claimSocketDir(dir)).toBe(dir)
  })

  test("the marker is created exclusively, and 0600", () => {
    const dir = mkdtempSync(join(tmpdir(), "zmx-claim-"))
    claimSocketDir(dir)
    expect(statSync(join(dir, SOCKET_DIR_OWNER_FILE)).mode & 0o777).toBe(0o600)
  })

  // procfs only, like the refusal it asserts: off Linux `looksLikeBroker` says
  // "not a broker" by design, so the loser would take the directory over.
  test.skipIf(process.platform !== "linux")(
    "two brokers claiming at the same instant: exactly ONE wins", async () => {
      // THE RACE, RUN FOR REAL. Reading the marker, deciding and then writing
      // it left a window in which two brokers starting together both saw "no
      // owner" and both won — the two-broker state the lock exists to prevent,
      // and one that JS run-to-completion does NOT rule out: the read is I/O,
      // and the peer is another process anyway. Two real processes, released
      // by a wall-clock barrier, are the only honest way to assert it.
      const dir = mkdtempSync(join(tmpdir(), "zmx-race-"))
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
// Stay alive until BOTH have answered: a loser must find a LIVE owner in the
// marker, exactly as a second broker would during a restart.
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

      expect(outcomes.filter(outcome => outcome === "won")).toHaveLength(1)
      expect(outcomes.filter(outcome => outcome.startsWith("lost:socket-dir-unsafe"))).toHaveLength(1)
      // ...and the directory belongs to the winner, not to whoever wrote last.
      const owner = Number(readFileSync(join(dir, SOCKET_DIR_OWNER_FILE), "utf8").trim())
      expect(claimers.map(child => child.pid)).toContain(owner)
    }, 30_000)

  // procfs only: `looksLikeBroker` cannot tell a broker from a recycled pid
  // without it, and deliberately takes the directory over rather than locking
  // the user out. So this asserts the Linux behaviour, where the check exists.
  test.skipIf(process.platform !== "linux")(
    "a socket dir held by ANOTHER live broker is refused, not shared", async () => {
      // The `owner:false` deduction in backend.ts is only sound while ONE
      // process owns every broker viewer of a target; two brokers on one
      // directory would each miss the other's leases. A live, bun-shaped
      // process is what a lingering old broker looks like during a restart.
      const dir = mkdtempSync(join(tmpdir(), "zmx-claim-"))
      const stand_in = Bun.spawn([process.execPath, "-e", "setTimeout(() => {}, 30_000)"], {
        stdin: "ignore", stdout: "ignore", stderr: "ignore",
      })
      try {
        // /proc/<pid>/cmdline is empty until the child has finished exec'ing.
        for (let attempt = 0; attempt < 200; attempt++) {
          if (readFileSync(`/proc/${stand_in.pid}/cmdline`, "utf8").length > 0) break
          await Bun.sleep(5)
        }
        writeFileSync(join(dir, SOCKET_DIR_OWNER_FILE), `${stand_in.pid}\n`)
        expect(throwsCode(() => claimSocketDir(dir), "socket-dir-unsafe")).toBe(true)
      } finally {
        stand_in.kill()
      }
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
