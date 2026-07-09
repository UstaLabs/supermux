// Gates inbound channel messages on Claude's ACTUAL readiness to receive them.
//
// Claude silently discards `notifications/claude/channel` sent before it has
// completed the MCP initialize handshake AND wired its channel-notification
// handler ("Channel notifications registered" in its debug log, observed
// 0.6–1.0s after initialize completes). The old shim flushed on wall-clock
// guesses — a 2s post-spawn fallback and a 2.5s post-ListTools grace — so on a
// loaded host the flush raced Claude's startup and the first message of a
// fresh session vanished (confirmed 3× on 2026-07-08: the transcript's first
// user message was the user's ~30s-later re-send, and the shim log showed the
// fallback firing before Claude even finished connecting).
//
// The gate anchors on the real signal instead: the SDK server's `oninitialized`
// callback (the initialize handshake is mandatory on every connection,
// including --resume, unlike ListTools which resume may skip). Messages are
// buffered until initialized + graceMs (covering the short handler-wiring
// window after the handshake), then flushed in order; from that point on
// messages pass through immediately. If initialize NEVER completes, a generous
// init-timeout flushes anyway as a last resort — if Claude is dead the write
// is lost regardless, but a pathologically slow boot still gets the message.
export type InboundPayload = { content: string; meta: Record<string, string> }

export type FlushTrigger = "immediate" | "initialized_grace" | "init_timeout"

export type InboundGate = {
  inbound: (p: InboundPayload) => void
  initialized: () => void
  isOpen: () => boolean
  pendingCount: () => number
}

export function createInboundGate(opts: {
  graceMs: number
  initTimeoutMs: number
  notify: (p: InboundPayload, trigger: FlushTrigger) => void
  schedule?: (fn: () => void, ms: number) => () => void
}): InboundGate {
  const schedule = opts.schedule ?? ((fn: () => void, ms: number) => {
    const t = setTimeout(fn, ms)
    t.unref?.()
    return () => clearTimeout(t)
  })

  let open = false
  let initSeen = false
  const buffer: InboundPayload[] = []

  function doOpen(trigger: Exclude<FlushTrigger, "immediate">) {
    if (open) return
    open = true
    cancelInitTimeout()
    for (const p of buffer.splice(0)) opts.notify(p, trigger)
  }

  const cancelInitTimeout = schedule(() => doOpen("init_timeout"), opts.initTimeoutMs)

  return {
    inbound(p) {
      if (open) opts.notify(p, "immediate")
      else buffer.push(p)
    },
    initialized() {
      if (open || initSeen) return
      initSeen = true
      cancelInitTimeout()
      schedule(() => doOpen("initialized_grace"), opts.graceMs)
    },
    isOpen: () => open,
    pendingCount: () => buffer.length,
  }
}
