import { expect, test } from "bun:test"
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { lock } from "@supermux/platform"
import { claimBroker } from "./broker-instance"

// #36's regression. A second broker took broker.pid (it misjudged the first as dead), died on .core.lock with the
// port in use, and on exit removed its own claim: the first broker ran on with no pid file, and every later start
// skipped the check. With the kernel lock taken first, the second stops before it touches anything.
test("a second broker on the same state stops before it touches the first one's broker.pid", async () => {
  const state = mkdtempSync(join(tmpdir(), "mux-broker-"))
  const pidFile = join(state, "broker.pid")
  const first = await lock.singleInstance("broker", state) // the first broker, alive
  writeFileSync(pidFile, "999999") // its claim, under a pid this process cannot see: the old guard called it stale
  await expect(claimBroker(state, pidFile)).rejects.toMatchObject({ code: "already_running" })
  expect(readFileSync(pidFile, "utf8")).toBe("999999")

  await first.release() // the first broker is gone: the next one takes over, stale claim and all
  const claimed = await claimBroker(state, pidFile)
  expect(readFileSync(pidFile, "utf8").trim()).toBe(String(process.pid))
  await claimed.release()
  expect(existsSync(pidFile)).toBe(false)
  const again = await lock.singleInstance("broker", state) // and the lock is free
  await again.release()
})
