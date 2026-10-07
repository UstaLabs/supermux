import {createInterface} from 'node:readline'
import {appendFileSync, readFileSync, writeFileSync} from 'node:fs'
import {spawn} from 'node:child_process'

const argv = process.argv.slice(2)
if (process.env.PID_FILE) writeFileSync(process.env.PID_FILE, String(process.pid))
// Accounts tests: what the driver launched with.
if (process.env.ENV_TRACE) writeFileSync(process.env.ENV_TRACE, JSON.stringify({ argv: process.argv.slice(2), env: process.env }))
if (process.env.MODE === 'stubborn') process.on('SIGTERM', () => {})
if (process.env.TRACE) appendFileSync(process.env.TRACE, JSON.stringify({argv}) + '\n')

const flag = (name) => {
  const eq = argv.find(a => a.startsWith(name + '='))
  if (eq) return eq.slice(name.length + 1)
  const i = argv.indexOf(name)
  if (i < 0) return undefined
  const next = argv[i + 1]
  if (next === undefined || next.startsWith('-')) return true
  return next
}

const send = o => process.stdout.write(JSON.stringify(o) + '\n')

// FIXTURE_MCP=1: start every stdio server of every --mcp-config like Claude does (an MCP client
// that initializes at startup); a user turn "mcp-call <server> <tool> <json args>" calls a tool.
const mcp = new Map()
if (process.env.FIXTURE_MCP === '1') {
  const configs = argv.flatMap((a, i) => a === '--mcp-config' && argv[i + 1] ? [argv[i + 1]] : [])
  for (const path of configs) {
    for (const [name, spec] of Object.entries(JSON.parse(readFileSync(path, 'utf8')).mcpServers ?? {})) {
      const child = spawn(spec.command, spec.args ?? [], {stdio: ['pipe', 'pipe', 'ignore'], env: {...process.env, ...spec.env}})
      const client = {child, next: 1, waiting: new Map(), notifications: []}
      client.request = (method, params) => new Promise(resolve => {
        const id = client.next++
        client.waiting.set(id, resolve)
        child.stdin.write(JSON.stringify({jsonrpc: '2.0', id, method, params}) + '\n')
      })
      createInterface({input: child.stdout}).on('line', line => {
        const message = JSON.parse(line)
        if (message.id !== undefined && message.method === undefined) { client.waiting.get(message.id)?.(message); client.waiting.delete(message.id) }
        else if (message.method) client.notifications.push(message.method)
      })
      client.ready = client.request('initialize', {protocolVersion: '2025-11-25', capabilities: {}, clientInfo: {name: 'claude-fixture', version: '1'}})
        .then(() => child.stdin.write(JSON.stringify({jsonrpc: '2.0', method: 'notifications/initialized'}) + '\n'))
      mcp.set(name, client)
    }
  }
}
async function mcpCall(target, line) {
  const [, server, tool, ...rest] = line.split(' ')
  const client = mcp.get(server)
  let reply
  if (!client) reply = `no MCP server ${server}`
  else {
    await client.ready
    const response = await client.request('tools/call', {name: tool, arguments: JSON.parse(rest.join(' ') || '{}')})
    reply = response.error ? `MCP error ${response.error.code}: ${response.error.message}` : `MCP ${response.result.isError ? 'isError ' : ''}${response.result.content?.[0]?.text ?? ''}`
  }
  if (hanging !== target) return
  hanging = undefined
  send({type: 'assistant', session_id: target.session, uuid: target.uuid, user_message_uuid: target.uuid, message: {role: 'assistant', content: [{type: 'text', text: reply}]}})
  finish(target, 'end_turn')
}
const CREATE_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const resume = flag('--resume')
const sessionIdArg = flag('--session-id')
let session = resume && resume !== 'missing' ? resume : sessionIdArg || CREATE_ID
let initialized = false
let hanging
let turn = 0
let replayDuplicate = false
let latePermission = false
let changedInputReplay = false
let cancelAfterAllow = false
let expectDenyNext = false
let crossTurnAsks = 0

function fail(code, message) {
  process.stderr.write(message + '\n')
  process.exit(code)
}

