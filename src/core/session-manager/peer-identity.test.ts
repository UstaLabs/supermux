import { afterAll, expect, test } from "bun:test"
import { createServer, type Socket } from "node:net"
import { spawn } from "node:child_process"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { checkPeer, descendsFrom, parentOf, peerOf } from "./peer-identity"

const posix = process.platform === "linux" || process.platform === "darwin"
const dir = mkdtempSync(join(tmpdir(), "sm-peer-"))
afterAll(() => rmSync(dir, { recursive: true, force: true }))

/** Accept one connection from a child `bun` process and hand the server-side socket to `fn`. */
async function withChildPeer<T>(fn: (s: Socket, childPid: number) => Promise<T>): Promise<T> {
  const path = join(dir, `${Math.random().toString(36).slice(2)}.sock`)
  let resolve!: (v: T) => void, reject!: (e: unknown) => void
  const done = new Promise<T>((a, b) => { resolve = a; reject = b })
  let child: ReturnType<typeof spawn>
  const srv = createServer((s) => { fn(s, child.pid!).then(resolve, reject).finally(() => { s.end(); srv.close() }) })
  await new Promise<void>((r) => srv.listen(path, () => r()))
  child = spawn(process.execPath, ["-e", `require("net").connect(${JSON.stringify(path)}).on("end",()=>process.exit(0))`], { stdio: "ignore" })
  return done
}

test.if(posix)("the kernel names the connecting process and its user", async () => {
  const r = await withChildPeer(async (s, childPid) => ({ peer: await peerOf(s), childPid }))
  expect(r.peer).toEqual({ pid: r.childPid, uid: process.getuid!() })
})

test.if(posix)("a child of the session's root passes; the same process under another root does not", async () => {
  const ok = await withChildPeer((s) => checkPeer(s, process.pid))
  expect(ok.ok).toBe(true)
  const other = spawn("sleep", ["5"], { stdio: "ignore" })
  try {
    const bad = await withChildPeer((s) => checkPeer(s, other.pid!))
    expect(bad.ok).toBe(false)
    expect(bad.why).toContain("is not part of this session")
  } finally { other.kill() }
})

test.if(posix)("another OS user is refused outright; an unknown root passes with a reason", async () => {
  const foreign = await withChildPeer((s) => checkPeer(s, process.pid, { uid: 12345 }))
  expect(foreign).toMatchObject({ ok: false })
  expect(foreign.why).toContain("another OS user")
  const unknown = await withChildPeer((s) => checkPeer(s, undefined))
  expect(unknown).toMatchObject({ ok: true, why: "session root unknown" })
})

test.if(posix)("parents: this process descends from its parent, not from an unrelated process", async () => {
  expect((await parentOf(process.pid))?.ppid).toBe(process.ppid)
  expect(await descendsFrom(process.pid, process.ppid)).toBe(true)
  const other = spawn("sleep", ["5"], { stdio: "ignore" })
  try { expect(await descendsFrom(process.pid, other.pid!)).toBe(false) } finally { other.kill() }
})

test("an OS without a peer lookup yet (Windows until its spike) passes, saying why", async () => {
  const fake = { _handle: { fd: 3 } } as unknown as Socket
  expect(await checkPeer(fake, 1, { platform: "win32" })).toEqual({ ok: true, peer: null, why: "peer unknown on win32" })
})
