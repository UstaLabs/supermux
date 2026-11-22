// #156: the per-connection peer check on the session socket (log vs enforce).
import { afterEach, expect, test } from "bun:test"
import { connect } from "node:net"
import { mkdtempSync, rmSync, unlinkSync, writeFileSync } from "node:fs"
import { host } from "@supermux/platform"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { encodeFrame, decodeFrames } from "../../shared/frame-codec"
import { localEndpoint } from "../local-endpoint"
import { socketsDirInUse, startSocketServer, type PeerAuth } from "./socket-server"

const cleanup: Array<() => Promise<void> | void> = []
afterEach(async () => { for (const c of cleanup.splice(0)) await c() })

async function setup(peerAuth: PeerAuth) {
  const dir = mkdtempSync(join(tmpdir(), "sm-sock-"))
  const ops: string[] = []
  const server = await startSocketServer({
    socketsDir: dir, peerAuth,
    handler: {
      onRegister: async (m) => ({ name: "s", session_id: m.session_id }),
      onOutbound: async () => ({ ok: true }),
      onOrchestration: async (m) => { ops.push(m.op.name); return { ok: true, value: "ran" } },
    },
  })
  await server.bind("sess")
  cleanup.push(() => server.close(), () => rmSync(dir, { recursive: true, force: true }))
  const ask = (name: string) => new Promise<any>((res, rej) => {
    const s = connect(localEndpoint("sess", { socketsDir: dir }))
    let buf: Buffer = Buffer.alloc(0)
    s.on("error", rej)
    s.on("connect", () => s.write(encodeFrame({ kind: "orchestration", call_id: "cli-1", op: { name, args: {} } })))
    s.on("data", (c: Buffer) => { buf = Buffer.concat([buf, c]); for (const m of decodeFrames(buf).messages as any[]) if (m.kind === "result") { s.end(); res(m) } })
  })
  return { ops, ask }
}

test("enforce: a process that isn't the session's gets every op refused with the reason, and nothing runs", async () => {
  const t = await setup({ mode: "enforce", check: async () => ({ ok: false, why: "process 42 is not part of this session", peer: { pid: 42, uid: 1000 } }) })
  const r = await t.ask("list_sessions")
  expect(r.ok).toBe(false)
  expect(r.error).toStartWith("not allowed: process 42 is not part of this session")
  expect(t.ops).toEqual([])
})

test("log (the rollout default): the would-be denial is only logged; the op runs", async () => {
  const t = await setup({ mode: "log", check: async () => ({ ok: false, why: "nope", peer: null }) })
  expect((await t.ask("list_sessions")).value).toBe("ran")
  expect(t.ops).toEqual(["list_sessions"])
})

test("enforce, the session's own process: runs; a check that throws lets the op through (logged)", async () => {
  const ok = await setup({ mode: "enforce", check: async () => ({ ok: true }) })
  expect((await ok.ask("list_sessions")).value).toBe("ran")
  const broken = await setup({ mode: "enforce", check: async () => { throw new Error("ffi missing") } })
  expect((await broken.ask("list_sessions")).value).toBe("ran")
})

test("the operator socket: a checked caller's ops go to the operator handler with its peer; a refused one gets the reason (#156)", async () => {
  const dir = mkdtempSync(join(tmpdir(), "sm-op-"))
  let allow = true
  const handled: Array<{ op: string; pid: number }> = []
  const server = await startSocketServer({
    socketsDir: dir,
    handler: { onRegister: async (m) => ({ name: "s", session_id: m.session_id }), onOutbound: async () => ({ ok: true }), onOrchestration: async () => ({ ok: true }) },
    operator: {
      check: async () => (allow ? { ok: true, peer: { pid: 7, uid: 1000 } } : { ok: false, why: 'process 7 is part of session "X"', peer: { pid: 7, uid: 1000 } }),
      handle: async (op, peer) => { handled.push({ op: op.name, pid: peer.pid }); return { ok: true, value: "owner ran it" } },
    },
  })
  cleanup.push(() => server.close(), () => rmSync(dir, { recursive: true, force: true }))
  const ask = () => new Promise<any>((res) => {
    const s = connect(localEndpoint("operator", { socketsDir: dir }))
    let buf: Buffer = Buffer.alloc(0)
    s.on("connect", () => s.write(encodeFrame({ kind: "orchestration", call_id: "cli-1", op: { name: "list_sessions", args: {} } })))
    s.on("data", (c: Buffer) => { buf = Buffer.concat([buf, c]); for (const m of decodeFrames(buf).messages as any[]) if (m.kind === "result") { s.end(); res(m) } })
  })
  expect((await ask()).value).toBe("owner ran it")
  expect(handled).toEqual([{ op: "list_sessions", pid: 7 }])
  allow = false
  const refused = await ask()
  expect(refused.ok).toBe(false)
  expect(refused.error).toContain('not allowed: process 7 is part of session "X"')
})

