import { test, expect } from "bun:test"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { gitAsync, runAsync } from "./exec"

test("gitAsync returns trimmed stdout", async () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-exec-"))
  await gitAsync(dir, ["init", "-q", "-b", "main"])
  expect(await gitAsync(dir, ["symbolic-ref", "--short", "HEAD"])).toBe("main")
})

test("gitAsync rejects with git's stderr on failure", async () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-exec-"))
  await expect(gitAsync(dir, ["rev-parse", "--verify", "nope"])).rejects.toThrow(/nope|fatal/)
})

test("runAsync reports exit code and captures output", async () => {
  const r = await runAsync("bash", ["-c", "echo hi; echo err >&2; exit 3"], { cwd: tmpdir() })
  expect(r.code).toBe(3)
  expect(r.output).toContain("hi")
  expect(r.output).toContain("err")
})

test("runAsync kills the process at the timeout", async () => {
  const t0 = Date.now()
  const r = await runAsync("bash", ["-c", "sleep 5"], { cwd: tmpdir(), timeoutMs: 200 })
  expect(Date.now() - t0).toBeLessThan(2000)
  expect(r.code).not.toBe(0)
  expect(r.timedOut).toBe(true)
})

test("runAsync keeps only the tail when output exceeds maxOutput", async () => {
  const r = await runAsync("bash", ["-c", "for i in $(seq 1 2000); do echo line$i; done"], { cwd: tmpdir(), maxOutput: 100 })
  expect(r.output.length).toBeLessThanOrEqual(100)
  expect(r.output).toContain("line2000")
})
