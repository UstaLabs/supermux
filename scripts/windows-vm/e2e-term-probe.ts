import { SessionStore } from "./src/core/sessiond/session-store"
const enc = new TextEncoder()
const lines: string[] = []
function log(m: string) {
  lines.push(m)
  try {
    Bun.write("C:\\Windows\\Temp\\e2e-term.txt", lines.join("\n") + "\n")
  } catch {}
}
async function wait(store: SessionStore, id: string, re: RegExp, ms: number) {
  const d = Date.now() + ms
  let last: string | null = null
  while (Date.now() < d) {
    last = await store.capture(id, true)
    if (last?.match(re)) return last
    await Bun.sleep(50)
  }
  throw new Error(`timeout ${re}; last=${JSON.stringify(last)?.slice(0, 250)}`)
}
log(`BUN=${Bun.version} ARCH=${process.arch}`)
const store = new SessionStore()
const env = Object.fromEntries(Object.entries(process.env).filter((e): e is [string, string] => e[1] !== undefined))
const t = await store.create({
  group: "e2e",
  name: "ps-scratch",
  cwd: "C:\\Windows\\Temp",
  argv: ["powershell.exe", "-NoLogo", "-NoProfile"],
  env: { ...env, TERM: "xterm-256color" },
  cols: 100,
  rows: 30,
})
log(`pid=${t.pid}`)
try {
  await wait(store, t.id, /powershell/iu, 25000)
  log("READY")
  await store.write(t.id, enc.encode("Write-Output ('SUPERMUX_' + 'TERM_OK')\r"))
  await wait(store, t.id, /SUPERMUX_TERM_OK/u, 15000)
  log("TERM_OK")
  await store.resize(t.id, 120, 40)
  log("RESIZE_OK")
  await store.kill(t.id)
  log("SUPERMUX_TERM_E2E_OK")
} catch (e) {
  log(`FAIL ${e}`)
  process.exitCode = 1
} finally {
  await store.kill(t.id).catch(() => undefined)
}
