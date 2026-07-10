// End-to-end regression test for the fresh-session first-message race
// (2026-07-08): a channel notification written before Claude completes the MCP
// initialize handshake is silently discarded by Claude, so the shim must NEVER
// emit `notifications/claude/channel` before the client has initialized —
// no matter how early the broker delivers the inbound.
//
// Drives the REAL shim binary over stdio like Claude does, with a real broker
// socket, and a deliberately SLOW client: inbound arrives long before
// `initialize`. The old timer-based flush emitted into that pre-init window.
import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { Subprocess } from "bun"
import { startSocketServer, type SocketServer } from "../core/session-manager/socket-server"

const SESSION_ID = "gate-e2e-session"
const GRACE_MS = 100

let dir: string
let server: SocketServer
let child: Subprocess<"pipe", "pipe", "pipe">
let registered: Promise<void>

type Rpc = { jsonrpc: "2.0"; id?: number; method?: string; result?: unknown; params?: any }
const seen: { msg: Rpc; at: number }[] = []
const waiters: { pred: (m: Rpc) => boolean; resolve: (m: Rpc) => void }[] = []

function onLine(line: string) {
  if (!line.trim()) return
  let msg: Rpc
  try { msg = JSON.parse(line) } catch { return }
  seen.push({ msg, at: Date.now() })
  for (let i = waiters.length - 1; i >= 0; i--) {
    if (waiters[i]!.pred(msg)) waiters.splice(i, 1)[0]!.resolve(msg)
  }
}

function nextMessage(pred: (m: Rpc) => boolean, timeoutMs: number): Promise<Rpc> {
  const already = seen.find(s => pred(s.msg))
  if (already) return Promise.resolve(already.msg)
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error("timed out waiting for message")), timeoutMs)
    waiters.push({ pred, resolve: (m) => { clearTimeout(t); resolve(m) } })
  })
}

function send(msg: Rpc) {
  child.stdin.write(JSON.stringify(msg) + "\n")
}

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), "mux-gate-e2e-"))
  let onReg: () => void
  registered = new Promise<void>(r => { onReg = r })
  server = await startSocketServer({
    socketsDir: dir,
    handler: {
      onRegister: async () => { onReg!(); return { name: "gate-e2e", session_id: SESSION_ID } },
      onOutbound: async () => ({ ok: true }),
      onOrchestration: async () => ({ ok: true }),
    },
  })
  await server.bind(SESSION_ID)

  child = Bun.spawn(["bun", "run", join(import.meta.dir, "index.ts")], {
    stdin: "pipe", stdout: "pipe", stderr: "pipe",
    env: {
      ...process.env,
      MUX_SOCKETS_DIR: dir,
      MUX_SESSION_ID: SESSION_ID,
      MUX_CHANNEL_ONLY: "1",
      MUX_CHANNEL_INJECT_GRACE_MS: String(GRACE_MS),
    },
  })
  void (async () => {
    const decoder = new TextDecoder()
    let buf = ""
    for await (const chunk of child.stdout) {
      buf += decoder.decode(chunk)
      const lines = buf.split("\n")
      buf = lines.pop() ?? ""
      for (const l of lines) onLine(l)
    }
  })()
})

afterAll(() => {
  child?.kill()
  void server?.close()
  rmSync(dir, { recursive: true, force: true })
})

describe("channel inject gate (e2e over real shim + stdio)", () => {
  test("inbound delivered pre-initialize is held, then injected once the client initializes", async () => {
    await registered

    // Broker delivers the first message the moment the session registers —
    // exactly what the PWA's create-then-send flow does.
    await server.sendInbound(SESSION_ID, { content: "first message", meta: { chat_id: "web" } })

    // Client (Claude) is slow: no initialize yet. The shim must stay silent —
    // anything emitted now would be discarded by the real client. The window
    // is deliberately longer than the old 2s wall-clock fallback so this test
    // FAILS on any reintroduction of time-based flushing.
    await new Promise(r => setTimeout(r, 2_500))
    expect(seen.filter(s => s.msg.method === "notifications/claude/channel")).toEqual([])

    // Client finally completes the MCP handshake.
    const initSentAt = Date.now()
    send({
      jsonrpc: "2.0", id: 1, method: "initialize",
      params: { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "e2e", version: "0" } },
    })
    await nextMessage(m => m.id === 1 && m.result !== undefined, 5_000)
    send({ jsonrpc: "2.0", method: "notifications/initialized" })

    // The held message arrives — after initialized (+grace), never before.
    const notif = await nextMessage(m => m.method === "notifications/claude/channel", 5_000)
    expect(notif.params?.content).toBe("first message")
    const at = seen.find(s => s.msg === notif)!.at
    expect(at).toBeGreaterThanOrEqual(initSentAt + GRACE_MS)

    // Steady state: once open, later messages flow straight through.
    await server.sendInbound(SESSION_ID, { content: "second message", meta: { chat_id: "web" } })
    const second = await nextMessage(m => m.method === "notifications/claude/channel" && m.params?.content === "second message", 5_000)
    expect(second.params?.content).toBe("second message")
  }, 20_000)
})
