// src/web-app/src/composables/useWaveform.ts
const RING_SIZE = 120  // number of samples shown across the canvas width

export interface UseWaveform {
  attach: (stream: MediaStream, canvas: HTMLCanvasElement) => void
  detach: () => void
  snapshot: () => number[]
}

export function useWaveform(): UseWaveform {
  let audioCtx: AudioContext | null = null
  let analyser: AnalyserNode | null = null
  let source: MediaStreamAudioSourceNode | null = null
  let canvas: HTMLCanvasElement | null = null
  let ring: number[] = []
  let raf: number | null = null
  const timeData = new Uint8Array(256)

  function draw() {
    if (!analyser || !canvas) return
    analyser.getByteTimeDomainData(timeData as any)
    let peak = 0
    for (let i = 0; i < timeData.length; i++) {
      const v = Math.abs(timeData[i]! - 128)
      if (v > peak) peak = v
    }
    ring.push(peak / 128)
    if (ring.length > RING_SIZE) ring.shift()

    const ctx = canvas.getContext("2d")
    if (!ctx) return
    const w = canvas.width
    const h = canvas.height
    ctx.clearRect(0, 0, w, h)
    ctx.fillStyle = getComputedStyle(canvas).color || "#fff"
    const barW = w / RING_SIZE
    for (let i = 0; i < ring.length; i++) {
      const amp = ring[i]!
      const barH = Math.max(2, amp * h)
      const x = i * barW
      const y = (h - barH) / 2
      ctx.fillRect(x, y, Math.max(1, barW - 1), barH)
    }
    raf = requestAnimationFrame(draw)
  }

  function attach(stream: MediaStream, canvasEl: HTMLCanvasElement): void {
    detach()
    canvas = canvasEl
    canvas.width = canvas.clientWidth * (window.devicePixelRatio || 1)
    canvas.height = canvas.clientHeight * (window.devicePixelRatio || 1)
    audioCtx = new (window.AudioContext || (window as any).webkitAudioContext)()
    // Safari needs an explicit resume from a user gesture; caller invokes us from one.
    if (audioCtx.state === "suspended") void audioCtx.resume()
    source = audioCtx.createMediaStreamSource(stream)
    analyser = audioCtx.createAnalyser()
    analyser.fftSize = 256
    source.connect(analyser)
    ring = []
    raf = requestAnimationFrame(draw)
  }

  function detach(): void {
    if (raf !== null) { cancelAnimationFrame(raf); raf = null }
    try { source?.disconnect() } catch { /* ignore */ }
    try { analyser?.disconnect() } catch { /* ignore */ }
    try { void audioCtx?.close() } catch { /* ignore */ }
    source = null
    analyser = null
    audioCtx = null
    canvas = null
  }

  function snapshot(): number[] {
    return ring.slice()
  }

  return { attach, detach, snapshot }
}
