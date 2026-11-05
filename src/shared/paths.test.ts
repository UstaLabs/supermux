import { describe, expect, test } from "bun:test"
import { dirname, join, sep } from "path"
import { HOST_KEY_FILE, SESSION_INBOX_DIR, STATE_DIR, agentWorkdir, defaultMuxHome, homeDirName, inboxFilePath, isMuxInternalPath } from "./paths"

test("HOST_KEY_FILE lives under STATE_DIR", () => {
  expect(HOST_KEY_FILE).toBe(`${STATE_DIR}/host-key`)
})

test("inboxFilePath is <dir>/<session id>.jsonl, in the session inbox folder unless another is given", () => {
  expect(inboxFilePath("7c1d2e9a-0000")).toBe(`${SESSION_INBOX_DIR}/7c1d2e9a-0000.jsonl`)
  expect(inboxFilePath("7c1d2e9a-0000", "/data/inboxes")).toBe("/data/inboxes/7c1d2e9a-0000.jsonl")
})

test("homeDirName keeps an ordinary name, and makes any other one a single folder inside the agents folder", () => {
  expect(homeDirName("Fix login")).toBe("Fix login")
  expect(homeDirName("a/b")).toBe("a-b")
  expect(homeDirName("..\\x")).toBe("..-x")
  expect(homeDirName("..")).toBe("_..")
  expect(homeDirName(".")).toBe("_.")
  const base = join(STATE_DIR, "agents", "codex")
  const escape = "../../../../.codex"
  expect(homeDirName(escape)).toBe("..-..-..-..-.codex")
  expect(join(base, homeDirName(escape)).startsWith(base + sep)).toBe(true)
  expect(dirname(join(base, homeDirName(escape)))).toBe(base)
})

describe("isMuxInternalPath", () => {
  const mux = "/home/a/.mux"
  test("the mux home and agent folders are internal", () => {
    expect(isMuxInternalPath("/home/a/.mux", mux)).toBe(true)
    expect(isMuxInternalPath("/home/a/.mux/", mux)).toBe(true)
    expect(isMuxInternalPath(agentWorkdir("Kahya", mux), mux)).toBe(true)
    expect(isMuxInternalPath("/home/a/.mux/workspace/Kahya/sub", mux)).toBe(true)
  })
  test("everything else is a project", () => {
    expect(isMuxInternalPath("/home/a/.mux/workspace", mux)).toBe(false)
    expect(isMuxInternalPath("/home/a/.mux/state/projects/local/telpa", mux)).toBe(false)
    expect(isMuxInternalPath("/home/a/.mux-other", mux)).toBe(false)
    expect(isMuxInternalPath("/home/a/code/supermux", mux)).toBe(false)
  })
})

// Q2: on Windows the home is %USERPROFILE% now, where the broker took $HOME first before (an MSYS2 or Cygwin shell sets
// its own). Nobody's state moves silently: a .mux only that $HOME has is kept, and the broker says so.
describe("defaultMuxHome", () => {
  const has = (...paths: string[]) => (path: string) => paths.includes(path)

  test("is <home>/.mux", () => {
    expect(defaultMuxHome("/home/ada", undefined, has())).toEqual({ path: join("/home/ada", ".mux") })
  })

  test("keeps a .mux that only the shell's $HOME has, with a note for the log", () => {
    const former = join("/msys/home/ada", ".mux")
    const chosen = defaultMuxHome("/users/ada", "/msys/home/ada", has(former))
    expect(chosen.path).toBe(former)
    expect(chosen.note).toContain(join("/users/ada", ".mux"))
  })

  test("takes the home's own .mux when it has one, or when neither has one yet", () => {
    const former = join("/msys/home/ada", ".mux"), standard = join("/users/ada", ".mux")
    expect(defaultMuxHome("/users/ada", "/msys/home/ada", has(former, standard))).toEqual({ path: standard })
    expect(defaultMuxHome("/users/ada", "/msys/home/ada", has())).toEqual({ path: standard })
  })
})
