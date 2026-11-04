import { expect, test } from "bun:test"
import { spawn } from "node:child_process"
import { mkdtempSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { lock } from "@supermux/platform"

const scope = () => mkdtempSync(join(tmpdir(), "mux-lock-"))

test("a second holder is refused while the first holds the lock, and gets it once released", async () => {
  const dir = scope()
  const first = await lock.singleInstance("test", dir)
  await expect(lock.singleInstance("test", dir)).rejects.toMatchObject({ code: "already_running" })
  await first.release()
  const second = await lock.singleInstance("test", dir)
  await second.release()
})

test("each name and each scope is its own lock", async () => {
  const dir = scope()
  const a = await lock.singleInstance("a", dir)
  const b = await lock.singleInstance("b", dir)
  const c = await lock.singleInstance("a", scope())
  await Promise.all([a.release(), b.release(), c.release()])
})

// The point of a kernel-owned lock: the OS drops it with its owner, so a crash never leaves it stale and a reused
// pid never holds it (#36: .core.lock and broker.pid are files, and both got this wrong on Windows).
test("the lock is free again the moment its holder dies", async () => {
  const dir = scope()
  const child = spawn(process.execPath, ["-e", `
    const { lock } = await import("@supermux/platform")
    await lock.singleInstance("test", ${JSON.stringify(dir)})
    console.log("held")
    setInterval(() => {}, 1000)
  `], { stdio: ["ignore", "pipe", "inherit"] })
  await new Promise<void>((resolve) => child.stdout!.on("data", (d) => { if (String(d).includes("held")) resolve() }))
  await expect(lock.singleInstance("test", dir)).rejects.toMatchObject({ code: "already_running" })
  child.kill("SIGKILL")
  await new Promise((resolve) => child.once("exit", resolve))
  const after = await lock.singleInstance("test", dir)
  await after.release()
})

// macOS has no abstract sockets and a socket file outlives a crash: there the lock is a loopback port derived from
// the name and scope, answered with an identity so a stranger on that port is never mistaken for the holder.
test("the loopback-port form (macOS) refuses a second holder too", async () => {
  const dir = scope()
  const first = await lock.singleInstance("test", dir, { platform: "darwin" })
  await expect(lock.singleInstance("test", dir, { platform: "darwin" })).rejects.toMatchObject({ code: "already_running" })
  await first.release()
  const again = await lock.singleInstance("test", dir, { platform: "darwin" })
  await again.release()
})

// sessiond took this lock before the package existed: keeping its names means an older sessiond and a newer one
// still exclude each other.
test("sessiond's endpoint names are kept", () => {
  expect(lock.pipeName("sessiond", "C:\\Users\\me\\.mux\\state")).toMatch(/^\\\\\.\\pipe\\supermux-sessiond-[0-9a-f]{32}-lock$/)
  expect(lock.pipeName("sessiond", "C:\\Users\\me\\.mux\\state")).toBe(lock.pipeName("sessiond", "c:/users/ME/.mux/state"))
})
