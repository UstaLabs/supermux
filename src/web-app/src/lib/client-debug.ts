import { useWS } from "@/api/ws"

export type ClientLogEntry = {
  ts: number
  category: string
  event: string
  data?: Record<string, unknown>
}

const MAX_ENTRIES = 400
const ring: ClientLogEntry[] = []
let flushTimer: ReturnType<typeof setTimeout> | null = null

function trimValue(v: unknown, depth = 0): unknown {
  if (depth > 3) return "[deep]"
  if (v == null || typeof v === "number" || typeof v === "boolean") return v
  if (typeof v === "string") return v.length > 500 ? `${v.slice(0, 500)}…` : v
  if (Array.isArray(v)) return v.slice(0, 20).map((x) => trimValue(x, depth + 1))
  if (typeof v === "object") {
    const out: Record<string, unknown> = {}
    for (const [k, val] of Object.entries(v as Record<string, unknown>).slice(0, 30)) {
      out[k] = trimValue(val, depth + 1)
    }
    return out
  }
  return String(v)
}

/** Append a structured client log entry (ring buffer + optional server flush). */
export function clientDebug(category: string, event: string, data?: Record<string, unknown>): void {
  const entry: ClientLogEntry = {
    ts: Date.now(),
    category,
    event,
    data: data ? (trimValue(data) as Record<string, unknown>) : undefined,
  }
  ring.push(entry)
  if (ring.length > MAX_ENTRIES) ring.splice(0, ring.length - MAX_ENTRIES)

  if (category === "lsp" && /error|fail|null|timeout/i.test(event)) {
    scheduleFlush("lsp_error")
  }
}

export function getClientDebugEntries(category?: string): ClientLogEntry[] {
  const list = category ? ring.filter((e) => e.category === category) : [...ring]
  return list.slice(-120)
}

export function clearClientDebug(category?: string): void {
  if (!category) {
    ring.length = 0
    return
  }
  for (let i = ring.length - 1; i >= 0; i--) {
    if (ring[i]!.category === category) ring.splice(i, 1)
  }
}

function scheduleFlush(reason: string): void {
  if (flushTimer) return
  flushTimer = setTimeout(() => {
    flushTimer = null
    void flushClientLogs({ reason })
  }, 800)
}

/** Push buffered entries to the broker (WebSocket preferred, HTTP fallback). */
export async function flushClientLogs(meta?: Record<string, unknown>): Promise<{ ok: boolean; via?: string }> {
  if (ring.length === 0) return { ok: true }
  const entries = ring.splice(0, ring.length)
  const payload = {
    entries,
    meta: {
      ...meta,
      buildId: typeof __APP_BUILD_ID__ !== "undefined" ? __APP_BUILD_ID__ : "unknown",
      href: location.href,
      ua: navigator.userAgent.slice(0, 200),
    },
  }

  try {
    const ws = useWS()
    if (ws.status === "connected") {
      ws.send({ type: "client_logs", ...payload })
      return { ok: true, via: "ws" }
    }
  } catch { /* store not ready */ }

  try {
    const res = await fetch("/client-logs", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload),
    })
    return { ok: res.ok, via: "http" }
  } catch (err) {
    ring.unshift(...entries)
    if (ring.length > MAX_ENTRIES) ring.splice(0, ring.length - MAX_ENTRIES)
    clientDebug("debug", "flush_failed", { message: String(err) })
    return { ok: false }
  }
}

declare global {
  interface Window {
    __cmuxDebug?: {
      entries: (category?: string) => ClientLogEntry[]
      flush: typeof flushClientLogs
      lsp: () => ClientLogEntry[]
    }
  }
}

if (typeof window !== "undefined") {
  window.__cmuxDebug = {
    entries: getClientDebugEntries,
    flush: flushClientLogs,
    lsp: () => getClientDebugEntries("lsp"),
  }
}
