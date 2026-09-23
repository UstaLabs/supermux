import { AgentSideConnection, ndJsonStream } from '@agentclientprotocol/sdk';
import { Readable, Writable } from 'node:stream';
import { appendFileSync, writeFileSync } from 'node:fs';
const mode = process.env.FIXTURE_MODE;
if (process.env.TRACE) appendFileSync(process.env.TRACE, JSON.stringify({ argv: process.argv.slice(2) }) + '\n');
if (process.env.PID_FILE) writeFileSync(process.env.PID_FILE, String(process.pid));
if (mode === 'stubborn') process.on('SIGTERM', () => {});
let finish;
let promptActive = false;
let nativeTurn = false;
const record = (value) => { if (process.env.TRACE) appendFileSync(process.env.TRACE, JSON.stringify(value) + '\n'); };
async function emitNativeTurn(client, sessionId, { id, starts = 1, complete = true, delayCompleteMs = 0 } = {}) {
  const update = (sessionUpdate, extra = {}) => ({ sessionUpdate, ...(id ? { prompt_id: id } : {}), ...extra })
  for (let i = 0; i < starts; i++) {
    await client.extNotification('_x.ai/session/update', { sessionId, update: update('user_message_chunk', { content: { type: 'text', text: 'user' } }) })
  }
  const arm = () => { nativeTurn = true }
  if (process.env.NATIVE_SLOW === '1') setTimeout(arm, 80)
  else arm()
  if (!complete) return
  const done = async () => {
    await client.extNotification('_x.ai/session_notification', { sessionId, update: update('turn_completed') })
    nativeTurn = false
    finish?.({ stopReason: 'cancelled' })
  }
  if (delayCompleteMs) setTimeout(() => { void done() }, delayCompleteMs)
  else await done()
}
new AgentSideConnection(client => ({
 async initialize(params) {
  record(params);
  if (mode === 'exit') process.exit(17);
  if (mode === 'hang') return new Promise(() => {});
  return { protocolVersion: mode === 'version' ? 999 : 1, agentCapabilities: { loadSession: true, ...(mode === 'resume' ? { sessionCapabilities: { resume: {} } } : {}) }, authMethods: [{id:'token',name:'Token'}, {id:'terminal',name:'Terminal',type:'terminal'}] };
 },
 async authenticate({methodId}) { record({methodId, token:process.env.TOKEN}); if (process.env.TOKEN !== 'ok') throw new Error('authentication rejected'); return {}; },
 async newSession() {
  record('new');
  const sessionId = 'agent-1'
  if (process.env.AUTONOMOUS_ON_OPEN === '1') {
    queueMicrotask(() => { void emitNativeTurn(client, sessionId, { id: process.env.TURN_ID || 'auto-1', complete: process.env.AUTONOMOUS_HOLD !== '1' }) })
  }
  return {sessionId};
 },
 async setSessionConfigOption(params) { record({ setConfig: { configId: params.configId, value: params.value } }); return { configOptions: [] }; },
 async resumeSession(params) { record('resume'); return {}; },
 async loadSession(params) {
  record('load');
  if(params.sessionId === 'missing') throw new Error('missing session');
  await client.sessionUpdate({sessionId:params.sessionId,update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'history'}}});
  await client.extNotification('_x.ai/session/update', { sessionId: params.sessionId, update: { sessionUpdate: 'user_message_chunk', prompt_id: 'hist-1', content: { type: 'text', text: 'old' } } });
  await client.extNotification('_x.ai/session/update', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: 'hist-1' } });
  await client.extNotification('_x.ai/session_notification', { turn_completed: true, extra: { keep: true } });
  await client.notify('_x.ai/session/update', { unsolicitedturns: [{ id: 'u1' }] });
  if (process.env.LIVE_AFTER_LOAD === '1') {
    setTimeout(() => { void emitNativeTurn(client, params.sessionId, { id: 'live-1', starts: Number(process.env.START_CHUNKS || 1) }) }, 15)
  }
  return {};
 },
 async prompt(params) {
  const text = params.prompt[0].text;
  if(text === 'disconnect') { process.exit(19); return new Promise(()=>{}); }
  if(text === 'error') throw new Error('prompt rejected');
  if(text === 'hang') { promptActive = true; return new Promise(resolve => finish=resolve); }
  if(text === 'live-hang') {
    promptActive = true
    await emitNativeTurn(client, params.sessionId, { id: process.env.TURN_ID || 'live-turn', complete: false })
    const delay = Number(process.env.FINISH_AFTER_MS || 0)
    if (delay > 0) setTimeout(() => { promptActive = false; finish?.({ stopReason: 'end_turn' }) }, delay)
    return new Promise(resolve => { finish = (value) => { promptActive = false; nativeTurn = false; resolve(value) } })
  }
  if(text === 'slow-start' || text === 'very-slow-start') {
    record({prompt:text,state:'queued'});
    await new Promise(resolve => setTimeout(resolve, text === 'very-slow-start' ? 750 : 80));
    promptActive = true;
    record({prompt:text,state:'active'});
    return new Promise(resolve => { finish = (value) => { promptActive = false; resolve(value); }; });
  }
  if(text === 'permission') { promptActive = true; const response = await client.requestPermission({ sessionId:params.sessionId,toolCall:{toolCallId:'call-1',title:'Read'},options:[{optionId:'allow',name:'Allow',kind:'allow_once'}] }); record(response); promptActive = false; return {stopReason:response.outcome.outcome === 'cancelled' ? 'cancelled':'end_turn'}; }
  if(text === 'permission-kinds') {
    promptActive = true
    const response = await client.requestPermission({
      sessionId: params.sessionId,
      toolCall: { toolCallId: 'call-k', title: 'Read' },
      options: [
        { optionId: 'allow_once', name: 'Allow once', kind: 'allow_once' },
        { optionId: 'allow_always', name: 'Always', kind: 'allow_always' },
        { optionId: 'reject_once', name: 'Reject', kind: 'reject_once' },
        { optionId: 'reject_always', name: 'Never', kind: 'reject_always' },
      ],
    })
    record(response)
    promptActive = false
    return { stopReason: 'end_turn' }
  }
  if(text === 'auto-perm-late') {
    queueMicrotask(async () => {
      await emitNativeTurn(client, params.sessionId, { id: 'auto-B', complete: false })
      const pending = client.requestPermission({ sessionId: params.sessionId, toolCall: { toolCallId: 'late-perm', title: 'Late' }, options: [{ optionId: 'allow_once', name: 'Allow', kind: 'allow_once' }] })
      await client.extNotification('_x.ai/session_notification', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: 'auto-B' } })
      nativeTurn = false
      record({ permissionResponse: await pending, tool: 'late-perm' })
    })
    return { stopReason: 'end_turn' }
  }
  if(text === 'auto-perm-hold') {
    queueMicrotask(() => { void emitNativeTurn(client, params.sessionId, { id: 'auto-B', complete: false }) })
    return { stopReason: 'end_turn' }
  }
  if(text === 'delayed-perm') {
    promptActive = true
    return new Promise(resolve => { finish = (value) => { promptActive = false; resolve(value) } })
  }
  if(text === 'cancel-overlap-perm') {
    queueMicrotask(async () => {
      await emitNativeTurn(client, params.sessionId, { id: 'own-A', complete: false })
      const pendingA = client.requestPermission({ sessionId: params.sessionId, toolCall: { toolCallId: 'a-perm', title: 'A' }, options: [{ optionId: 'allow_once', name: 'Allow', kind: 'allow_once' }] })
      await new Promise(resolve => setTimeout(resolve, 80))
      await emitNativeTurn(client, params.sessionId, { id: 'own-B', complete: false })
      const pendingLate = client.requestPermission({ sessionId: params.sessionId, toolCall: { toolCallId: 'late-A', title: 'LateA' }, options: [{ optionId: 'allow_once', name: 'Allow', kind: 'allow_once' }] })
      record({ permissionResponse: await pendingA, tool: 'a-perm' })
      record({ permissionResponse: await pendingLate, tool: 'late-A' })
      await client.extNotification('_x.ai/session_notification', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: 'own-A' } })
      const pendingB = client.requestPermission({ sessionId: params.sessionId, toolCall: { toolCallId: 'b-perm', title: 'B' }, options: [{ optionId: 'allow_once', name: 'Allow', kind: 'allow_once' }] })
      record({ permissionResponse: await pendingB, tool: 'b-perm' })
      await client.extNotification('_x.ai/session_notification', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: 'own-B' } })
      nativeTurn = false
    })
    return { stopReason: 'end_turn' }
  }
  if(text === 'overflow-activity') {
    for (let i = 0; i < 257; i++) {
      await emitNativeTurn(client, params.sessionId, { id: `ov-${i}`, complete: false })
    }
    return { stopReason: 'end_turn' }
  }
  if(text === 'overlap-perm') {
    queueMicrotask(async () => {
      await emitNativeTurn(client, params.sessionId, { id: 'own-A', complete: false })
      const pending = client.requestPermission({ sessionId: params.sessionId, toolCall: { toolCallId: 'a-perm', title: 'A' }, options: [{ optionId: 'allow_once', name: 'Allow', kind: 'allow_once' }] })
      await emitNativeTurn(client, params.sessionId, { id: 'own-B', complete: false })
      await client.extNotification('_x.ai/session_notification', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: 'own-A' } })
      record({ permissionResponse: await pending, tool: 'a-perm' })
      await client.extNotification('_x.ai/session_notification', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: 'own-B' } })
      nativeTurn = false
    })
    return { stopReason: 'end_turn' }
  }
  if(text === 'post-complete-perm') {
    promptActive = true
    await emitNativeTurn(client, params.sessionId, { id: 'owned-1', complete: true })
    const response = await client.requestPermission({ sessionId: params.sessionId, toolCall: { toolCallId: 'stale-perm', title: 'Stale' }, options: [{ optionId: 'allow_once', name: 'Allow', kind: 'allow_once' }] })
    record({ permissionResponse: response, tool: 'stale-perm' })
    promptActive = false
    return { stopReason: response.outcome.outcome === 'cancelled' ? 'cancelled' : 'end_turn' }
  }
  if(text === 'native-hold') {
    promptActive = true
    await emitNativeTurn(client, params.sessionId, { id: 'owned-1', complete: false })
    return new Promise(resolve => { finish = (value) => { promptActive = false; nativeTurn = false; resolve(value) } })
  }
  if(text === 'handoff') {
    promptActive = true
    await emitNativeTurn(client, params.sessionId, { id: 'owned-1', complete: true })
    queueMicrotask(() => { void emitNativeTurn(client, params.sessionId, { id: 'auto-2', complete: process.env.HANDOFF_HOLD === '1' ? false : true }) })
    return { stopReason: 'end_turn' }
  }
  if(text === 'handoff-delay') {
    promptActive = true
    await emitNativeTurn(client, params.sessionId, { id: 'owned-1', complete: true })
    void emitNativeTurn(client, params.sessionId, { id: 'auto-2', complete: true, delayCompleteMs: 300 })
    await new Promise(resolve => setTimeout(resolve, 40))
    promptActive = false
    return { stopReason: 'end_turn' }
  }
  if(text === 'stale-complete') {
    await client.extNotification('_x.ai/session/update', { sessionId: params.sessionId, update: { sessionUpdate: 'user_message_chunk', prompt_id: 'live-a', content: { type: 'text', text: 'u' } } })
    await client.extNotification('_x.ai/session_notification', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: 'never-seen' } })
    await client.extNotification('_x.ai/session_notification', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: 'live-a' } })
    await client.extNotification('_x.ai/session_notification', { sessionId: params.sessionId, update: { sessionUpdate: 'turn_completed', prompt_id: 'live-a' } })
    return { stopReason: 'end_turn' }
  }
  if(text === 'repeat-start') {
    await emitNativeTurn(client, params.sessionId, { id: 'rep-1', starts: 3, complete: true })
    return { stopReason: 'end_turn' }
  }
  await client.sessionUpdate({sessionId:params.sessionId,update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'answer'}}});
  await client.extNotification('_x.ai/session_notification', { turn_completed: true, extra: { keep: true } });
  await client.notify('_x.ai/session/update', { unsolicitedturns: [{ id: 'u1' }] });
  return {stopReason:'end_turn'};
 },
 async cancel() {
  record({cancel:true,promptActive,nativeTurn});
  if (process.env.PERM_AFTER_CANCEL === '1' && nativeTurn) {
    const response = await client.requestPermission({ sessionId: 'agent-1', toolCall: { toolCallId: 'auto-perm', title: 'Auto' }, options: [{ optionId: 'allow_once', name: 'Allow', kind: 'allow_once' }] })
    record({ permissionResponse: response, tool: 'auto-perm' })
    return
  }
  if (process.env.DELAYED_PERM === '1' && promptActive) {
    await emitNativeTurn(client, 'agent-1', { id: 'delayed-A', complete: false })
    const response = await client.requestPermission({ sessionId: 'agent-1', toolCall: { toolCallId: 'delayed', title: 'Delayed' }, options: [{ optionId: 'allow_once', name: 'Allow', kind: 'allow_once' }] })
    record({ permissionResponse: response, tool: 'delayed' })
    finish?.({ stopReason: 'cancelled' })
    return
  }
  if (!promptActive && !nativeTurn) return;
  if (nativeTurn && !promptActive) {
    nativeTurn = false
    await client.extNotification('_x.ai/session_notification', { sessionId: 'agent-1', update: { sessionUpdate: 'turn_completed', prompt_id: process.env.TURN_ID || 'auto-1' } })
    return
  }
  finish?.({stopReason:'cancelled'});
 }
}), ndJsonStream(Writable.toWeb(process.stdout), Readable.toWeb(process.stdin)));
