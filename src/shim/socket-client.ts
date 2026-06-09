import { connect, Socket } from "net"
import { encodeFrame, decodeFrames } from "../shared/frame-codec"
import { makeLogger } from "../shared/log"
import { parseSocketFrame, type SocketFrame, type ToolOperation } from "../shared/socket-frames"
const log = makeLogger("shim.sock")

export type ShimClientOpts = {
  socketsDir: string
  sessionId: string
  workdir: string
  pid: number
  requestedName?: string
  displayName?: string
  agentSessionId?: string
  channelOnly?: boolean
  onInbound?: (payload: { content: string; meta: Record<string, string> }) => void
}

export type ShimClient = {
  assignedName: string
  callOutbound: (op: ToolOperation) => Promise<{ ok: boolean; value?: unknown; error?: string }>
  callOrchestration: (op: ToolOperation) => Promise<{ ok: boolean; value?: unknown; error?: string }>
  close: () => Promise<void>
}

export async function connectShim(opts: ShimClientOpts): Promise<ShimClient> {
  const sockPath = `${opts.socketsDir}/${opts.sessionId}.sock`
  const pending = new Map<string, (r: { ok: boolean; value?: unknown; error?: string }) => void>()
  let registered: { name: string; session_id: string } | undefined
  let sock: Socket
  let closedByUs = false
  let counter = 0
  const BACKOFFS_MS = [1000, 2000, 4000, 8000, 16000, 30000]
  function backoffFor(attempt: number): number {
    return BACKOFFS_MS[Math.min(attempt, BACKOFFS_MS.length - 1)]!
  }

  function wireSocket(s: Socket) {
    let buf: Buffer = Buffer.alloc(0)
    s.on("data", (chunk: Buffer) => {
      buf = Buffer.concat([buf, chunk])
      const { messages, rest } = decodeFrames(buf)
      buf = rest
      for (const raw of messages) {
        let m: SocketFrame
        try {
          m = parseSocketFrame(raw)
        } catch (err) {
          log.warn("socket_frame_invalid", { err: err instanceof Error ? err.message : String(err) })
          continue
        }
        if (m.kind === "registered") {
          registered = { name: m.display_name, session_id: m.session_id }
        } else if (m.kind === "inbound") {
          log.debug("inbound", { content: m.content.slice(0, 80), meta_keys: Object.keys(m.meta) })
          opts.onInbound?.({ content: m.content, meta: m.meta })
        } else if (m.kind === "result") {
          log.info("shim_call_response_received", { call_id: m.call_id, ok: m.ok, mono_ns: process.hrtime.bigint().toString() })
          const resolver = pending.get(m.call_id)
          if (resolver) { pending.delete(m.call_id); resolver({ ok: m.ok, value: m.value, error: m.error }) }
        } else if (m.kind === "pong") {
          // ignore — heartbeat tracking lives broker-side
        }
      }
    })
    function drainPending(reason: string) {
      for (const [call_id, resolver] of pending) {
        resolver({ ok: false, error: reason })
        pending.delete(call_id)
      }
    }
    s.on("close", async () => {
      drainPending("broker disconnected")
      if (closedByUs) return
      log.warn("disconnect_starting_reconnect_loop")
      void reconnectLoop()
    })
    // Drain pending on `error` too. Without this, a half-closed socket
    // (where write emits `error` asynchronously but `close` is delayed)
    // can hold pending entries waiting for a `close` that may not arrive
    // promptly. Symmetric draining keeps no pending alive past the dead
    // socket.
    s.on("error", () => { drainPending("broker socket error") })
  }

  async function attemptConnect(): Promise<Socket> {
    return await new Promise<Socket>((res, rej) => {
      const s = connect(sockPath)
      s.once("connect", () => res(s))
      s.once("error", rej)
    })
  }

  async function reconnectLoop() {
    for (let attempt = 0; !closedByUs; attempt++) {
      const delay = backoffFor(attempt)
      log.info("reconnect_waiting", { attempt, delay_ms: delay })
      await new Promise(r => setTimeout(r, delay))
      if (closedByUs) return
      try {
        const fresh = await attemptConnect()
        sock = fresh
        wireSocket(sock)
        // Re-send register frame so broker re-attaches us under the same name.
        sock.write(encodeFrame({
          kind: "register",
          workdir: opts.workdir,
          pid: opts.pid,
          ...(opts.requestedName ? { requested_name: opts.requestedName } : {}),
          ...(opts.displayName ? { display_name: opts.displayName } : {}),
          ...(opts.agentSessionId ? { agent_session_id: opts.agentSessionId } : {}),
          ...(opts.channelOnly || process.env.MUX_CHANNEL_ONLY === "1" ? { channel_only: true } : {}),
        }))
        log.info("reconnect_success", { attempt })
        return
      } catch (err) {
        log.warn("reconnect_attempt_failed", { attempt, err: String(err) })
      }
    }
  }

  // First connect — same promise-based pattern as before; if THIS fails, throw.
  sock = await attemptConnect()
  wireSocket(sock)

  sock.write(encodeFrame({
    kind: "register",
    workdir: opts.workdir,
    pid: opts.pid,
    ...(opts.requestedName ? { requested_name: opts.requestedName } : {}),
    ...(opts.displayName ? { display_name: opts.displayName } : {}),
    ...(opts.agentSessionId ? { agent_session_id: opts.agentSessionId } : {}),
    ...(opts.channelOnly || process.env.MUX_CHANNEL_ONLY === "1" ? { channel_only: true } : {}),
  }))

  for (let i = 0; i < 100 && !registered; i++) await new Promise(r => setTimeout(r, 20))
  if (!registered) throw new Error("broker did not respond to register within 2s")

  function nextCallId(): string { return `c${++counter}` }
  // Hard 10s ceiling. Future hangs become loud timeouts that surface in
  // logs and reject the caller, instead of silently wedging an MCP host
  // for minutes. A healthy broker round-trip is sub-100ms; 10s is a
  // generous upper bound for any pathological case worth surfacing.
  // Overridable via env so tests can drive shorter timeouts.
  const CALL_TIMEOUT_MS = Number(process.env.MUX_SHIM_CALL_TIMEOUT_MS ?? 10_000)
  function sendCall(kind: "outbound" | "orchestration", op: ToolOperation): Promise<{ ok: boolean; value?: unknown; error?: string }> {
    const call_id = nextCallId()
    const enter_mono_ns = process.hrtime.bigint()
    log.info("shim_call_enter", { call_id, kind, op_name: op?.name, mono_ns: enter_mono_ns.toString() })
    return new Promise(resolve => {
      let settled = false
      let timer: ReturnType<typeof setTimeout> | undefined
      const settle = (r: { ok: boolean; value?: unknown; error?: string }, reason: string) => {
        if (settled) return
        settled = true
        if (timer) clearTimeout(timer)
        pending.delete(call_id)
        const ns = process.hrtime.bigint()
        const ms = Number(ns - enter_mono_ns) / 1e6
        log.info("shim_call_complete", { call_id, kind, ok: r.ok, reason, elapsed_ms: Math.round(ms), mono_ns: ns.toString() })
        resolve(r)
      }
      // Fail fast when the socket is not currently writable. During the
      // reconnect window `sock.write` on the destroyed socket does NOT
      // throw synchronously — it returns false and emits an async `error`
      // event, dropping the frame. Old `try { sock.write } catch` caught
      // nothing, leaking the pending entry forever.
      if (!sock || sock.destroyed || !sock.writable) {
        log.warn("shim_call_fail_fast", { call_id, kind, reason: "not_writable" })
        settle({ ok: false, error: "broker not writable (mid-reconnect)" }, "not_writable")
        return
      }
      // The data-handler path looks up `pending` by call_id; it gets the
      // settle function which is idempotent.
      pending.set(call_id, (r) => settle(r, "response"))
      timer = setTimeout(() => {
        log.warn("shim_call_timeout_exceeded", { call_id, kind, timeout_ms: CALL_TIMEOUT_MS })
        settle({ ok: false, error: `broker call timeout after ${CALL_TIMEOUT_MS}ms` }, "timeout")
      }, CALL_TIMEOUT_MS)
      try {
        sock.write(encodeFrame({ kind, call_id, op }), (err) => {
          if (err) {
            log.warn("shim_call_write_failed", { call_id, kind, err: err.message })
            settle({ ok: false, error: `broker write failed: ${err.message}` }, "write_failed")
            return
          }
          log.info("shim_call_write_complete", { call_id, kind, mono_ns: process.hrtime.bigint().toString() })
        })
      } catch (err) {
        log.warn("shim_call_write_threw", { call_id, kind, err: String(err) })
        settle({ ok: false, error: `broker disconnected: ${err}` }, "write_threw")
      }
    })
  }

  return {
    assignedName: registered.name,
    callOutbound: op => sendCall("outbound", op),
    callOrchestration: op => sendCall("orchestration", op),
    async close() { closedByUs = true; sock.destroy() },
  }
}