if (!argv.includes('--print') || flag('--input-format') !== 'stream-json' || flag('--output-format') !== 'stream-json' || !argv.includes('--verbose')) fail(10, 'missing stream-json protocol flags')
if (!argv.includes('--await-initialize')) fail(11, 'missing --await-initialize')
if (argv.includes('--bare') || argv.includes('--tmux') || argv.includes('--no-session-persistence') || argv.includes('--fork-session')) fail(12, 'unsafe or history-breaking flags')
if (process.env.EXPECT_DEFAULT_DENY !== '0') {
  if (flag('--tools') !== '') fail(13, 'tools must default to disabled')
  if (flag('--permission-prompts') !== 'none') fail(14, 'permission prompts must default to none')
}
if (process.env.EXPECT_MODEL && flag('--model') !== process.env.EXPECT_MODEL) fail(15, 'model mismatch')
if (process.env.EXPECT_EFFORT && flag('--effort') !== process.env.EXPECT_EFFORT) fail(16, 'effort mismatch')

if (process.env.MODE === 'setup-hang') {
  setInterval(() => {}, 1 << 30)
} else {
  let first = true
  createInterface({input: process.stdin}).on('line', line => {
    if (process.env.TRACE) appendFileSync(process.env.TRACE, line + '\n')
    let m
    try { m = JSON.parse(line) } catch { fail(18, 'malformed stdin') }
    if (first) {
      first = false
      if (m?.type !== 'control_request' || m.request?.subtype !== 'initialize' || typeof m.request_id !== 'string') fail(19, 'first line must be initialize')
      if (process.env.MODE === 'init-hang') return
      if (resume === 'missing') {
        send({type: 'control_response', response: {subtype: 'error', request_id: m.request_id, error: 'missing session'}})
        process.exit(17)
        return
      }
      // Early identity mismatch must arrive before initialize ACK so open() cannot
      // resolve from control_response while the wrong system/init is still queued.
      if (process.env.MODE === 'wrong-resume-early') {
        send(initFrame('ffffffff-ffff-4fff-8fff-ffffffffffff'))
      }
      send({type: 'control_response', response: {subtype: 'success', request_id: m.request_id, response: {commands: [], models: [], session_state: 'idle'}}})
      initialized = true
      return
    }
    if (!initialized) fail(20, 'not initialized')
    if (m.type === 'control_request' && ['reload_plugins', 'reload_skills', 'mcp_set_servers'].includes(m.request?.subtype)) {
      // Session context in flight (C1b): record what the driver asked for.
      if (process.env.CONTROL_TRACE) appendFileSync(process.env.CONTROL_TRACE, JSON.stringify(m.request) + '\n')
      const held = m.request.subtype === 'reload_plugins' && m.request.hold_on_cache_impact === true && process.env.HOLD === '1'
      const response = m.request.subtype === 'mcp_set_servers' ? {added: Object.keys(m.request.servers ?? {}), removed: [], errors: {}} : {plugins: [], ...(m.request.hold_on_cache_impact ? {held, ...(held ? {cache_impact: {tools: 1}} : {})} : {})}
      send({type: 'control_response', response: {subtype: 'success', request_id: m.request_id, response}})
      return
    }
    if (m.type === 'control_request' && m.request?.subtype === 'set_permission_mode') {
      send({type: 'control_response', response: {subtype: 'success', request_id: m.request_id, response: {mode: m.request.mode}}})
      return
    }
    if (m.type === 'control_request' && m.request?.subtype === 'interrupt') {
      if (process.env.MODE === 'interrupt-hang') return
      if (process.env.MODE === 'interrupt-fail') {
        send({type: 'control_response', response: {subtype: 'error', request_id: m.request_id, error: 'interrupt failed'}})
        if (hanging) {
          send({type: 'result', subtype: 'success', is_error: false, session_id: hanging.session, uuid: hanging.uuid, user_message_uuid: hanging.uuid, stop_reason: 'end_turn', result: 'ok', duration_ms: 1, duration_api_ms: 1, num_turns: 1, total_cost_usd: 0, usage: {}, modelUsage: {}, permission_denials: []})
          hanging = undefined
        }
        return
      }
      send({type: 'control_response', response: {subtype: 'success', request_id: m.request_id, response: {}}})
      if (hanging) {
        send({type: 'result', subtype: 'success', is_error: false, session_id: hanging.session, uuid: hanging.uuid, user_message_uuid: hanging.uuid, stop_reason: 'cancelled', result: '', duration_ms: 1, duration_api_ms: 1, num_turns: 1, total_cost_usd: 0, usage: {}, modelUsage: {}, permission_denials: []})
        hanging = undefined
      }
      return
    }
    if (m.type === 'control_response') {
      if (typeof m.response?.request_id === 'string' && m.response.request_id.startsWith('perm-')) {
        const behavior = m.response?.response?.behavior
        if (latePermission) {
          if (behavior === 'allow') fail(4, 'late allow')
          latePermission = false
          return
        }
        if (changedInputReplay) {
          if (behavior !== 'allow') fail(4, 'expected allow before changed-input replay')
          changedInputReplay = false
          expectDenyNext = true
          send({type: 'control_request', request_id: 'perm-1', request: {subtype: 'can_use_tool', tool_name: 'Bash', input: {command: 'rm -rf /'}}})
          return
        }
        if (cancelAfterAllow) {
          if (behavior !== 'allow') fail(4, 'expected allow before cancel-after-allow')
          cancelAfterAllow = false
          expectDenyNext = true
          send({type: 'control_cancel_request', request_id: 'perm-1'})
          send({type: 'control_request', request_id: 'perm-1', request: {subtype: 'can_use_tool', tool_name: 'Bash', input: {command: 'pwd'}}})
          return
        }
        if (expectDenyNext || crossTurnAsks > 1) {
          if (behavior !== 'deny') fail(4, 'expected deny without stale allow')
          expectDenyNext = false
        } else if (process.env.EXPECT_PERM === 'allow') {
          if (behavior !== 'allow') fail(4, 'expected allow')
          const updated = m.response?.response?.updatedInput
          if (!updated || updated.command !== 'pwd') fail(5, 'updatedInput mismatch')
        } else if (process.env.EXPECT_PERM === 'always') {
          if (behavior !== 'allow') fail(4, 'expected allow always')
          const perms = m.response?.response?.updatedPermissions
          if (!Array.isArray(perms) || !perms.some(p => p?.type === 'addRules')) fail(5, 'updatedPermissions mismatch')
        } else if (process.env.EXPECT_PERM === 'deny-message') {
          if (behavior !== 'deny') fail(4, 'expected deny')
          if (m.response?.response?.message !== (process.env.EXPECT_DENY_MESSAGE || 'Denied by user')) fail(5, 'deny message mismatch')
        } else if (process.env.EXPECT_PERM === 'question') {
          if (behavior !== 'allow') fail(4, 'expected allow')
          const answers = m.response?.response?.updatedInput?.answers
          if (!answers || answers['Favorite color?'] !== 'Blue') fail(5, 'answers mismatch')
        } else if (behavior !== 'deny') fail(4, 'expected deny')
        if (replayDuplicate) {
          replayDuplicate = false
          const request = {subtype: 'can_use_tool', tool_name: 'Bash', input: {command: 'pwd'}}
          send({type: 'control_request', request_id: 'perm-1', request})
          return
        }
        if (hanging && process.env.PERM_WAIT_INTERRUPT !== '1') {
          finish(hanging, 'end_turn')
          hanging = undefined
        }
      }
      return
    }
    if (m.type !== 'user') return
    const content = m.message?.content
    const text = typeof content === 'string' ? content : content?.[0]?.text
    const uuid = m.uuid
    turn++
    if (turn === 1) {
      const id = process.env.MODE === 'wrong-resume' ? 'ffffffff-ffff-4fff-8fff-ffffffffffff' : session
      send(initFrame(id))
    }
    // Broker UI journeys: the real answer shape, echoing the prompt.
    if (process.env.MODE === 'echo') {
      send({type: 'assistant', session_id: session, uuid, user_message_uuid: uuid, message: {role: 'assistant', content: [{type: 'text', text: `Fixture reply: ${text}`}]}})
      finish({session, uuid}, 'end_turn')
      return
    }
    if (text === 'disconnect') { process.exit(1) }
    if (text === 'eof') { process.stdout.end(() => process.exit(0)); return }
    if (text === 'malformed') { process.stdout.write('{bad}\n'); return }
    if (text === 'oversize') { process.stdout.write('x'.repeat(5000)); return }
    if (text === 'hang') { hanging = {session, uuid}; return }
    if (typeof text === 'string' && text.startsWith('mcp-call ')) { hanging = {session, uuid}; void mcpCall(hanging, text); return }
    if (text === 'hang-then-result') {
      hanging = {session, uuid}
      setTimeout(() => {
        if (hanging) { finish(hanging, 'end_turn'); hanging = undefined }
      }, 1200)
      return
    }
    if (text === 'ask-user-question') {
      hanging = {session, uuid}
      send({type: 'control_request', request_id: 'perm-q', request: {
        subtype: 'can_use_tool',
        tool_name: 'AskUserQuestion',
        tool_use_id: 'tool-q',
        input: { questions: [{ question: 'Favorite color?', header: 'Color', options: [{ label: 'Blue' }, { label: 'Red' }], multiSelect: false }] },
      }})
      return
    }
    if (text === 'permission' || text === 'permission-tool-use-id' || text === 'permission-duplicate' || text === 'permission-native-cancel' || text === 'permission-late' || text === 'permission-cross-turn' || text === 'permission-changed-input' || text === 'permission-cancel-after-allow' || text === 'permission-always') {
      hanging = {session, uuid}
      const request = {subtype: 'can_use_tool', tool_name: 'Bash', input: {command: 'pwd'}}
      if (text === 'permission-tool-use-id') request.tool_use_id = 'tool-99'
      if (text === 'permission-always') {
        request.permission_suggestions = [{ type: 'addRules', rules: [{ toolName: 'Bash', ruleContent: 'pwd' }], behavior: 'allow', destination: 'session' }]
        request.blocked_path = '/tmp/x'
      }
      send({type: 'control_request', request_id: 'perm-1', request})
      if (text === 'permission-duplicate') replayDuplicate = true
      if (text === 'permission-changed-input') changedInputReplay = true
      if (text === 'permission-cancel-after-allow') cancelAfterAllow = true
      if (text === 'permission-cross-turn') crossTurnAsks++
      if (text === 'permission-native-cancel') send({type: 'control_cancel_request', request_id: 'perm-1'})
      if (text === 'permission-late') {
        latePermission = true
        finish(hanging, 'end_turn')
        hanging = undefined
      }
      return
    }
    send({type: 'assistant', session_id: 'other-session', message: {role: 'assistant', content: [{type: 'text', text: 'wrong'}]}})
    send({type: 'result', subtype: 'success', is_error: false, session_id: 'other-session', uuid: 'other-turn', stop_reason: 'end_turn', result: 'wrong', duration_ms: 1, duration_api_ms: 1, num_turns: 1, total_cost_usd: 0, usage: {}, modelUsage: {}, permission_denials: []})
    send({type: 'assistant', session_id: session, uuid, user_message_uuid: uuid, message: {role: 'assistant', content: [{type: 'text', text: 'héllo'}]}})
    finish({session, uuid}, text === 'fail' ? 'failed' : 'end_turn')
  })
}

function initFrame(id) {
  return {type: 'system', subtype: 'init', session_id: id, cwd: process.cwd(), tools: [], mcp_servers: [], model: flag('--model') ?? 'sonnet', permissionMode: 'dontAsk', slash_commands: [], output_style: 'default', skills: [], plugins: [], claude_code_version: 'fixture', apiKeySource: 'none'}
}

function finish(target, status) {
  if (status === 'failed') {
    send({type: 'result', subtype: 'error_during_execution', is_error: true, session_id: target.session, uuid: target.uuid, user_message_uuid: target.uuid, stop_reason: 'error', errors: ['turn failed'], duration_ms: 1, duration_api_ms: 1, num_turns: 1, total_cost_usd: 0, usage: {}, modelUsage: {}, permission_denials: []})
    return
  }
  send({type: 'result', subtype: 'success', is_error: false, session_id: target.session, uuid: target.uuid, user_message_uuid: target.uuid, stop_reason: status, result: 'ok', duration_ms: 1, duration_api_ms: 1, num_turns: 1, total_cost_usd: 0, usage: {}, modelUsage: {}, permission_denials: []})
}

void sessionIdArg
void turn
