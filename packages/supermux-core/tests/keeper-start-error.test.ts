// The live broker's keepers died at an import its checkout had not installed, and every start said only "keeper exited
// before its socket appeared". The keeper's stderr is kept next to its status, and the error quotes it. A file of its own
// (not compiled-entry.test.ts) so the Windows lane runs it too.
import { afterAll, expect, setDefaultTimeout, test } from "bun:test"
import { spawnSync } from "node:child_process"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"

setDefaultTimeout(60_000)
const work = mkdtempSync(join(tmpdir(), "core-keeper-error-"))
// Every process this test starts has exited by the time it ends (the keeper before its error is thrown, the host before
// spawnSync returns), but Windows may still hold the freshly compiled broken.exe for a moment: an antivirus scan of a new
// executable, say. The windows lane once failed here with EBUSY after the test itself passed. Retry with a growing pause;
// a folder still held after ~11 s is a real leak, and that throws.
afterAll(() => {
  for (let attempt = 1; ; attempt++) {
    try {
      rmSync(work, { recursive: true, force: true })
      return
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code
      if (attempt >= 10 || (code !== "EBUSY" && code !== "EPERM" && code !== "ENOTEMPTY")) throw error
      Bun.sleepSync(200 * attempt)
    }
  }
})

test("a keeper that dies before its socket says why: the start's error quotes the keeper's stderr", () => {
  const binary = join(work, process.platform === "win32" ? "broken.exe" : "broken")
  const host = fileURLToPath(new URL("./fixtures/broken-keeper-host.ts", import.meta.url))
  const build = spawnSync(process.execPath, ["build", "--compile", host, "--outfile", binary], { encoding: "utf8" })
  expect(build.status, build.stderr).toBe(0)
  const state = mkdtempSync(join(work, "state-"))
  const run = spawnSync(binary, [], { encoding: "utf8", timeout: 30_000, env: { PATH: process.env.PATH ?? "", STATE_DIR: state } })
  expect(run.stdout).toContain("keeper exited before its socket appeared")
  expect(run.stdout).toContain("Cannot find package '@supermux/platform'")
})
