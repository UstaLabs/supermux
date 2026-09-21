#!/usr/bin/env node
import { AgentSideConnection, ndJsonStream } from '@agentclientprotocol/sdk'
import { Readable, Writable } from 'node:stream'
import { appendFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs'

if (process.env.PID_FILE) writeFileSync(process.env.PID_FILE, String(process.pid))
const record = (value) => { if (process.env.TRACE) appendFileSync(process.env.TRACE, JSON.stringify(value) + '\n') }
record({ argv: process.argv.slice(2) })

function bumpResumes() {
  const file = process.env.FAIL_COUNT_FILE
  if (!file) return ++resumeAttempts
  const n = (existsSync(file) ? Number(readFileSync(file, 'utf8')) : 0) + 1
  writeFileSync(file, String(n))
  return n
}

let resumeAttempts = 0
let finish
let promptActive = false
let nativeTurn = false

async function emitNative(client, sessionId, id = 'auto-1', complete = true) {
  const start = process.env.NO_START_ID === '1'
    ? { sessionUpdate: 'user_message_chunk', content: { type: 'text', text: 'user' }, _meta: { modelId: 'grok-4.6', promptIndex: 0 } }
    : { sessionUpdate: 'user_message_chunk', prompt_id: id, content: { type: 'text', text: 'user' } }
  if (process.env.NO_START_ID === '1') {
    await client.sessionUpdate({ sessionId, update: start })
  } else {
    await client.extNotification('_x.ai/session/update', { sessionId, update: start })
  }
  nativeTurn = true
  if (!complete) return
  await client.extNotification('_x.ai/session_notification', { sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: id } })
  nativeTurn = false
}

async function extensions(client) {
  await client.extNotification('_x.ai/session_notification', { turn_completed: true, extra: { keep: true } })
  await client.notify('_x.ai/session/update', { unsolicitedturns: [{ id: 'u1' }] })
}

new AgentSideConnection(client => ({
  async initialize() {
    if (process.env.FIXTURE_MODE === 'exit') process.exit(17)
    if (process.env.FIXTURE_MODE === 'hang') return new Promise(() => {})
    return {
      protocolVersion: process.env.FIXTURE_MODE === 'version' ? 999 : 1,
      agentCapabilities: { loadSession: true },
      authMethods: [],
    }
  },
  async authenticate() { return {} },
  async newSession() {
    record({ method: 'newSession' })
    if (process.env.FAIL_NEW === '1') throw new Error('new session forbidden')
    const sessionId = process.env.SESSION_ID || 'grok-1'
    if (process.env.AUTONOMOUS_ON_OPEN === '1') {
      setTimeout(() => { void emitNative(client, sessionId, process.env.TURN_ID || 'auto-1', process.env.AUTONOMOUS_HOLD !== '1') }, 15)
    }
    return { sessionId }
  },
  async resumeSession(params) {
    record({ method: 'resumeSession', sessionId: params.sessionId })
    throw new Error('resumeSession not advertised')
  },
  async loadSession(params) {
    const attempts = bumpResumes()
    record({ method: 'loadSession', sessionId: params.sessionId, resumeAttempts: attempts })
    if (process.env.FAIL_RESUME === '1') throw new Error('resume failed')
    if (process.env.FAIL_OPEN_ONCE === '1' && attempts === 1) throw new Error('candidate open failed')
    if (params.sessionId === 'missing') throw new Error('missing session')
    await client.sessionUpdate({ sessionId: params.sessionId, update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'history' } } })
    await client.extNotification('_x.ai/session/update', { sessionId: params.sessionId, update: { sessionUpdate: 'user_message_chunk', prompt_id: 'hist-1', content: { type: 'text', text: 'old' } } })
    await client.extNotification('_x.ai/session/update', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: 'hist-1' } })
    await extensions(client)
    if (process.env.LIVE_AFTER_LOAD === '1') {
      setTimeout(() => { void emitNative(client, params.sessionId, 'live-1', true) }, 15)
    }
    return {}
  },
  async prompt(params) {
    const text = params.prompt[0].text
    if (text === 'disconnect') { process.exit(19); return new Promise(() => {}) }
    if (text === 'hang') { promptActive = true; return new Promise(resolve => { finish = resolve }) }
    if (text === 'native-hold') {
      promptActive = true
      await emitNative(client, params.sessionId, 'owned-1', false)
      return new Promise(resolve => { finish = (value) => { promptActive = false; nativeTurn = false; resolve(value) } })
    }
    if (text === 'stale-uuid') {
      await client.sessionUpdate({ sessionId: params.sessionId, update: { sessionUpdate: 'user_message_chunk', content: { type: 'text', text: 'next' }, _meta: { modelId: 'grok-4.6', promptIndex: 1 } } })
      await client.extNotification('_x.ai/session_notification', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: process.env.TURN_ID || 'same-uuid' } })
      return { stopReason: 'end_turn' }
    }
    if (text === 'explicit-adversarial') {
      await emitNative(client, params.sessionId, 'explicit-A', false)
      await client.extNotification('_x.ai/session_notification', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: 'UNKNOWN-B' } })
      await client.extNotification('_x.ai/session_notification', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: 'STALE-C' } })
      await client.extNotification('_x.ai/session_notification', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed' } })
      await new Promise(resolve => setTimeout(resolve, 180))
      await client.extNotification('_x.ai/session_notification', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: 'explicit-A' } })
      nativeTurn = false
      return { stopReason: 'end_turn' }
    }
    if (text === 'ask-user-question') {
      const response = await client.extMethod('_x.ai/ask_user_question', {
        sessionId: params.sessionId,
        toolCallId: 'tc-1',
        questions: [{ question: 'Favorite color?', options: [{ label: 'Blue', description: 'cool' }, { label: 'Red' }], multiSelect: false }],
        mode: 'interview',
      })
      record(response)
      return { stopReason: 'end_turn' }
    }
    await client.sessionUpdate({ sessionId: params.sessionId, update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'answer' } } })
    await extensions(client)
    return { stopReason: 'end_turn' }
  },
  async cancel() {
    record({ cancel: true, promptActive, nativeTurn })
    if (!promptActive && !nativeTurn) return
    if (nativeTurn && !promptActive) {
      nativeTurn = false
      const id = process.env.TURN_ID || 'auto-1'
      await client.extNotification('_x.ai/session_notification', { sessionId: process.env.SESSION_ID || 'grok-1', update: { sessionUpdate: 'turn_completed', prompt_id: id } })
      return
    }
    finish?.({ stopReason: 'cancelled' })
  },
}), ndJsonStream(Writable.toWeb(process.stdout), Readable.toWeb(process.stdin)))
