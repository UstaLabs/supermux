import { afterEach, describe, expect, test } from "bun:test"
import { mkdtemp, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { isAbsolute, join } from "node:path"
import { acquireSessiondLock, parseSessiondArgs } from "./main"

const dirs: string[] = []
afterEach(async () => Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true }))))
async function temp(): Promise<string> { const dir = await mkdtemp(join(tmpdir(), "sessiond-main-")); dirs.push(dir); return dir }

describe("sessiond main", () => {
  test("parses a robust absolute --state-dir", () => {
    const parsed = parseSessiondArgs(["--state-dir", "relative-state"])
    expect(isAbsolute(parsed.stateDir)).toBe(true)
    expect(() => parseSessiondArgs(["--state-dir"])).toThrow("requires a value")
    expect(() => parseSessiondArgs(["--unknown"])).toThrow("unknown argument")
    expect(() => parseSessiondArgs(["--state-dir=a", "--state-dir=b"])).toThrow("specified more than once")
  })

  test("single-instance lock rejects a live owner and recovers a stale one", async () => {
    const dir = await temp()
    const first = await acquireSessiondLock(dir)
    await expect(acquireSessiondLock(dir)).rejects.toThrow("already running")
    await first.release()

    await writeFile(join(dir, "sessiond.lock"), JSON.stringify({ pid: 999_999_999, token: "stale" }), { mode: 0o600 })
    const recovered = await acquireSessiondLock(dir)
    await recovered.release()
  })
})
