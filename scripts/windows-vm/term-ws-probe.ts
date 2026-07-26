// Authenticated scratch-terminal probe over WebSocket (runs under Bun on Windows).
const base = process.env.BASE_URL!
const token = process.env.DEVICE_TOKEN!
const session = process.env.SESSION_ID!
const url = `${base.replace("http", "ws")}/ws/term?session=${encodeURIComponent(session)}&terminal=authprobe`

const ws = new WebSocket(url, {
  headers: { Authorization: `Bearer ${token}` },
})

let buf = ""
const deadline = Date.now() + 45_000

function log(m: string) {
  console.log(m)
  try {
    const prev = Bun.file("C:\\Windows\\Temp\\term-ws.txt")
    // append-ish rewrite
  } catch {}
  try {
    Bun.write(
      "C:\\Windows\\Temp\\term-ws.txt",
      (typeof (globalThis as any).__lines === "string" ? (globalThis as any).__lines : "") + m + "\n",
    )
    ;(globalThis as any).__lines =
      (typeof (globalThis as any).__lines === "string" ? (globalThis as any).__lines : "") + m + "\n"
  } catch {}
}

await new Promise<void>((resolve, reject) => {
  const t = setTimeout(() => reject(new Error("ws open timeout")), 15_000)
  ws.addEventListener("open", () => {
    clearTimeout(t)
    log("WS_OPEN")
    resolve()
  })
  ws.addEventListener("error", (e) => {
    clearTimeout(t)
    reject(new Error(`ws error ${String(e)}`))
  })
  ws.addEventListener("message", (ev) => {
    const data = typeof ev.data === "string" ? ev.data : new TextDecoder().decode(ev.data as ArrayBuffer)
    buf += data
  })
})

ws.send(JSON.stringify({ type: "resize", cols: 120, rows: 40 }))
await Bun.sleep(2000)
ws.send(new TextEncoder().encode("Write-Output ('SUPERMUX_' + 'TERM_OK')\r"))

while (Date.now() < deadline) {
  if (/SUPERMUX_TERM_OK/.test(buf)) {
    log("TERM_OK")
    break
  }
  await Bun.sleep(50)
}
if (!/SUPERMUX_TERM_OK/.test(buf)) {
  log(`FAIL timeout buf=${JSON.stringify(buf.slice(0, 300))}`)
  process.exit(2)
}

// reattach
ws.close()
await Bun.sleep(500)
const ws2 = new WebSocket(url, { headers: { Authorization: `Bearer ${token}` } })
let buf2 = ""
await new Promise<void>((resolve, reject) => {
  const t = setTimeout(() => reject(new Error("ws2 open timeout")), 15_000)
  ws2.addEventListener("open", () => {
    clearTimeout(t)
    log("WS2_OPEN")
    resolve()
  })
  ws2.addEventListener("error", () => {
    clearTimeout(t)
    reject(new Error("ws2 error"))
  })
  ws2.addEventListener("message", (ev) => {
    const data = typeof ev.data === "string" ? ev.data : new TextDecoder().decode(ev.data as ArrayBuffer)
    buf2 += data
  })
})
const d2 = Date.now() + 20_000
while (Date.now() < d2) {
  if (/SUPERMUX_TERM_OK/.test(buf2)) {
    log("REATTACH_OK")
    break
  }
  await Bun.sleep(50)
}
if (!/SUPERMUX_TERM_OK/.test(buf2)) {
  log(`FAIL reattach buf=${JSON.stringify(buf2.slice(0, 300))}`)
  process.exit(3)
}
ws2.send(JSON.stringify({ type: "close" }))
await Bun.sleep(300)
ws2.close()
log("SUPERMUX_TERM_WS_OK")
