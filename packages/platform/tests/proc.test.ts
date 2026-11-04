import { expect, test } from "bun:test"
import { proc } from "@supermux/platform"

test("a live process is alive, a pid that never existed is not", () => {
  expect(proc.isAlive(process.pid)).toBe(true)
  expect(proc.isAlive(0)).toBe(false)
  expect(proc.isAlive(-1)).toBe(false)
  expect(proc.isAlive(2 ** 31 - 2)).toBe(false)
})

// #36: kill(pid, 0) answering EPERM means the process exists but belongs to someone else (on Windows another session
// or an elevated one). The broker's pid file counted it as dead and took over a live broker's claim.
test("EPERM means alive, ESRCH means gone", () => {
  const thrower = (code: string) => () => { throw Object.assign(new Error(code), { code }) }
  expect(proc.isAliveWith(1234, thrower("EPERM"))).toBe(true)
  expect(proc.isAliveWith(1234, thrower("ESRCH"))).toBe(false)
  expect(proc.isAliveWith(1234, () => true)).toBe(true)
})
