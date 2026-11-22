// #156, the Windows CI pin: session sockets are AF_UNIX files on Windows (so the broker can ask the kernel who
// connected), and each session's pre-#156 named pipe is still served for agents started before the update.
import { afterEach, expect, test } from "bun:test"
import { connect } from "node:net"
import { spawn } from "node:child_process"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { host } from "@supermux/platform"
import { encodeFrame, decodeFrames } from "../../shared/frame-codec"
import { legacyPipeEndpoint, localEndpoint } from "../local-endpoint"
import { startSocketServer } from "./socket-server"
import { checkPeer } from "./peer-identity"

const windows = host.os() === "win32"
const cleanup: Array<() => Promise<void> | void> = []
afterEach(async () => { for (const c of cleanup.splice(0)) await c() })

const SID = "cccccccc-1111-4222-8333-444444444444"
async function server(peerAuth?: Parameters<typeof startSocketServer>[0]["peerAuth"]) {
  const dir = mkdtempSync(join(tmpdir(), "sm-win-"))
  const s = await startSocketServer({ socketsDir: dir, peerAuth, handler: {
    onRegister: async (m) => ({ name: "w", session_id: m.session_id }),
    onOutbound: async () => ({ ok: true }),
    onOrchestration: async (m) => ({ ok: true, value: m.op.name }),
  } })
  await s.bind(SID)
  cleanup.push(() => s.close(), () => rmSync(dir, { recursive: true, force: true }))
  return dir
}
const ask = (endpoint: string) => new Promise<any>((res, rej) => {
  const s = connect(endpoint)
  let buf: Buffer = Buffer.alloc(0)
  s.on("error", rej)
  s.on("connect", () => s.write(encodeFrame({ kind: "orchestration", call_id: "c1", op: { name: "list_sessions", args: {} } })))
  s.on("data", (c: Buffer) => { buf = Buffer.concat([buf, c]); for (const m of decodeFrames(buf).messages as any[]) if (m.kind === "result") { s.end(); res(m) } })
})

test.if(windows)("a session is served on its AF_UNIX file AND on its pre-#156 named pipe", async () => {
  const dir = await server()
  const file = localEndpoint(SID, { socketsDir: dir })
  expect(file).toBe(join(dir, `${SID}.sock`))
  expect((await ask(file)).value).toBe("list_sessions")
  expect((await ask(legacyPipeEndpoint(SID))).value).toBe("list_sessions")
})

test.if(windows)("on the AF_UNIX file the kernel names the connecting process: a child passes, an unrelated root does not", async () => {
  const verdicts: Array<{ ok: boolean; why?: string; peer?: unknown }> = []
  let root = process.pid
  const dir = await server({ mode: "enforce", check: async (_id, socket) => { const v = await checkPeer(socket, root, { rootSubreaper: false }); verdicts.push(v); return v } })
  const file = localEndpoint(SID, { socketsDir: dir })
  const child = (path: string) => new Promise<number>((res) => {
    const p = spawn(process.execPath, ["-e", `const s=require("net").connect(${JSON.stringify(path)});s.on("connect",()=>s.write(Buffer.from([0,0,0,2,123,125])));setTimeout(()=>process.exit(0),500)`], { stdio: "ignore" })
    p.on("close", () => res(p.pid!))
  })
  const pid = await child(file)
  expect(verdicts[0]).toMatchObject({ ok: true, peer: { pid } })
  const other = spawn("ping", ["-n", "5", "127.0.0.1"], { stdio: "ignore" })
  root = other.pid!
  try {
    await child(file)
    expect(verdicts[1]!.ok).toBe(false)
  } finally { other.kill() }
})
