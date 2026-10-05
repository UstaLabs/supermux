import { createServer, Server, Socket } from "net"
import { mkdirSync, chmodSync, existsSync, unlinkSync } from "fs"
import { localEndpoint, usesFilesystemEndpoint } from "../local-endpoint"
import { encodeFrame, decodeFrames } from "../../shared/frame-codec"
import { makeLogger } from "../../shared/log"
import { parseSocketFrame, type OrchestrationFrame, type OutboundFrame, type RegisterFrame, type SocketFrame } from "../../shared/socket-frames"

const log = makeLogger("socket-server")

export type RegisterReply = { name: string; session_id: string }
export type OpResult = { ok: boolean; value?: unknown; error?: string }
type SessionFrame<T> = T & { session_id: string }

export type ServerHandler = {
  onRegister: (msg: SessionFrame<RegisterFrame>) => Promise<RegisterReply>
  onOutbound: (msg: SessionFrame<OutboundFrame>) => Promise<OpResult>
  onOrchestration: (msg: SessionFrame<OrchestrationFrame>) => Promise<OpResult>
}

/**
 * The per-session socket the EXTERNAL mux-shim talks to (tool calls in, results out, liveness).
 * Outbound-only since C3b: inbound user turns reach every core session through its adapter, so
 * the tmux-era inbound queue / channel delivery is gone (a `channel_only` register is accepted and
 * treated like any other connection).
 */
export type SocketServer = {
  bind: (session_id: string) => Promise<void>
  close: () => Promise<void>
}

