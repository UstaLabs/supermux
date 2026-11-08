// #29: keep-awake, the git requirement and the Windows install each ran a command to its end with node's execFile and
// a callback of their own. runCommand does that once, through the launcher.
import { expect, test } from "bun:test"
import { mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { runCommand, type RunOptions } from "./run"

// The commands are this runtime itself, so they run the same on every OS (on Windows, bash's pwd is never a C:\ path).
const script = (source: string, opts?: RunOptions) => runCommand(process.execPath, ["-e", source], opts)

test("a command that succeeds gives its exit code and stdout", async () => {
  const r = await script("console.log('out'); console.error('err')")
  expect(r).toMatchObject({ code: 0, stdout: "out\n", stderr: "err\n", timedOut: false })
  expect(r.error).toBeUndefined()
})

test("a command that fails gives its exit code and stderr, and one that cannot start says why", async () => {
  expect(await script("console.error('nope'); process.exit(3)")).toMatchObject({ code: 3, stderr: "nope\n" })
  const missing = await runCommand("no-such-command-supermux", [])
  expect(missing.code).toBeNull()
  // Bun says "Executable not found in $PATH", Node ENOENT: either way, the error names it.
  expect(missing.error).toContain("no-such-command-supermux")
})

test("a command past its timeout is stopped, and says it timed out", async () => {
  const t0 = Date.now()
  const r = await script("setTimeout(() => {}, 5000)", { timeoutMs: 200 })
  expect(r.timedOut).toBe(true)
  expect(r.code).not.toBe(0)
  expect(Date.now() - t0).toBeLessThan(3_000)
})

test("it runs in the folder and with the env it is given, and caps what it keeps", async () => {
  const dir = mkdtempSync(join(tmpdir(), "supermux-run-"))
  try {
    const r = await script("require('fs').writeFileSync('marker.txt', process.env.SUPERMUX_RUN_X ?? ''); process.stdout.write('x'.repeat(5000))", {
      cwd: dir, env: { ...process.env, SUPERMUX_RUN_X: "given" }, maxChars: 100,
    })
    expect(r.code).toBe(0)
    expect(readFileSync(join(dir, "marker.txt"), "utf8")).toBe("given")
    expect(r.stdout).toBe("x".repeat(100))
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
