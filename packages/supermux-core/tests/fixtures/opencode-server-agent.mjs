// Fake `opencode acp` for the subagent-permission side channel. Like real OpenCode 1.16.2 it
// serves ACP on stdio AND its HTTP API on `--port` (basic auth with OPENCODE_SERVER_PASSWORD),
// and it NEVER relays the child session's permission ask over ACP: the task's child waits until
// someone answers it through POST /permission/:id/reply.
import { createServer } from 'node:http'
import { appendFileSync } from 'node:fs'

const argv = process.argv.slice(2)
const port = Number(argv[argv.indexOf('--port') + 1])
const password = process.env.OPENCODE_SERVER_PASSWORD ?? ''
const trace = process.env.FAKE_TRACE
const log = (entry) => { if (trace) appendFileSync(trace, JSON.stringify(entry) + '\n') }
const PARENT = 'ses_parent', CHILD = 'ses_child', TASK = 'call_task'
let taskRunning = false
let ask // pending child ask
let answer // resolves with the reply

const expected = 'Basic ' + Buffer.from(`opencode:${password}`).toString('base64')
const http = createServer((req, res) => {
  const url = new URL(req.url, 'http://x')
  const send = (status, body) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(body)) }
  if (!password || req.headers.authorization !== expected) return send(401, { error: 'unauthorized' })
  if (req.method === 'GET' && url.pathname === '/permission') return send(200, ask ? [ask] : [])
  if (req.method === 'GET' && url.pathname === `/session/${PARENT}/message`) {
    const parts = taskRunning ? [{ type: 'tool', tool: 'task', callID: TASK, state: { status: 'running', metadata: { sessionId: CHILD, parentSessionId: PARENT } } }] : []
    return send(200, [{ info: { id: 'msg_1', sessionID: PARENT, role: 'assistant' }, parts }])
  }
  if (req.method === 'GET' && url.pathname === `/session/${CHILD}`) return send(200, { id: CHILD, parentID: PARENT })
  if (req.method === 'POST' && url.pathname === `/session/${CHILD}/abort`) {
    // Like OpenCode: aborting the child drops its pending ask and fails its task tool call.
    log({ aborted: CHILD })
    if (taskRunning) { ask = undefined; answer?.('aborted-http') }
    return send(200, true)
  }
  const m = /^\/permission\/([^/]+)\/reply$/.exec(url.pathname)
  if (req.method === 'POST' && m) {
    let body = ''
    req.on('data', d => { body += d })
    req.on('end', () => {
      const { reply } = JSON.parse(body || '{}')
      log({ reply, id: decodeURIComponent(m[1]) })
      if (!ask || ask.id !== decodeURIComponent(m[1])) return send(404, { error: 'not found' })
      ask = undefined
      answer?.(reply)
      send(200, true)
    })
    return
  }
  send(404, { error: 'no route' })
})
http.listen(port, '127.0.0.1')

const write = (msg) => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...msg }) + '\n')
const update = (sessionId, u) => write({ method: 'session/update', params: { sessionId, update: u } })

async function runTask(id) {
  update(PARENT, { sessionUpdate: 'tool_call', toolCallId: TASK, title: 'task', kind: 'think', status: 'pending', rawInput: {} })
  const input = { description: 'Run ls', prompt: 'Run ls and report.', subagent_type: 'general' }
  update(PARENT, { sessionUpdate: 'tool_call_update', toolCallId: TASK, status: 'in_progress', title: 'Run ls', kind: 'think', rawInput: input })
  taskRunning = true
  const reply = await new Promise(resolve => {
    answer = resolve
    // The child asks a moment later, once its session is running.
    setTimeout(() => { ask = { id: 'per_1', sessionID: CHILD, permission: 'bash', patterns: ['ls'], metadata: { command: 'ls' }, always: ['ls *'], tool: { messageID: 'msg_c', callID: 'call_bash' } } }, 150)
  })
  taskRunning = false
  const output = `<task id="${CHILD}" state="completed">\n<task_result>\nREPLY=${reply}\n</task_result>\n</task>`
  if (reply === 'aborted-http') {
    update(PARENT, { sessionUpdate: 'tool_call_update', toolCallId: TASK, status: 'failed', title: 'Run ls', kind: 'think', rawInput: input, rawOutput: { error: 'Tool execution aborted', metadata: { sessionId: CHILD, parentSessionId: PARENT } } })
    update(PARENT, { sessionUpdate: 'agent_message_chunk', messageId: 'msg_2', content: { type: 'text', text: 'the subagent was stopped' } })
    write({ id, result: { stopReason: 'end_turn' } })
    return
  }
  update(PARENT, { sessionUpdate: 'tool_call_update', toolCallId: TASK, status: 'completed', title: 'Run ls', kind: 'think', rawInput: input, content: [{ type: 'content', content: { type: 'text', text: output } }], rawOutput: { output, metadata: { sessionId: CHILD, parentSessionId: PARENT } } })
  update(PARENT, { sessionUpdate: 'agent_message_chunk', messageId: 'msg_2', content: { type: 'text', text: `subagent said ${reply}` } })
  write({ id, result: { stopReason: 'end_turn' } })
}

let buf = ''
process.stdin.on('data', d => {
  buf += d
  let i
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i); buf = buf.slice(i + 1)
    if (!line.trim()) continue
    const msg = JSON.parse(line)
    if (msg.method === undefined) continue
    if (msg.method === 'initialize') write({ id: msg.id, result: { protocolVersion: 1, agentCapabilities: { loadSession: true }, authMethods: [] } })
    else if (msg.method === 'session/new') write({ id: msg.id, result: { sessionId: PARENT } })
    else if (msg.method === 'session/prompt') void runTask(msg.id)
    else if (msg.method === 'session/cancel') {
      // Like OpenCode: aborting the parent aborts the child, whose pending ask is dropped unanswered.
      if (taskRunning) { log({ cancelled: true }); ask = undefined; answer?.('aborted') }
    }
    else if (msg.id !== undefined) write({ id: msg.id, error: { code: -32601, message: 'Method not found' } })
  }
})
process.stdin.on('end', () => process.exit(0))
