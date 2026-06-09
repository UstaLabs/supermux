import { ref, computed, toValue, type MaybeRefOrGetter, type Ref } from "vue"

const BACKOFF_MS = [1000, 2000, 4000, 8000, 30000]

export type TerminalStatus = "connecting" | "connected" | "disconnected"

export interface UseTerminal {
  status: Ref<TerminalStatus>
  connect: () => void
  disconnect: () => void
  sendInput: (data: Uint8Array) => void
  resize: (cols: number, rows: number) => void
  onData: (cb: (data: Uint8Array) => void) => void
  onExit: (cb: (code: number) => void) => void
}

export function useTerminal(sessionName: MaybeRefOrGetter<string>): UseTerminal {
  const sessionId = computed(() => toValue(sessionName))
  const status = ref<TerminalStatus>("disconnected")

  let ws: WebSocket | null = null
  let attempt = 0
  let stopped = false

  let dataCallback: ((data: Uint8Array) => void) | null = null
  let exitCallback: ((code: number) => void) | null = null

  function open() {
    if (stopped) return
    status.value = "connecting"
    const proto = window.location.protocol === "https:" ? "wss" : "ws"
    ws = new WebSocket(
      `${proto}://${window.location.host}/ws/term?session=${encodeURIComponent(sessionId.value)}`
    )
    ws.binaryType = "arraybuffer"

    ws.onopen = () => {
      attempt = 0
      status.value = "connected"
    }

    ws.onmessage = (e) => {
      if (e.data instanceof ArrayBuffer) {
        dataCallback?.(new Uint8Array(e.data))
        return
      }
      let frame: any
      try { frame = JSON.parse(String(e.data)) } catch { return }
      if (frame.type === "exit") {
        exitCallback?.(frame.code ?? 0)
      } else if (frame.type === "error") {
        exitCallback?.(-1)
      }
    }

    ws.onclose = () => {
      if (stopped) {
        status.value = "disconnected"
        return
      }
      status.value = "connecting"
      const delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)]!
      attempt++
      setTimeout(open, delay)
    }

    ws.onerror = () => {
      try { ws?.close() } catch {}
    }
  }

  function connect() {
    stopped = false
    attempt = 0
    open()
  }

  function disconnect() {
    stopped = true
    try { ws?.close() } catch {}
    ws = null
    status.value = "disconnected"
  }

  function sendInput(data: Uint8Array) {
    if (ws?.readyState === WebSocket.OPEN) {
      ws.send(data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength))
    }
  }

  function resize(cols: number, rows: number) {
    if (ws?.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "resize", cols, rows }))
    }
  }

  function onData(cb: (data: Uint8Array) => void) {
    dataCallback = cb
  }

  function onExit(cb: (code: number) => void) {
    exitCallback = cb
  }

  return { status, connect, disconnect, sendInput, resize, onData, onExit }
}
