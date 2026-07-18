import { createServer, Server, Socket } from "net"
import { mkdirSync, chmodSync, existsSync, unlinkSync } from "fs"
import { localEndpoint } from "../local-endpoint"
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

export type SocketServer = {
  bind: (session_id: string) => Promise<void>
  sendInbound: (session_id: string, payload: { content: string; meta: Record<string, string> }) => Promise<void>
  close: () => Promise<void>
}

export async function startSocketServer(opts: {
  socketsDir: string
  handler: ServerHandler
  onStatusChange?: (session_id: string, connected: boolean, last_pong_at?: number) => void
  // Fired when a queued inbound can't be handed to a live channel shim within
  // deliveryGraceMs (the session crashed / never came up). Lets the broker
  // surface "couldn't deliver" to the user instead of silently dropping it.
  onUndeliverable?: (session_id: string, payload: { content: string; meta: Record<string, string> }) => void
  deliveryGraceMs?: number
}): Promise<SocketServer> {
  if (process.platform !== "win32") {
    mkdirSync(opts.socketsDir, { recursive: true, mode: 0o700 })
  }
  // A single Claude session has MORE THAN ONE shim connection on the same
  // session_id: Claude loads mux-shim both as a tools MCP server
  // (~/.claude.json) and as a channel provider
  // (--dangerously-load-development-channels). Only the channel one surfaces
  // inbound to Claude, but they register identically so the broker can't tell
  // them apart — so we track ALL connections per session and deliver inbound to
  // every live one. (Keying a single Socket here used to drop inbound whenever
  // the tools shim won the racy registration order.)
  const conns = new Map<string, Set<Socket>>()
  // Inbound must reach the mux-channel shim only; the tools shim shares the
  // session socket but does not surface channel notifications to Claude.
  const channelConns = new Map<string, Set<Socket>>()
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

  // Single-flight dedup for orchestration calls. A Claude session runs TWO shim
  // processes (tools + channel), both advertising the orchestration tools, so one
  // agent tool-call reaches the broker twice (different call_ids). Idempotent ops
  // (reply) collapse downstream, but spawn_session would create two sessions.
  // Keyed by session+op+args: a duplicate within the window shares the first
  // call's result instead of re-running. Each call_id still gets its own reply.
  const ORCH_DEDUP_MS = 10_000
  const orchInflight = new Map<string, Promise<{ ok: boolean; value?: unknown; error?: string }>>()

  // Live = present, not destroyed, still writable. Writing to a destroyed socket
  // returns false silently and the frame is lost, so we filter first and fall
  // back to the queue when nothing is live.
  function liveConns(session_id: string): Socket[] {
    const set = conns.get(session_id)
    if (!set) return []
    return [...set].filter(s => s.writable && !s.destroyed)
  }

  const QUEUE_CAP = 20
  const QUEUE_DROP_AFTER_MS = 5 * 60 * 1000

  type Queued = { content: string; meta: Record<string, string>; ts: number }
  const queues = new Map<string, Queued[]>()
  const queueStart = new Map<string, number>()
  const DELIVERY_GRACE_MS = opts.deliveryGraceMs ?? 15_000
  const deliveryTimers = new Map<string, ReturnType<typeof setTimeout>>()

  // If a queued message isn't flushed to a live channel shim within the grace
  // window, the session didn't come up — notify the broker (which tells the user)
  // and drop the now-undeliverable queue (a re-send is the recovery).
  function scheduleUndeliverable(session_id: string): void {
    if (!opts.onUndeliverable || deliveryTimers.has(session_id)) return
    const t = setTimeout(() => {
      deliveryTimers.delete(session_id)
      const q = queues.get(session_id)
      if (!q || q.length === 0) return  // already flushed/delivered
      const sample = q[q.length - 1]!
      queues.delete(session_id)
      queueStart.delete(session_id)
      log.warn("inbound_undeliverable", { session_id, dropped: q.length, preview: sample.content.slice(0, 60) })
      opts.onUndeliverable!(session_id, { content: sample.content, meta: sample.meta })
    }, DELIVERY_GRACE_MS)
    t.unref?.()
    deliveryTimers.set(session_id, t)
  }

  function clearDeliveryTimer(session_id: string): void {
    const t = deliveryTimers.get(session_id)
    if (t) { clearTimeout(t); deliveryTimers.delete(session_id) }
  }

  function enqueueInbound(session_id: string, payload: Queued): "queued" | "dropped" | "expired" {
    if (!queues.has(session_id)) {
      queues.set(session_id, [])
      queueStart.set(session_id, Date.now())
    }
    if (Date.now() - (queueStart.get(session_id) ?? 0) > QUEUE_DROP_AFTER_MS) {
      queues.set(session_id, [])
      queueStart.set(session_id, Date.now())
      return "expired"
    }
    const q = queues.get(session_id)!
    if (q.length >= QUEUE_CAP) q.shift()  // drop oldest
    q.push(payload)
    return "queued"
  }

  function inboundTargets(session_id: string): Socket[] {
    const channel = channelConns.get(session_id)
    if (!channel?.size) return []
    return [...channel].filter(s => s.writable && !s.destroyed)
  }

  function flushQueue(session_id: string) {
    const q = queues.get(session_id)
    if (!q || q.length === 0) return
    const live = inboundTargets(session_id)
    if (live.length === 0) return
    log.info("inbound_queue_flush", {
      session_id,
      queued: q.length,
      channel_conns: channelConns.get(session_id)?.size ?? 0,
      total_conns: liveConns(session_id).length,
      preview: q[0]?.content.slice(0, 60),
    })
    for (const p of q) {
      const frame = encodeFrame({ kind: "inbound", content: p.content, meta: p.meta })
      for (const conn of live) conn.write(frame)
    }
    queues.delete(session_id)
    queueStart.delete(session_id)
    clearDeliveryTimer(session_id)  // delivered — cancel the undeliverable alarm
  }

  async function bindOne(session_id: string): Promise<void> {
    const sockPath = localEndpoint(session_id, { socketsDir: opts.socketsDir })
    if (process.platform !== "win32" && existsSync(sockPath)) unlinkSync(sockPath)
    const s = createServer(socket => handleConnection(session_id, socket))
    await new Promise<void>((res, rej) => {
      s.once("error", rej)
      s.listen(sockPath, () => {
        if (process.platform !== "win32") chmodSync(sockPath, 0o600)
        res()
      })
    })
    servers.set(session_id, s)
  }

  function trackChannelConn(session_id: string, socket: Socket, channel_only: boolean) {
    if (!channel_only) return
    let set = channelConns.get(session_id)
    if (!set) { set = new Set(); channelConns.set(session_id, set) }
    set.add(socket)
  }

  function untrackChannelConn(session_id: string, socket: Socket) {
    const set = channelConns.get(session_id)
    if (!set) return
    set.delete(socket)
    if (set.size === 0) channelConns.delete(session_id)
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
          const reply = await opts.handler.onRegister({ ...m, session_id })
          socket.write(encodeFrame({ kind: "registered", display_name: reply.name, session_id: reply.session_id }))
          // Liveness (lastPong + onStatusChange) is handled by markAlive above.
          if (m.channel_only) {
            trackChannelConn(session_id, socket, true)
            flushQueue(session_id)
          }
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
              const key = `${session_id}|${m.op.name}|${JSON.stringify(m.op.args)}`
              let p = orchInflight.get(key)
              if (p) {
                log.info("broker_call_deduped", { session_id, call_id: m.call_id, op_name: m.op.name })
              } else {
                p = opts.handler.onOrchestration({ ...m, session_id })
                orchInflight.set(key, p)
                void p.finally(() => setTimeout(() => orchInflight.delete(key), ORCH_DEDUP_MS))
              }
              r = await p
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
      untrackChannelConn(session_id, socket)
      const set = conns.get(session_id)
      let remaining = 0
      if (set) {
        set.delete(socket)
        remaining = set.size
        if (remaining === 0) conns.delete(session_id)
      }
      // Only report disconnected when the LAST connection for the session is
      // gone — otherwise closing the tools shim would falsely mark a session
      // (still live on its channel shim) as disconnected.
      if (remaining === 0) opts.onStatusChange?.(session_id, false)
    })
    socket.on("error", (e) => log.warn("socket_error", { session_id, err: e.message }))
  }

  // Broker side does not pre-bind: a session_id is bound when broker spawns
  // that session. Outer code calls `bind(session_id)` before launching claude.
  // For the test, we eagerly bind on first send.
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
    async sendInbound(session_id, payload) {
      await ensureBound(session_id)
      const live = inboundTargets(session_id)
      if (live.length === 0) {
        // No channel shim yet (tools may be connected but cannot deliver inbound).
        const qResult = enqueueInbound(session_id, { ...payload, ts: Date.now() })
        log.info("inbound_queued", {
          session_id,
          result: qResult,
          depth: queues.get(session_id)?.length ?? 0,
          preview: payload.content.slice(0, 60),
        })
        scheduleUndeliverable(session_id)
        return
      }
      const frame = encodeFrame({ kind: "inbound", ...payload })
      for (const conn of live) conn.write(frame)
    },
    async close() {
      for (const t of deliveryTimers.values()) clearTimeout(t)
      deliveryTimers.clear()
      for (const set of conns.values()) for (const s of set) s.destroy()
      for (const srv of servers.values()) await new Promise<void>(r => srv.close(() => r()))
      conns.clear()
      servers.clear()
    },
  }
}
