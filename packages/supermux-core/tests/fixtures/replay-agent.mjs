// Generic wire replayer for real captures under fixtures/real/subagents/*.ndjson.
// Each fixture row is {d:'c'|'a', m}: 'c' = what the client sent, 'a' = what the agent sent.
// When the client sends a trigger (a JSON-RPC request, a Claude `user` frame or a Claude
// `control_request`), the replayer advances to the next captured client frame of the same kind,
// replays every agent frame up to the next captured trigger, and rewrites the matching response
// id to the live one. Requests the capture does not contain get an empty success result.
//   argv: --replay <fixture> [--session-id=<id>] (Claude flags are ignored)
//   env:  REPLAY_TRACE=<file> appends every client frame as one JSON line.
//         REPLAY_SKIP=m1,m2   captured client requests of these methods are not triggers (the
//                             live client will not send them; their agent frames just flow on).
//         REPLAY_SKIP_SESSION=id  captured client requests addressed to this sessionId are not
//                             triggers either (a probe's own experiments on a child session).
//         REPLAY_LOOSE=m1,m2  a live request of these methods that the capture does not have next
//                             stands in for whatever captured trigger comes next.
//         REPLAY_GROK_NO_CANCEL=1  answer `_x.ai/subagent/cancel` with -32601 (a Grok without it);
//                             `late`: only real cancels get -32601, the startup probe still works.
// supermux's startup probe of Grok's `_x.ai/subagent/cancel` (subagentId `supermux-probe-…`) is
// answered the way grok 1.0.46 answers an unknown id, without consuming the capture.
import { createInterface } from 'node:readline'
import { appendFileSync, readFileSync } from 'node:fs'

const argv = process.argv.slice(2)
const file = argv[argv.indexOf('--replay') + 1]
const rows = readFileSync(file, 'utf8').split('\n').filter(Boolean).map(line => JSON.parse(line))
const sessionArg = argv.find(a => a.startsWith('--session-id=') || a.startsWith('--resume='))
const liveSession = sessionArg ? sessionArg.slice(sessionArg.indexOf('=') + 1) : undefined
const replacements = new Map()
const fixtureSession = rows.find(r => r.d === 'c' && r.m?.type === 'user')?.m?.session_id
if (liveSession && fixtureSession) replacements.set(fixtureSession, liveSession)

const skip = new Set((process.env.REPLAY_SKIP ?? '').split(',').filter(Boolean))
const loose = new Set((process.env.REPLAY_LOOSE ?? '').split(',').filter(Boolean))
function liveKey(m) {
  if (!m || typeof m !== 'object') return undefined
  if (m.type === 'user') return 'user'
  if (m.type === 'control_request') return `control:${m.request?.subtype}`
  if (typeof m.method === 'string' && m.id !== undefined && m.id !== null) return m.method
  return undefined
}
const skipSessions = new Set((process.env.REPLAY_SKIP_SESSION ?? '').split(',').filter(Boolean))
function key(m) {
  const k = liveKey(m)
  if (k && skipSessions.size && skipSessions.has(m.params?.sessionId)) return undefined
  return k && skip.has(k) ? undefined : k
}
function send(m) {
  let line = JSON.stringify(m)
  for (const [from, to] of replacements) line = line.split(from).join(to)
  process.stdout.write(line + '\n')
}
let cursor = 0
const isResponse = m => m.type === 'control_response' || (m.method === undefined && m.id !== undefined && ('result' in m || 'error' in m))
function isResponseTo(agentFrame, captured) {
  if (captured.type === 'control_request') return agentFrame.type === 'control_response' && agentFrame.response?.request_id === captured.request_id
  if (captured.type === 'user') return false
  return agentFrame.method === undefined && agentFrame.id === captured.id && ('result' in agentFrame || 'error' in agentFrame)
}
function respond(live, captured, agentFrame) {
  if (captured.type === 'control_request') return { ...agentFrame, response: { ...agentFrame.response, request_id: live.request_id } }
  return { ...agentFrame, id: live.id }
}
function fallback(live) {
  if (live.type === 'control_request') send({ type: 'control_response', response: { subtype: 'success', request_id: live.request_id, response: {} } })
  else if (live.type !== 'user') send({ jsonrpc: '2.0', id: live.id, result: {} })
}
function handle(live) {
  const k = liveKey(live)
  if (!k) return
  const probe = k === '_x.ai/subagent/cancel' && String(live.params?.subagentId ?? '').startsWith('supermux-probe')
  if (k === '_x.ai/subagent/cancel' && (process.env.REPLAY_GROK_NO_CANCEL === '1' || (process.env.REPLAY_GROK_NO_CANCEL === 'late' && !probe))) {
    send({ jsonrpc: '2.0', id: live.id, error: { code: -32601, message: 'Method not found' } })
    return
  }
  if (probe) {
    send({ jsonrpc: '2.0', id: live.id, result: { result: { subagentId: live.params.subagentId, cancelled: false, outcome: { kind: 'not_found' } } } })
    return
  }
  let at = -1
  let next = -1
  for (let i = cursor; i < rows.length; i++) {
    if (rows[i].d !== 'c') continue
    const captured = key(rows[i].m)
    if (captured && next < 0) next = i
    if (captured === k) { at = i; break }
  }
  if (at < 0 && loose.has(k) && next >= 0) at = next
  if (at < 0) { fallback(live); return }
  const captured = rows[at].m
  if (captured.type === 'user' && typeof captured.uuid === 'string' && typeof live.uuid === 'string') replacements.set(captured.uuid, live.uuid)
  // Agent frames captured before this trigger (after the previous one) are still owed.
  for (let i = cursor; i < at; i++) if (rows[i].d === 'a' && !isResponse(rows[i].m)) send(rows[i].m)
  let i = at + 1
  let answered = false
  for (; i < rows.length; i++) {
    const row = rows[i]
    if (row.d === 'c') { if (key(row.m)) break; continue }
    if (!answered && isResponseTo(row.m, captured)) { send(respond(live, captured, row.m)); answered = true; continue }
    if (!isResponse(row.m)) send(row.m)
  }
  cursor = i
  if (!answered && captured.type !== 'user') fallback(live)
}
createInterface({ input: process.stdin }).on('line', line => {
  if (!line.trim()) return
  const m = JSON.parse(line)
  if (process.env.REPLAY_TRACE) appendFileSync(process.env.REPLAY_TRACE, JSON.stringify(m) + '\n')
  handle(m)
})