export async function startSocketServer(opts: {
  socketsDir: string
  handler: ServerHandler
  onStatusChange?: (session_id: string, connected: boolean, last_pong_at?: number) => void
}): Promise<SocketServer> {
  const usesFilesystem = usesFilesystemEndpoint()
  if (usesFilesystem) {
    mkdirSync(opts.socketsDir, { recursive: true, mode: 0o700 })
  }
  // A session can have MORE THAN ONE shim connection on the same session_id (an external-mode
  // Claude session also starts the zero-tools `mux-channel` copy from ~/.claude.json), so every
  // connection is tracked and the session is disconnected only when the last one closes.
  const conns = new Map<string, Set<Socket>>()
  const servers = new Map<string, Server>()
  const lastPong = new Map<string, number>()
  const STALE_AFTER_MS = 45_000
  const PING_INTERVAL_MS = 15_000
  // Any frame from a shim proves liveness; only announce the false->true edge.
  function markAlive(session_id: string): void {
    const now = Date.now()
    const prev = lastPong.get(session_id)
    const wasStale = prev == null || (now - prev) > STALE_AFTER_MS
    lastPong.set(session_id, now)
    if (wasStale) opts.onStatusChange?.(session_id, true, now)
  }

  // Orchestration single-flight (identical calls within 10 s share one result) lives in the
  // shared handler (SessionManager.orchestration), so it covers this socket and the host MCP
  // server alike. Each call_id still gets its own reply.

  // Live = present, not destroyed, still writable (a write to a destroyed socket is lost silently).
  function liveConns(session_id: string): Socket[] {
    const set = conns.get(session_id)
    if (!set) return []
    return [...set].filter(s => s.writable && !s.destroyed)
  }

  async function bindOne(session_id: string): Promise<void> {
    const sockPath = localEndpoint(session_id, { socketsDir: opts.socketsDir })
    if (usesFilesystem && existsSync(sockPath)) unlinkSync(sockPath)
    const s = createServer(socket => handleConnection(session_id, socket))
    await new Promise<void>((res, rej) => {
      s.once("error", rej)
      s.listen(sockPath, () => {
        if (usesFilesystem) chmodSync(sockPath, 0o600)
        res()
      })
    })
    servers.set(session_id, s)
  }

  function handleConnection(session_id: string, socket: Socket) {
    let set = conns.get(session_id)
    if (!set) { set = new Set(); conns.set(session_id, set) }
    set.add(socket)
    let buf: Buffer = Buffer.alloc(0)
    socket.on("data", async (chunk: Buffer) => {
      buf = Buffer.concat([buf, chunk])
      const { messages, rest } = decodeFrames(buf)
      buf = rest
      for (const raw of messages) {
        let m: SocketFrame
        try {
          m = parseSocketFrame(raw)
        } catch (err) {
          log.warn("socket_frame_invalid", { session_id, err: err instanceof Error ? err.message : String(err) })
          continue
        }
        markAlive(session_id)
        if (m.kind === "register") {
          let reply: { name: string; session_id: string }
          try {
            reply = await opts.handler.onRegister({ ...m, session_id })
          } catch (err) {
            // Refused registration (unknown session id — e.g. killed in the
            // startup gap). Close the socket; the shim's reconnect loop backs
            // off and the dead window is reclaimed by the supervisor.
            log.warn("register_refused", { session_id, err: err instanceof Error ? err.message : String(err) })
            socket.end()
            return
          }
          socket.write(encodeFrame({ kind: "registered", display_name: reply.name, session_id: reply.session_id }))
          // Liveness (lastPong + onStatusChange) is handled by markAlive above.
        } else if (m.kind === "outbound" || m.kind === "orchestration") {
          // Mirror-log the shim-side timing so we can correlate even when the
          // shim is running an old build without its own instrumentation. If
          // a shim call hangs, journalctl will show:
          //   broker_call_received — broker saw the frame
          //   broker_call_dispatched — handler returned (or threw)
          //   broker_call_written — result frame written back to socket
          // Any missing step pinpoints where the round-trip actually stalled.
          const callStart = process.hrtime.bigint()
          log.info("broker_call_received", { session_id, kind: m.kind, call_id: m.call_id, op_name: m.op.name, mono_ns: callStart.toString() })
          let r: { ok: boolean; value?: unknown; error?: string }
          try {
            if (m.kind === "orchestration") {
              r = await opts.handler.onOrchestration({ ...m, session_id })
            } else {
              r = await opts.handler.onOutbound({ ...m, session_id })
            }
          } catch (err: any) {
            log.warn("broker_call_handler_threw", { session_id, kind: m.kind, call_id: m.call_id, err: String(err?.message ?? err) })
            r = { ok: false, error: `handler threw: ${err?.message ?? err}` }
          }
          const dispatchedAt = process.hrtime.bigint()
          const dispatch_ms = Number(dispatchedAt - callStart) / 1e6
          log.info("broker_call_dispatched", { session_id, kind: m.kind, call_id: m.call_id, ok: r.ok, dispatch_ms: Math.round(dispatch_ms) })
          socket.write(encodeFrame({ kind: "result", call_id: m.call_id, ...r }), (err) => {
            if (err) log.warn("broker_call_write_failed", { session_id, kind: m.kind, call_id: m.call_id, err: err.message })
            else log.info("broker_call_written", { session_id, kind: m.kind, call_id: m.call_id, mono_ns: process.hrtime.bigint().toString() })
          })
        } else if (m.kind === "ping") {
          socket.write(encodeFrame({ kind: "pong" }))
        } else if (m.kind === "pong") {
          // liveness handled by markAlive above
        }
      }
    })
    socket.on("close", () => {
      const set = conns.get(session_id)
      let remaining = 0
      if (set) {
        set.delete(socket)
        remaining = set.size
        if (remaining === 0) conns.delete(session_id)
      }
      // Only report disconnected when the LAST connection for the session is gone.
      if (remaining === 0) opts.onStatusChange?.(session_id, false)
    })
    socket.on("error", (e) => log.warn("socket_error", { session_id, err: e.message }))
  }

  // Broker side does not pre-bind: a session_id is bound when broker spawns
  // that session. Outer code calls `bind(session_id)` before launching the agent.
  async function ensureBound(session_id: string): Promise<void> {
    if (!servers.has(session_id)) await bindOne(session_id)
  }

  setInterval(() => {
    const now = Date.now()
    for (const session_id of conns.keys()) {
      const ping = encodeFrame({ kind: "ping" })
      for (const conn of liveConns(session_id)) {
        try { conn.write(ping) } catch { /* pruned via close handler */ }
      }
      const lp = lastPong.get(session_id)
      if (lp != null && (now - lp) > STALE_AFTER_MS) {
        opts.onStatusChange?.(session_id, false, lp)
      }
    }
  }, PING_INTERVAL_MS).unref()

  return {
    async bind(session_id) {
      await ensureBound(session_id)
    },
    async close() {
      for (const set of conns.values()) for (const s of set) s.destroy()
      for (const srv of servers.values()) await new Promise<void>(r => srv.close(() => r()))
      conns.clear()
      servers.clear()
    },
  }
}
