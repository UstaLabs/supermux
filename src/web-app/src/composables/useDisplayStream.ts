import { ref, type Ref } from "vue"
import RFB from "@novnc/novnc/core/rfb"

export type StreamStatus = "connecting" | "connected" | "disconnected"

export interface UseDisplayStream {
  status: Ref<StreamStatus>
  connect: (target: HTMLElement, password?: string) => void
  disconnect: () => void
  sendCtrlAltDel: () => void
}

export function useDisplayStream(streamId: string): UseDisplayStream {
  const status = ref<StreamStatus>("disconnected")
  let rfb: any = null

  function connect(target: HTMLElement, password?: string) {
    const proto = window.location.protocol === "https:" ? "wss" : "ws"
    const url = `${proto}://${window.location.host}/ws/display?id=${encodeURIComponent(streamId)}`
    status.value = "connecting"
    rfb = new RFB(target, url, {
      credentials: password ? { password } : undefined,
      wsProtocols: ["binary"],
    })
    rfb.scaleViewport = true
    // Clip + no drag: scaled-to-fit remote desktop; avoids viewport pan gestures
    // that iOS treats as PWA shell scroll.
    rfb.clipViewport = true
    rfb.dragViewport = false
    rfb.addEventListener("connect", () => { status.value = "connected" })
    rfb.addEventListener("disconnect", () => { status.value = "disconnected" })
    rfb.addEventListener("securityfailure", () => { status.value = "disconnected" })
  }

  function disconnect() {
    try { rfb?.disconnect() } catch {}
    rfb = null
    status.value = "disconnected"
  }

  function sendCtrlAltDel() { try { rfb?.sendCtrlAltDel() } catch {} }

  return { status, connect, disconnect, sendCtrlAltDel }
}
