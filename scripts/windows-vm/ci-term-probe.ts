// The Windows lane's terminal probe (scripts/windows-vm/ci-smoke.ps1). Opens a session's scratch terminal over the
// authenticated WebSocket, as a paired device. MODE=write types a marker command and waits for its output.
// MODE=reattach expects that output already in the replay (a restarted broker reattached to the same terminal),
// then closes the terminal. Exit 0 = ok, 2 = no marker, 3 = no connection.
export {}
const base = process.env.BASE_URL!
const token = process.env.DEVICE_TOKEN!
const session = process.env.SESSION_ID!
const mode = process.env.MODE === "reattach" ? "reattach" : "write"
const url = `${base.replace(/^http/, "ws")}/ws/term?session=${encodeURIComponent(session)}&terminal=ciprobe`
const MARKER = /SUPERMUX_CI_TERM_OK/

const ws = new WebSocket(url, { headers: { Authorization: `Bearer ${token}` } })
let output = ""
ws.addEventListener("message", event => {
  output += typeof event.data === "string" ? event.data : new TextDecoder().decode(event.data as ArrayBuffer)
})
const opened = await new Promise<boolean>(resolve => {
  const timer = setTimeout(() => resolve(false), 15_000)
  ws.addEventListener("open", () => { clearTimeout(timer); resolve(true) })
  ws.addEventListener("error", () => { clearTimeout(timer); resolve(false) })
})
if (!opened) {
  console.log(`CI_TERM_NO_CONNECTION ${url}`)
  process.exit(3)
}
if (mode === "write") {
  ws.send(JSON.stringify({ type: "resize", cols: 120, rows: 40 }))
  await Bun.sleep(2000)
  // Built at runtime so the echo of the typed line alone cannot match.
  ws.send(new TextEncoder().encode("Write-Output ('SUPERMUX_CI_' + 'TERM_OK')\r"))
}
const deadline = Date.now() + 45_000
while (Date.now() < deadline && !MARKER.test(output)) await Bun.sleep(100)
if (!MARKER.test(output)) {
  console.log(`CI_TERM_NO_MARKER mode=${mode} output=${JSON.stringify(output.slice(-400))}`)
  process.exit(2)
}
if (mode === "reattach") {
  ws.send(JSON.stringify({ type: "close" }))
  await Bun.sleep(300)
}
ws.close()
console.log(`CI_TERM_OK mode=${mode}`)
