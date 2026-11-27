import { expect, mock, test } from "bun:test"
import * as realNet from "node:net"
import { mkdtempSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"

// Windows keeps whole port ranges for itself (`netsh int ipv4 show excludedportrange`): a bind there fails with EACCES
// although nothing listens. The loopback-port lock must take its next candidate, as for a port another program holds
// (#166: lock.test.ts failed on the Windows lane whenever a candidate fell in such a range).
const net = { ...realNet }
const refused = new Set<number>()
const tried: number[] = []
mock.module("node:net", () => ({
  ...net,
  createServer: (...args: Parameters<typeof net.createServer>) => {
    const server = net.createServer(...args)
    const listen = server.listen.bind(server)
    server.listen = ((options: realNet.ListenOptions, callback?: () => void) => {
      if (typeof options === "object" && options.port !== undefined) tried.push(options.port)
      if (typeof options === "object" && options.port !== undefined && refused.has(options.port)) {
        queueMicrotask(() => server.emit("error", Object.assign(new Error(`listen EACCES: permission denied 127.0.0.1:${options.port}`), { code: "EACCES" })))
        return server
      }
      return listen(options, callback)
    }) as typeof server.listen
    return server
  },
}))
const lock = await import("../src/lock")

test("a port the OS refuses is passed over like a taken one; a second holder is still refused", async () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-lock-"))
  // Learn the first candidate, then have the OS refuse it.
  const probe = await lock.singleInstance("test", dir, { platform: "darwin" })
  await probe.release()
  expect(tried.length).toBe(1)
  refused.add(tried[0]!)
  const holder = await lock.singleInstance("test", dir, { platform: "darwin" })
  await expect(lock.singleInstance("test", dir, { platform: "darwin" })).rejects.toMatchObject({ code: "already_running" })
  await holder.release()
})
