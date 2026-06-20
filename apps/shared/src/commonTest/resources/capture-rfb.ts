#!/usr/bin/env bun
// Capture a REAL RFB/ZRLE byte stream from the live broker VNC byte-tunnel.
//
// Drives the RFB 3.8 handshake over the broker WS (`/ws/display?id=<id>`), pins
// the 32bpp BGRA pixel format, requests encodings [ZRLE,CopyRect,Raw,DesktopSize],
// asks for a full framebuffer update, then records EVERY server->client byte to
// `rfb-zrle-session.bin`. Stops after the first FramebufferUpdate that contains a
// ZRLE (enc 16) rect. Also writes the exact client->server bytes we sent to
// `rfb-client-handshake.bin` (golden output for encoder tests).
//
// Usage:
//   bun apps/shared/src/commonTest/resources/capture-rfb.ts <id> <token> [baseHost]
// e.g.
//   bun .../capture-rfb.ts d-1504c1bf "$TOKEN" localhost:9898

import { writeFileSync } from "node:fs"
import { join } from "node:path"

const id = process.argv[2]
const token = process.argv[3]
const host = process.argv[4] ?? "localhost:9898"
if (!id || !token) {
  console.error("usage: bun capture-rfb.ts <id> <token> [host]")
  process.exit(1)
}

const OUT_DIR = import.meta.dir
const url = `ws://${host}/ws/display?id=${id}`

// ── byte sinks ────────────────────────────────────────────────────────────────
const serverChunks: Uint8Array[] = []
const clientChunks: Uint8Array[] = []

// rolling buffer of UNCONSUMED server bytes for the handshake state machine
let buf = new Uint8Array(0)
function appendBuf(b: Uint8Array) {
  const n = new Uint8Array(buf.length + b.length)
  n.set(buf, 0)
  n.set(b, buf.length)
  buf = n
}
// resolver chain for readN
let waiter: { n: number; resolve: (b: Uint8Array) => void } | null = null
function pump() {
  if (waiter && buf.length >= waiter.n) {
    const out = buf.slice(0, waiter.n)
    buf = buf.slice(waiter.n)
    const w = waiter
    waiter = null
    w.resolve(out)
  }
}
function readN(n: number): Promise<Uint8Array> {
  return new Promise((resolve) => {
    waiter = { n, resolve }
    pump()
  })
}

const ws = new WebSocket(url, { headers: { Authorization: `Bearer ${token}` } } as any)
ws.binaryType = "arraybuffer"

function send(bytes: Uint8Array) {
  clientChunks.push(bytes)
  ws.send(bytes)
}

// big-endian writers
function u8(v: number) { return Uint8Array.of(v & 0xff) }
function u16(v: number) { return Uint8Array.of((v >>> 8) & 0xff, v & 0xff) }
function s32(v: number) {
  return Uint8Array.of((v >>> 24) & 0xff, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff)
}
function cat(...parts: Uint8Array[]) {
  let len = 0
  for (const p of parts) len += p.length
  const out = new Uint8Array(len)
  let o = 0
  for (const p of parts) { out.set(p, o); o += p.length }
  return out
}

// SetPixelFormat: msg 0, pad(3), 16-byte PIXEL_FORMAT pinned 32bpp BGRA
function setPixelFormat(): Uint8Array {
  const pf = Uint8Array.of(
    32, // bits-per-pixel
    24, // depth
    0,  // big-endian-flag = 0 (little endian)
    1,  // true-colour-flag = 1
    0, 255, // red-max 255
    0, 255, // green-max 255
    0, 255, // blue-max 255
    16, // red-shift
    8,  // green-shift
    0,  // blue-shift
    0, 0, 0, // padding
  )
  return cat(u8(0), u8(0), u8(0), u8(0), pf)
}

// SetEncodings: msg 2, pad(1), u16 count, s32[] encodings
function setEncodings(encs: number[]): Uint8Array {
  return cat(u8(2), u8(0), u16(encs.length), ...encs.map(s32))
}

// FramebufferUpdateRequest: msg 3, u8 incremental, u16 x,y,w,h
function fbur(incremental: number, x: number, y: number, w: number, h: number): Uint8Array {
  return cat(u8(3), u8(incremental), u16(x), u16(y), u16(w), u16(h))
}

let done = false
function finish(reason: string) {
  if (done) return
  done = true
  const server = cat(...serverChunks)
  const client = cat(...clientChunks)
  writeFileSync(join(OUT_DIR, "rfb-zrle-session.bin"), server)
  writeFileSync(join(OUT_DIR, "rfb-client-handshake.bin"), client)
  console.error(`[capture] ${reason}: wrote ${server.length} server bytes, ${client.length} client bytes`)
  try { ws.close() } catch {}
  process.exit(0)
}

ws.addEventListener("message", (ev: MessageEvent) => {
  const b = new Uint8Array(ev.data as ArrayBuffer)
  serverChunks.push(b)
  appendBuf(b)
  pump()
})
ws.addEventListener("error", (e: any) => { console.error("[capture] ws error", e?.message ?? e); process.exit(2) })
ws.addEventListener("close", () => { if (!done) { console.error("[capture] closed before ZRLE"); finish("closed-early") } })

