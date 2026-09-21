import { createInterface } from 'node:readline'
import { appendFileSync, writeFileSync } from 'node:fs'
if (process.env.PID_FILE) writeFileSync(process.env.PID_FILE, String(process.pid))
if (process.env.MODE === 'stubborn') process.on('SIGTERM', () => {})
const send = o => process.stdout.write(JSON.stringify(o) + '\n')
const trace = line => { if (process.env.TRACE) appendFileSync(process.env.TRACE, line + (line.endsWith('\n') ? '' : '\n')) }
createInterface({ input: process.stdin }).on('line', line => {
  trace(line)
  const m = JSON.parse(line)
  if (m.cmd === 'echo') { send({ echoed: m.n }); return }
  if (m.cmd === 'ask') { send({ id: m.askId ?? 'park-1', method: 'item/ask', params: m.params ?? {} }); return }
  if (m.cmd === 'ask-later') {
    setTimeout(() => send({ id: m.askId ?? 'park-1', method: 'item/ask', params: {} }), m.delay ?? 40)
    return
  }
  if (m.cmd === 'oversize') { process.stdout.write('x'.repeat(Number(m.n ?? 5000)) + '\n'); return }
  if (m.cmd === 'exit') { process.exit(m.code ?? 0); return }
  if (typeof m.method === 'string' && 'id' in m) {
    const delay = Number(m.delay ?? 0)
    const go = () => send({ id: m.id, result: m.result ?? { ok: true } })
    if (delay > 0) setTimeout(go, delay)
    else go()
    return
  }
  if ('result' in m || 'error' in m) { send({ answered: m.id, result: m.result, error: m.error }); return }
  send({ received: m })
})
