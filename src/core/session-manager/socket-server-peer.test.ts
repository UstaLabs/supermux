// #156: the per-connection peer check on the session socket (log vs enforce).
import { afterEach, expect, test } from "bun:test"
import { connect } from "node:net"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { encodeFrame, decodeFrames } from "../../shared/frame-codec"
import { localEndpoint } from "../local-endpoint"
import { startSocketServer, type PeerAuth } from "./socket-server"

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
