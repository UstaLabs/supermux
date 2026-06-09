import { ref, type Ref } from "vue"

export type StreamStatus = "connecting" | "connected" | "disconnected" | "unsupported"

export interface UseScrcpyStream {
  status: Ref<StreamStatus>
  dims: Ref<{ width: number; height: number }>
  connect: () => void
  disconnect: () => void
  onFrame: (cb: (f: any) => void) => void
  sendInput: (ev: object) => void
}

export function useScrcpyStream(streamId: string): UseScrcpyStream {
  const status = ref<StreamStatus>("disconnected")
  const dims = ref<{ width: number; height: number }>({ width: 0, height: 0 })
  let ws: WebSocket | null = null
  let decoder: any = null
  let onFrameCb: ((f: any) => void) | null = null

  function connect() {
    if (typeof (globalThis as any).VideoDecoder === "undefined") { status.value = "unsupported"; return }
    const proto = window.location.protocol === "https:" ? "wss" : "ws"
    status.value = "connecting"
    ws = new WebSocket(`${proto}://${window.location.host}/ws/scrcpy?id=${encodeURIComponent(streamId)}`)
    ws.binaryType = "arraybuffer"
    ws.onmessage = (e) => {
      if (typeof e.data === "string") {
        const m = JSON.parse(e.data)
        if (m.type === "init") {
          dims.value = { width: m.width, height: m.height }
          decoder = new (globalThis as any).VideoDecoder({
            output: (f: any) => { onFrameCb?.(f); f.close() },
            error: () => { status.value = "disconnected" },
          })
          decoder.configure({ codec: m.codec === "avc" ? "avc1.42E01E" : m.codec, optimizeForLatency: true })
          status.value = "connected"
        }
        return
      }
      if (!decoder) return
      const u8 = new Uint8Array(e.data as ArrayBuffer)
      const key = (u8[0]! & 0x01) === 1
      const chunk = new (globalThis as any).EncodedVideoChunk({
        type: key ? "key" : "delta",
        timestamp: performance.now() * 1000,
        data: u8.subarray(1),
      })
      try { decoder.decode(chunk) } catch {}
    }
    ws.onclose = () => { if (status.value !== "unsupported") status.value = "disconnected" }
    ws.onerror = () => { try { ws?.close() } catch {} }
  }

  function disconnect() {
    try { ws?.close() } catch {}
    try { decoder?.close() } catch {}
    ws = null
    decoder = null
    status.value = "disconnected"
  }

  function onFrame(cb: (f: any) => void) { onFrameCb = cb }
  function sendInput(ev: object) { if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(ev)) }

  return { status, dims, connect, disconnect, onFrame, sendInput }
}
