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
  keysFromNames,
  namesInScope,
  serverSocketPath,
  targetSocketPath,
  zmxSocketDir,
} from "./names"
import { isWorkspaceTerminalError } from "../workspace-backend"
import { workspaceScope } from "../../workspace/scope"

const roundTrip = (scope: string, terminalId: string) =>
  decodeName(encodeName({ scope, terminalId }))

const throwsCode = (fn: () => unknown, code: "name-too-long" | "socket-dir-unsafe") => {
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

  test("the server socket fits sun_path in our private directory", () => {
    const dir = mkdtempSync(join(tmpdir(), "zmx-names-"))
    const path = serverSocketPath(dir)
    expect(path.endsWith("/zmx.sock")).toBe(true)
    expect(path.length).toBeLessThanOrEqual(SOCKET_PATH_MAX)
  })

  test("a directory too deep for sun_path fails with our error, not bind()'s", () => {
    const dir = "/" + "d".repeat(SOCKET_PATH_MAX)
    expect(throwsCode(() => serverSocketPath(dir), "name-too-long")).toBe(true)
  })

  test("a per-target socket cannot hold a real workspace name — hence one server socket", () => {
    // This is the measurement behind the layout: 93-char name + directory +
    // ".sock" is 126 bytes, and there is no directory short enough to fix it.
    const key = { scope: workspaceScope(randomUUID()), terminalId: "main" }
    expect(encodeName(key).length).toBe(93)
    expect(throwsCode(() => targetSocketPath("/run/user/1000/supermux/zmx", key), "name-too-long")).toBe(true)
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
