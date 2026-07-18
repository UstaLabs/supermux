import { afterEach, describe, expect, test } from "bun:test"
import { mkdtemp, readdir, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { isAbsolute, join } from "node:path"
import { acquireSessiondLock, parseSessiondArgs, sessiondLockEndpoint } from "./main"

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

  test("kernel lock rejects a live owner, leaves no artifact, and recovers on handle close", async () => {
    const dir = await temp()
    const first = await acquireSessiondLock(dir)
    expect((await readdir(dir)).filter(name => name.startsWith("sessiond.lock"))).toEqual([])
    await expect(acquireSessiondLock(dir)).rejects.toThrow("already running")
    await first.release()
    const recovered = await acquireSessiondLock(dir)
    await recovered.release()
    expect((await readdir(dir)).filter(name => name.startsWith("sessiond.lock"))).toEqual([])
  })

  test("derives a stable bounded Windows named-pipe lock distinct per state directory", async () => {
    const dir = await temp()
    const endpoint = sessiondLockEndpoint(dir)
    expect(endpoint).toStartWith("\\\\.\\pipe\\supermux-sessiond-")
    expect(endpoint).toEndWith("-lock")
    expect(endpoint.length).toBeLessThan(120)
    expect(endpoint).not.toBe(sessiondLockEndpoint(join(dir, "other")))
  })

  test("concurrent contenders deterministically elect exactly one owner", async () => {
    const dir = await temp()
    const settled = await Promise.allSettled(Array.from({ length: 12 }, () => acquireSessiondLock(dir)))
    const winners = settled.filter((result): result is PromiseFulfilledResult<Awaited<ReturnType<typeof acquireSessiondLock>>> => result.status === "fulfilled")
    const losers = settled.filter(result => result.status === "rejected")
    expect(winners).toHaveLength(1)
    expect(losers).toHaveLength(11)
    for (const loser of losers) expect(String((loser as PromiseRejectedResult).reason)).toContain("already running")
    await winners[0]!.value.release()
    const successor = await acquireSessiondLock(dir)
    await successor.release()
  })

  test("kernel automatically releases ownership when the owner process dies", async () => {
    if (process.platform !== "linux") return
    const dir = await temp()
    const moduleUrl = new URL("./main.ts", import.meta.url).href
    const script = `const m=await import(${JSON.stringify(moduleUrl)});await m.acquireSessiondLock(process.env.MUX_LOCK_TEST_DIR,{platform:"linux"});process.stdout.write("ready\\n");await new Promise(()=>{})`
    const child = Bun.spawn([process.execPath, "-e", script], {
      env: { ...process.env, MUX_LOCK_TEST_DIR: dir },
      stdout: "pipe",
      stderr: "pipe",
    })
    const reader = child.stdout.getReader()
    let output = ""
    while (!output.includes("ready\n")) {
      const result = await reader.read()
      if (result.done) throw new Error(`lock child exited before ready: ${await new Response(child.stderr).text()}`)
      output += new TextDecoder().decode(result.value)
    }
    await expect(acquireSessiondLock(dir)).rejects.toThrow("already running")
    child.kill()
    await child.exited
    const recovered = await acquireSessiondLock(dir)
    await recovered.release()
  })
})
