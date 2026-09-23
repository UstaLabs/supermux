import { describe, expect, test } from "bun:test"
import { mkdtempSync, mkdirSync, statSync, writeFileSync, chmodSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { randomUUID } from "crypto"
import {
  SOCKET_PATH_MAX,
  TARGET_NAME_MAX,
  assertNameFits,
  belongsToScope,
  decodeName,
  encodeName,
  ensureSocketDir,
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