ws.addEventListener("open", async () => {
  try {
    // 1. ProtocolVersion: server sends 12 bytes "RFB 003.008\n"
    const pv = await readN(12)
    const pvStr = new TextDecoder().decode(pv)
    console.error("[capture] server version:", JSON.stringify(pvStr))
    send(new TextEncoder().encode("RFB 003.008\n"))

    // 2. Security: u8 count, then count u8 types
    const cnt = (await readN(1))[0]
    if (cnt === 0) {
      // failure: u32 reason length + reason
      const rl = await readN(4)
      const rlen = (rl[0] << 24) | (rl[1] << 16) | (rl[2] << 8) | rl[3]
      const reason = new TextDecoder().decode(await readN(rlen))
      throw new Error("security failure: " + reason)
    }
    const types = await readN(cnt)
    console.error("[capture] security types:", Array.from(types))
    if (!types.includes(1)) throw new Error("server did not offer None(1); types=" + Array.from(types))
    send(u8(1)) // pick None

    // 3. SecurityResult: u32 (0=OK)
    const sr = await readN(4)
    const ok = (sr[0] << 24) | (sr[1] << 16) | (sr[2] << 8) | sr[3]
    if (ok !== 0) throw new Error("security result not OK: " + ok)

    // 4. ClientInit: u8 shared=1
    send(u8(1))

    // 5. ServerInit: u16 w, u16 h, 16 PIXEL_FORMAT, u32 nameLen, name
    const head = await readN(4)
    const w = (head[0] << 8) | head[1]
    const h = (head[2] << 8) | head[3]
    await readN(16) // server pixel format (we override it)
    const nl = await readN(4)
    const nameLen = (nl[0] << 24) | (nl[1] << 16) | (nl[2] << 8) | nl[3]
    const name = new TextDecoder().decode(await readN(nameLen))
    console.error(`[capture] ServerInit ${w}x${h} name=${JSON.stringify(name)}`)

    // 6. SetPixelFormat + SetEncodings + full FramebufferUpdateRequest
    send(setPixelFormat())
    send(setEncodings([16, 1, 0, -223])) // ZRLE, CopyRect, Raw, DesktopSize
    send(fbur(0, 0, 0, w, h)) // full

    // 7. Read server messages; record raw; stop after first FBU containing ZRLE.
    // Loop FramebufferUpdate parsing. Resend incremental requests to keep frames
    // flowing until a ZRLE rect appears (glxgears guarantees motion).
    let sawZrle = false
    let updates = 0
    while (!sawZrle && updates < 40) {
      const t = (await readN(1))[0]
      if (t === 0) {
        // FramebufferUpdate
        await readN(1) // pad
        const nr = await readN(2)
        const nRects = (nr[0] << 8) | nr[1]
        let hasZrleThis = false
        for (let i = 0; i < nRects; i++) {
          const rh = await readN(12)
          const rw = (rh[4] << 8) | rh[5]
          const rhh = (rh[6] << 8) | rh[7]
          const enc = (rh[8] << 24) | (rh[9] << 16) | (rh[10] << 8) | rh[11]
          if (enc === 0) {
            await readN(rw * rhh * 4) // Raw
          } else if (enc === 1) {
            await readN(4) // CopyRect srcX,srcY
          } else if (enc === 16) {
            const ln = await readN(4)
            const len = ((ln[0] << 24) | (ln[1] << 16) | (ln[2] << 8) | ln[3]) >>> 0
            await readN(len) // ZRLE payload
            hasZrleThis = true
          } else if (enc === -223) {
            // DesktopSize pseudo — no data
          } else {
            throw new Error("unexpected encoding " + enc)
          }
        }
        updates++
        console.error(`[capture] FBU #${updates} rects=${nRects} zrle=${hasZrleThis}`)
        if (hasZrleThis) { sawZrle = true; break }
        // ask for more (incremental, full rect)
        send(fbur(1, 0, 0, w, h))
      } else if (t === 2) {
        // Bell — no payload
      } else if (t === 3) {
        // ServerCutText: pad(3), u32 len, text
        await readN(3)
        const cl = await readN(4)
        const clen = ((cl[0] << 24) | (cl[1] << 16) | (cl[2] << 8) | cl[3]) >>> 0
        await readN(clen)
      } else if (t === 1) {
        // SetColourMapEntries: pad(1), u16 first, u16 n, n*6 bytes
        await readN(1)
        const fb = await readN(2)
        const ncol = (fb[0] << 8) | fb[1]
        await readN(ncol * 6)
      } else {
        throw new Error("unknown server message type " + t)
      }
    }
    if (!sawZrle) throw new Error("no ZRLE rect after " + updates + " updates")
    finish(`captured ZRLE after ${updates} update(s)`)
  } catch (e: any) {
    console.error("[capture] handshake error:", e?.message ?? e)
    process.exit(3)
  }
})

// safety timeout
setTimeout(() => { console.error("[capture] timeout"); finish("timeout") }, 20000)
