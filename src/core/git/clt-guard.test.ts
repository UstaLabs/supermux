import { afterEach, beforeEach, expect, test } from "bun:test"
import { mkdtempSync, readFileSync, rmSync, statSync } from "fs"
import { spawnSync } from "child_process"
import { tmpdir } from "os"
import { join } from "path"
import { APPLE_GIT_STUB, NO_CLT_MESSAGE, NO_CLT_SHIM, applyCltGuard, noCltDir, type CltGuardDeps } from "./clt-guard"

let stateDir: string
beforeEach(() => { stateDir = mkdtempSync(join(tmpdir(), "clt-guard-")) })
afterEach(() => { rmSync(stateDir, { recursive: true, force: true }) })

const BASE_PATH = "/usr/bin:/bin:/usr/sbin:/sbin"

function deps(over: Partial<CltGuardDeps> = {}): CltGuardDeps & { xcodeCalls: number } {
  const d = {
    platform: "darwin" as NodeJS.Platform,
    which: (_bin: string, _path: string) => APPLE_GIT_STUB as string | null,
    runXcodeSelect: () => { d.xcodeCalls++; return 2 },
    stateDir,
    env: { PATH: BASE_PATH } as Record<string, string | undefined>,
    xcodeCalls: 0,
    ...over,
  }
  return d
}

test("darwin + Apple's stub + no CLT: shim written, PATH prepended, flag set", () => {
  const d = deps()
  const r = applyCltGuard(d)
  const dir = noCltDir(stateDir)
  expect(r.gitUnavailable).toBe(true)
  expect(r.reason).toContain("Command Line Tools")
  expect(r.shimDir).toBe(dir)
  expect(d.env.PATH).toBe(`${dir}:${BASE_PATH}`)
  expect(readFileSync(join(dir, "git"), "utf8")).toBe(NO_CLT_SHIM)
})

test("the shim's content is exact, it is 0755, and running it fails with the reason", () => {
  applyCltGuard(deps())
  const shim = join(noCltDir(stateDir), "git")
  expect(NO_CLT_SHIM).toBe(
    "#!/bin/sh\necho \"git is not available: install the Xcode Command Line Tools (xcode-select --install)\" >&2\nexit 1\n",
  )
  expect(statSync(shim).mode & 0o777).toBe(0o755)
  const run = spawnSync(shim, ["status"], { encoding: "utf8" })
  expect(run.status).toBe(1)
  expect(run.stderr.trim()).toBe(NO_CLT_MESSAGE)
  expect(run.stdout).toBe("")
})

test("a second run does not prepend the shim twice, and looks past it to find the stub", () => {
  const d = deps({ which: (_b, path) => (path.includes("no-clt") ? "/x/no-clt/git" : APPLE_GIT_STUB) })
  applyCltGuard(d)
  const r = applyCltGuard(d)
  expect(r.gitUnavailable).toBe(true)
  expect(d.env.PATH).toBe(`${noCltDir(stateDir)}:${BASE_PATH}`)
})

test("darwin + CLT installed: nothing", () => {
  const d = deps({ runXcodeSelect: () => 0 })
  const r = applyCltGuard(d)
  expect(r.gitUnavailable).toBe(false)
  expect(d.env.PATH).toBe(BASE_PATH)
  expect(() => statSync(noCltDir(stateDir))).toThrow()
})

test("darwin + Homebrew git first on PATH: nothing (and xcode-select is not even asked)", () => {
  const d = deps({ which: () => "/opt/homebrew/bin/git" })
  const r = applyCltGuard(d)
  expect(r.gitUnavailable).toBe(false)
  expect(d.xcodeCalls).toBe(0)
  expect(d.env.PATH).toBe(BASE_PATH)
  expect(() => statSync(noCltDir(stateDir))).toThrow()
})

test("darwin + no git at all: nothing", () => {
  const d = deps({ which: () => null })
  expect(applyCltGuard(d).gitUnavailable).toBe(false)
  expect(d.env.PATH).toBe(BASE_PATH)
})

test("linux: nothing, even with a /usr/bin/git and no xcode-select", () => {
  const d = deps({ platform: "linux" })
  const r = applyCltGuard(d)
  expect(r.gitUnavailable).toBe(false)
  expect(d.xcodeCalls).toBe(0)
  expect(d.env.PATH).toBe(BASE_PATH)
})