function operatorServer(dir: string, answer: string, healMs?: number) {
  return startSocketServer({
    socketsDir: dir,
    handler: { onRegister: async (m) => ({ name: "s", session_id: m.session_id }), onOutbound: async () => ({ ok: true }), onOrchestration: async () => ({ ok: true }) },
    operator: { check: async () => ({ ok: true, peer: { pid: 7, uid: 1000 } }), handle: async () => ({ ok: true, value: answer }) },
    operatorHealMs: healMs,
  })
}
const askOperator = (dir: string) => new Promise<any>((res) => {
  const s = connect(localEndpoint("operator", { socketsDir: dir }))
  let buf: Buffer = Buffer.alloc(0)
  s.on("error", (e) => res({ error: e.message }))
  s.on("connect", () => s.write(encodeFrame({ kind: "orchestration", call_id: "cli-1", op: { name: "list_sessions", args: {} } })))
  s.on("data", (c: Buffer) => { buf = Buffer.concat([buf, c]); for (const m of decodeFrames(buf).messages as any[]) if (m.kind === "result") { s.end(); res(m) } })
})

test("a second broker on the same sockets dir never takes the operator socket from a live one (#156, 2026-11-22)", async () => {
  const dir = mkdtempSync(join(tmpdir(), "sm-op2-"))
  const live = await operatorServer(dir, "live")
  const second = await operatorServer(dir, "second")
  cleanup.push(() => live.close(), () => rmSync(dir, { recursive: true, force: true }))
  expect((await askOperator(dir)).value).toBe("live")
  await second.close()
  expect((await askOperator(dir)).value).toBe("live")
})

test.skipIf(host.os() === "win32")("the live broker binds its operator socket again when the file is gone or left behind by a broker that exited (#156)", async () => {
  const dir = mkdtempSync(join(tmpdir(), "sm-op3-"))
  const live = await operatorServer(dir, "live", 30)
  cleanup.push(() => live.close(), () => rmSync(dir, { recursive: true, force: true }))
  const path = localEndpoint("operator", { socketsDir: dir })
  const until = async (ok: () => Promise<boolean>) => { for (let i = 0; i < 100; i++) { if (await ok()) return; await new Promise((r) => setTimeout(r, 20)) } throw new Error("timed out") }
  unlinkSync(path)
  await until(async () => (await askOperator(dir)).value === "live")
  unlinkSync(path)
  writeFileSync(path, "") // a file nothing answers at
  await until(async () => (await askOperator(dir)).value === "live")
})

test("a sockets dir is in use while a broker answers there, not when its file is left behind (the boot guard, #156)", async () => {
  const dir = mkdtempSync(join(tmpdir(), "sm-op4-"))
  cleanup.push(() => rmSync(dir, { recursive: true, force: true }))
  expect(await socketsDirInUse(dir)).toBe(false)
  const live = await operatorServer(dir, "live")
  expect(await socketsDirInUse(dir)).toBe(true)
  await live.close()
  writeFileSync(localEndpoint("operator", { socketsDir: dir }), "")
  expect(await socketsDirInUse(dir)).toBe(false)
})
