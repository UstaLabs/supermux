// #29: keep-awake, the git requirement and the Windows install each ran a command to its end with node's execFile and
// a callback of their own. runCommand does that once, through the launcher.
import { expect, test } from "bun:test"
import { tmpdir } from "node:os"
import { runCommand } from "./run"

test("a command that succeeds gives its exit code and stdout", async () => {
  const r = await runCommand("bash", ["-c", "echo out; echo err >&2"])
  expect(r).toMatchObject({ code: 0, stdout: "out\n", stderr: "err\n", timedOut: false })
  expect(r.error).toBeUndefined()
})

test("a command that fails gives its exit code and stderr, and one that cannot start says why", async () => {
  expect(await runCommand("bash", ["-c", "echo nope >&2; exit 3"])).toMatchObject({ code: 3, stderr: "nope\n" })
  const missing = await runCommand("no-such-command-supermux", [])
  expect(missing.code).toBeNull()
  // Bun says "Executable not found in $PATH", Node ENOENT: either way, the error names it.
  expect(missing.error).toContain("no-such-command-supermux")
})

test("a command past its timeout is stopped, and says it timed out", async () => {
  const t0 = Date.now()
  const r = await runCommand("bash", ["-c", "sleep 5"], { timeoutMs: 200 })
  expect(r.timedOut).toBe(true)
  expect(r.code).not.toBe(0)
  expect(Date.now() - t0).toBeLessThan(3_000)
})

test("it runs in the folder and with the env it is given, and caps what it keeps", async () => {
  const r = await runCommand("bash", ["-c", "pwd; echo $SUPERMUX_RUN_X; head -c 5000 /dev/zero | tr '\\\\0' x"], {
    cwd: tmpdir(), env: { ...process.env, SUPERMUX_RUN_X: "given" }, maxChars: 100,
  })
  expect(r.stdout.startsWith(`${tmpdir()}\ngiven\n`)).toBe(true)
  expect(r.stdout.length).toBeLessThanOrEqual(100)
})
