import { appendFileSync, writeFileSync } from 'node:fs'

const argv = process.argv.slice(2)
if (process.env.PID_FILE) writeFileSync(process.env.PID_FILE, String(process.pid))
if (process.env.MODE === 'stubborn') process.on('SIGTERM', () => {})
if (process.env.TRACE) appendFileSync(process.env.TRACE, JSON.stringify({ argv, envKeys: Object.keys(process.env).sort() }) + '\n')

const CREATE_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const flag = (name) => {
  const eq = argv.find(a => a.startsWith(name + '='))
  if (eq) return eq.slice(name.length + 1)
  const i = argv.indexOf(name)
  if (i < 0) return undefined
  const next = argv[i + 1]
  if (next === undefined || next.startsWith('-')) return true
  return next
}

function fail(code, message) {
  process.stderr.write(message + '\n')
  process.exit(code)
}

const send = o => process.stdout.write(JSON.stringify(o) + '\n')

if (argv.includes('create-chat')) {
  if (process.env.MODE === 'setup-hang') {
    setInterval(() => {}, 1 << 30)
  } else if (process.env.MODE === 'create-invalid') {
    process.stdout.write('not-a-uuid\n')
    process.exit(0)
  } else {
    process.stdout.write(CREATE_ID + '\n')
    process.exit(0)
  }
} else {
  if (!argv.includes('-p') && !flag('-p')) fail(10, 'missing -p')
  if (flag('--output-format') !== 'stream-json') fail(11, 'missing stream-json')
  if (!argv.includes('--stream-partial-output')) fail(12, 'missing --stream-partial-output')
  if (!argv.includes('--trust')) fail(13, 'missing --trust')
  if (flag('--mode') !== 'ask') fail(14, 'mode must default to ask')
  if (flag('--sandbox') !== 'enabled') fail(15, 'sandbox must default to enabled')
  if (argv.includes('--force') || argv.includes('--yolo') || argv.includes('--approve-mcps')) fail(16, 'must not default force or approve-mcps')
  const resume = flag('--resume')
  if (!resume) fail(17, 'missing --resume')
  const session = resume
  const text = flag('-p') ?? ''

  if (process.env.MODE === 'wrong-id') {
    send({ type: 'system', subtype: 'init', session_id: 'ffffffff-ffff-4fff-8fff-ffffffffffff', cwd: process.cwd(), model: 'fixture', permissionMode: 'default' })
    process.exit(0)
  }
  if (text !== 'no-init') {
    send({ type: 'system', subtype: 'init', session_id: session, cwd: process.cwd(), model: flag('--model') ?? 'fixture', permissionMode: 'default' })
  }
  if (text === 'eof') { process.stdout.end(() => process.exit(0)); }
  else if (text === 'malformed') { process.stdout.write('{bad}\n') }
  else if (text === 'oversize') { process.stdout.write('x'.repeat(5000) + '\n') }
  else if (text === 'disconnect') { process.exit(1) }
  else if (text === 'empty-exit') { process.exit(0) }
  else if (text === 'hang') { setInterval(() => {}, 1 << 30) }
  else if (text === 'no-init') {
    send({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: session })
    process.exit(0)
  } else if (text === 'result-no-id') {
    send({ type: 'result', subtype: 'success', is_error: false, result: 'ok' })
    process.exit(0)
  } else if (text === 'result-exit17') {
    send({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: session, duration_ms: 1 })
    process.exit(17)
  } else if (text === 'result-hang') {
    send({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: session, duration_ms: 1 })
    setInterval(() => {}, 1 << 30)
  } else if (text === 'fail') {
    send({ type: 'result', subtype: 'success', is_error: true, result: 'turn failed', session_id: session })
    process.exit(1)
  } else if (!['eof', 'malformed', 'oversize', 'disconnect', 'empty-exit'].includes(text)) {
    send({ type: 'assistant', session_id: session, message: { role: 'assistant', content: [{ type: 'text', text: 'héllo' }] } })
    send({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: session, duration_ms: 1 })
    process.exit(0)
  }
}
