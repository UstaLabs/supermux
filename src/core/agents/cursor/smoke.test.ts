import { afterEach, expect, test } from "bun:test"
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, utimesSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { delimiter, join } from "path"
import { cachedCursorSmoke } from "./smoke"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

function tmp(): string {
  const d = mkdtempSync(join(tmpdir(), "cursor-smoke-"))
  dirs.push(d)
  return d
}

// #102: `cursor-agent --version` loads its whole bundle (2.6–3.4 s alone, 6–7 s in the broker under load) and ran
// on every Cursor session start. Once a binary has passed, the same binary is not asked again.
test("the cursor-agent smoke runs once per binary, again when the binary changes, and a failure is never remembered", async () => {
  const dir = tmp()
  // A .cmd shim on Windows, a shell script elsewhere: what each OS runs from PATH.
  const windows = process.platform === "win32"
  const bin = join(dir, windows ? "cursor-agent.cmd" : "cursor-agent")
  const count = join(dir, "runs")
  const runs = () => (existsSync(count) ? readFileSync(count, "utf8").trim().split("\n").length : 0)
  const install = (body: string, mtime: number) => {
    writeFileSync(bin, windows ? `@echo off\r\necho x>>"${count}"\r\n${body}\r\n` : `#!/bin/sh\necho x >> "${count}"\n${body}\n`)
    if (!windows) chmodSync(bin, 0o755)
    utimesSync(bin, new Date(mtime), new Date(mtime))
  }
  const smoke = cachedCursorSmoke()
  const opts = { home: tmp(), authEnv: { PATH: [dir, process.env.PATH ?? ""].join(delimiter) } }

  install(`echo 2026.09.18`, Date.now() - 60_000)
  await smoke(opts)
  await smoke(opts)
  expect(runs()).toBe(1)

  // An upgrade replaces the binary.
  install(`echo 2026.10.01`, Date.now() - 30_000)
  await smoke(opts)
  expect(runs()).toBe(2)

  // A broken binary fails every time.
  install(windows ? `exit /b 3` : `exit 3`, Date.now())
  await expect(smoke(opts)).rejects.toThrow(/exit 3/)
  await expect(smoke(opts)).rejects.toThrow(/exit 3/)
  expect(runs()).toBe(4)
})
