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
afterAll(() => rmSync(work, { recursive: true, force: true }))

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
