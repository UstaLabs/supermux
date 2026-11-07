import { test, expect } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { gitSync, runSync } from "./exec-sync"

// #29: branches, integrate, remote, pr, clone and the editor's repo scan each ran git through a copy of the same
// execFileSync wrapper, without a hidden window or the live env. runSync is that wrapper, once, beside exec.ts
// (which stays async-only: src/core/worktree/no-sync-exec.test.ts).
test("runSync answers ok with stdout trimmed, or not ok with stdout and stderr", () => {
  expect(runSync("bash", ["-c", "echo '  hi  '"], { timeoutMs: 10_000 })).toEqual({ ok: true, out: "hi" })
  expect(runSync("bash", ["-c", "echo out; echo err >&2; exit 2"], { timeoutMs: 10_000 })).toEqual({ ok: false, out: "out\nerr" })
  expect(runSync("no-such-command-supermux", [], { timeoutMs: 10_000 })).toEqual({ ok: false, out: "" })
})

test("runSync stops a command at its timeout, and passes the env it is given", () => {
  const t0 = Date.now()
  expect(runSync("bash", ["-c", "sleep 5"], { timeoutMs: 200 }).ok).toBe(false)
  expect(Date.now() - t0).toBeLessThan(2000)
  expect(runSync("bash", ["-c", "echo $SUPERMUX_GIT_X"], { timeoutMs: 10_000, env: { ...process.env, SUPERMUX_GIT_X: "given" } }).out).toBe("given")
})

test("gitSync runs git in a folder", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-exec-"))
  try {
    expect(gitSync(dir, ["init", "-q", "-b", "main"], 10_000).ok).toBe(true)
    expect(gitSync(dir, ["symbolic-ref", "--short", "HEAD"], 10_000)).toEqual({ ok: true, out: "main" })
    expect(gitSync(dir, ["rev-parse", "--verify", "nope"], 10_000).ok).toBe(false)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
