import { fileURLToPath } from 'node:url'
import { afterEach, test, expect, setDefaultTimeout } from 'bun:test'
import { mkdtempSync } from 'node:fs'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { acp, type AcpActivityHint } from '../src/acp/index.js'
import { createCore } from '../src/core.js'
import type { AgentUpdate, DriverContext } from '../src/types.js'
import { TEST_LIMITS, nextId } from "./helpers.js"

const dirs: string[] = []
afterEach(async () => {
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})
function testKeeper() {
  const stateDirectory = mkdtempSync(join(tmpdir(), 'acp-k-'))
  dirs.push(stateDirectory)
  return { stateDirectory, limits: { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 } }
}

function classifyActivity(update: AgentUpdate): AcpActivityHint | undefined {
  const from = (value: unknown): AcpActivityHint | undefined => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return
    const rec = value as Record<string, unknown>
    if (rec.sessionUpdate !== 'user_message_chunk' && rec.sessionUpdate !== 'turn_completed') return
    const id = typeof rec.prompt_id === 'string' ? rec.prompt_id : undefined
    return { phase: rec.sessionUpdate === 'user_message_chunk' ? 'started' : 'completed', ...(id ? { id } : {}) }
  }
  if (update.protocol === 'acp') return from(update.value)
  const frame = update.value as { method?: string; params?: { update?: unknown } }
  if (frame?.method !== '_x.ai/session_notification' && frame?.method !== '_x.ai/session/update') return
  return from(frame.params?.update)
}
setDefaultTimeout(20_000)
const fixture = fileURLToPath(new URL('./fixtures/acp-agent.mjs', import.meta.url))
const driver = (env = {}, extra = {}) => acp({id:'fixture',command:process.execPath,args:[fixture],env,inheritEnv:true,mcpServers:[],setupTimeoutMs:3000,shutdownTimeoutMs:40,maxFrameBytes:16*1024*1024,maxOutstandingActivity:256,cancelRetryIntervalMs:250,cancelRetryTimeoutMs:10_000,keeper:testKeeper(),classifyActivity,...extra})
function context(extra: Partial<DriverContext> = {}): DriverContext { return {sessionId:'core-1',cwd:process.cwd(),signal:new AbortController().signal,onUpdate(){},onExit(){},requestPermission:async()=>({outcome:{outcome:'cancelled'}}),...extra} }
test('create, prompt, preserve updates, and close process', async()=>{
 const dir=await mkdtemp(join(tmpdir(),'acp-')); const pidFile=join(dir,'pid'); const updates:any[]=[];
 const runtime=await driver({PID_FILE:pidFile}).open(context({onUpdate:u=>updates.push(u)}));
 expect(runtime.agentSessionId).toBe('agent-1'); expect(runtime.capabilities).toEqual({resume:true,steer:false,fork:false,detach:true});
 expect(await runtime.prompt([{type:'text',text:'hello'}],new AbortController().signal)).toEqual({stopReason:'end_turn'});
 expect(updates.find(u=>u.protocol==='native'&&u.value?.method==='initialize')).toBeTruthy();
 expect(updates.find(u=>u.protocol==='acp')!.value.content.text).toBe('answer');
 const pid = Number(await readFile(pidFile,'utf8')); await runtime.close({ mode: "shutdown" }); expect(()=>process.kill(pid,0)).toThrow();
 await rm(dir,{recursive:true});
})
test('load replay and never replace missing history',async()=>{
 const updates:any[]=[]; const runtime=await driver().open(context({resumeId:'old',onUpdate:u=>updates.push(u)}));
 expect(runtime.agentSessionId).toBe('old');
 expect(updates.some(u=>u.protocol==='acp'&&u.replay===true)).toBe(true);
 expect(updates.find(u=>u.protocol==='native'&&u.value?.method==='initialize')?.replay).toBeUndefined();
 const loadNotes=updates.filter(u=>u.protocol==='native'&&u.value?.method==='_x.ai/session_notification')
 expect(loadNotes.length).toBeGreaterThanOrEqual(1)
 expect(loadNotes[0].replay).toBe(true)
 expect(loadNotes.some(u=>u.replay===true && u.value.params?.turn_completed===true)).toBe(true)
 const loadVendor=updates.filter(u=>u.protocol==='native'&&u.value?.method==='_x.ai/session/update')
 expect(loadVendor.length).toBeGreaterThanOrEqual(1)
 expect(loadVendor.every(u=>u.replay===true)).toBe(true)
 await runtime.prompt([{type:'text',text:'hello'}],new AbortController().signal)
 const notes=updates.filter(u=>u.protocol==='native'&&u.value?.method==='_x.ai/session_notification')
 expect(notes.some(u=>!u.replay)).toBe(true)
 const vendors=updates.filter(u=>u.protocol==='native'&&u.value?.method==='_x.ai/session/update')
 expect(vendors.some(u=>!u.replay)).toBe(true)
 await runtime.close({ mode: "shutdown" });
 await expect(driver().open(context({resumeId:'missing'}))).rejects.toThrow();
})
test('auth honors profile env, exposes methods, rejects terminal and failed authentication',async()=>{
 const d=driver({TOKEN:'bad'}); const ctx={signal:new AbortController().signal,profile:{agent:'fixture',env:{TOKEN:'ok'},methodId:'token'}};
 expect((await d.auth!.methods(ctx)).map(x=>x.id)).toContain('token'); await d.auth!.login(ctx,'token');
 const r=await d.open(context(ctx)); await r.close({ mode: "shutdown" });
 await expect(d.auth!.login(ctx,'terminal')).rejects.toMatchObject({code:'unsupported_operation'});
 await expect(d.auth!.login({signal:ctx.signal},'token')).rejects.toThrow();
})
test('setup rejects incompatible versions, hangs, and early exits',async()=>{
 for(const mode of ['version','hang','exit']) await expect(driver({FIXTURE_MODE:mode}).open(context())).rejects.toThrow();
})
test('permission cancellation settles uncooperative callback and prompt',async()=>{
 let requested!:()=>void; const seen=new Promise<void>(r=>requested=r); let permissionSignal:AbortSignal|undefined;
 const r=await driver().open(context({requestPermission:async(_,signal)=>{permissionSignal=signal;requested();return new Promise(()=>{})}}));
 const controller=new AbortController();const result=r.prompt([{type:'text',text:'permission'}],controller.signal);
 await seen; controller.abort(); await r.interrupt(); expect(await result).toEqual({stopReason:'cancelled'});expect(permissionSignal!.aborted).toBe(true);await r.close({ mode: "shutdown" });
})
test('close settles outstanding prompt and escalates stubborn child',async()=>{
 const r=await driver({FIXTURE_MODE:'stubborn'}).open(context()); const result=r.prompt([{type:'text',text:'hang'}],new AbortController().signal).catch(e=>e);
 await new Promise(r=>setTimeout(r,20)); await r.close({ mode: "shutdown" });expect(await result).toBeInstanceOf(Error);
})

test('resume takes precedence over load without replay', async()=>{
 const dir=await mkdtemp(join(tmpdir(),'acp-')); const trace=join(dir,'trace');const updates:any[]=[];
 const r=await driver({FIXTURE_MODE:'resume',TRACE:trace}).open(context({resumeId:'old',onUpdate:u=>updates.push(u)}));await r.close({ mode: "shutdown" });
 const lines=(await readFile(trace,'utf8')).trim().split('\n').map(x=>JSON.parse(x));expect(lines).toContain('resume');expect(lines).not.toContain('load');expect(updates.filter(u=>u.protocol==='acp')).toEqual([]);
 expect(updates.some(u=>u.protocol==='native'&&u.value?.method==='initialize')).toBe(true);
 expect(lines[0].clientCapabilities).toMatchObject({fs:{readTextFile:false,writeTextFile:false},terminal:false});await rm(dir,{recursive:true});
})
test('failed setup and cancelled setup both reap their child',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'acp-')); const pidFile=join(dir,'pid');
 for(const mode of ['version','hang']) {
  await expect(driver({FIXTURE_MODE:mode,PID_FILE:pidFile}).open(context())).rejects.toThrow();
  const pid = Number(await readFile(pidFile,'utf8')); expect(()=>process.kill(pid,0)).toThrow();
 }
 const controller=new AbortController();const pending=driver({FIXTURE_MODE:'hang',PID_FILE:pidFile}).open(context({signal:controller.signal}));
 setTimeout(()=>controller.abort(),1500);await expect(pending).rejects.toThrow();
 const lastPid=Number(await readFile(pidFile,'utf8'));expect(()=>process.kill(lastPid,0)).toThrow();await rm(dir,{recursive:true});
})
test('prompt errors propagate and allowed permission response reaches agent',async()=>{
 const r=await driver().open(context({requestPermission:async req=>{expect(req.coreSessionId).toBe('core-1');return {outcome:{outcome:'selected',optionId:'allow'}}}}));
 try {
  await expect(r.prompt([{type:'text',text:'error'}],new AbortController().signal)).rejects.toThrow();
  expect(await r.prompt([{type:'text',text:'permission'}],new AbortController().signal)).toEqual({stopReason:'end_turn'});
 } finally {await r.close({ mode: "shutdown" })}
})

test('unexpected process exit rejects prompt and reports runtime failure',async()=>{
 let report!:(error:Error)=>void;const reported=new Promise<Error>(resolve=>report=resolve);
 const r=await driver().open(context({onExit:report}));
 try {
  await expect(r.prompt([{type:'text',text:'disconnect'}],new AbortController().signal)).rejects.toThrow();
  expect(await Promise.race([reported,new Promise(resolve=>setTimeout(()=>resolve('not reported'),200))])).toBeInstanceOf(Error);
 } finally {await r.close({ mode: "shutdown" })}
})
test('missing executable rejects open without unhandled stream failures',async()=>{
 await expect(driver({}, {command:'/definitely-not-a-real-acp-command'}).open(context())).rejects.toThrow();
})
test('acp() TypeError names each missing required field',()=>{
 const full:any={id:'fixture',command:process.execPath,args:[fixture],inheritEnv:true,mcpServers:[],setupTimeoutMs:3000,shutdownTimeoutMs:40,maxFrameBytes:1,maxOutstandingActivity:256,cancelRetryIntervalMs:250,cancelRetryTimeoutMs:10_000,keeper:testKeeper()}
 for(const field of ['id','command','args','inheritEnv','mcpServers','setupTimeoutMs','shutdownTimeoutMs','maxFrameBytes','maxOutstandingActivity','keeper','cancelRetryIntervalMs','cancelRetryTimeoutMs']){
  const opts={...full};delete opts[field]
  expect(()=>acp(opts)).toThrow(TypeError)
  expect(()=>acp(opts)).toThrow(new RegExp(`ACP ${field} is required`))
 }
})
test('invalid cancel retry window options are rejected',()=>{
 expect(()=>driver({}, {cancelRetryIntervalMs:0})).toThrow(TypeError)
 expect(()=>driver({}, {cancelRetryIntervalMs:-1})).toThrow(TypeError)
 expect(()=>driver({}, {cancelRetryIntervalMs:Number.NaN})).toThrow(TypeError)
 expect(()=>driver({}, {cancelRetryTimeoutMs:0})).toThrow(TypeError)
 expect(()=>driver({}, {cancelRetryTimeoutMs:Number.POSITIVE_INFINITY})).toThrow(TypeError)
})

test('early cancel retries until the delayed prompt honors it and does not leak into the next prompt',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'acp-')); const trace=join(dir,'trace');
 const r=await driver({TRACE:trace},{cancelRetryIntervalMs:25,cancelRetryTimeoutMs:500}).open(context());
 try {
  const result=r.prompt([{type:'text',text:'slow-start'}],new AbortController().signal);
  await r.interrupt();
  const first=await Promise.race([result,new Promise(resolve=>setTimeout(()=>resolve('one-shot-miss'),40))]);
  expect(first).toBe('one-shot-miss');
  expect(await result).toEqual({stopReason:'cancelled'});
  const afterCancel=(await readFile(trace,'utf8')).trim().split('\n').map(x=>JSON.parse(x));
  const cancels=afterCancel.filter(x=>x.cancel===true);
  expect(cancels[0]?.promptActive).toBe(false);
  expect(cancels.some(x=>x.promptActive===true)).toBe(true);
  expect(cancels.length).toBeGreaterThan(1);
  const cancelCount=cancels.length;
  await new Promise(resolve=>setTimeout(resolve,80));
  expect(await r.prompt([{type:'text',text:'hello'}],new AbortController().signal)).toEqual({stopReason:'end_turn'});
  const later=(await readFile(trace,'utf8')).trim().split('\n').map(x=>JSON.parse(x));
  expect(later.filter(x=>x.cancel===true)).toHaveLength(cancelCount);
  await r.interrupt();
  expect(await r.prompt([{type:'text',text:'hello'}],new AbortController().signal)).toEqual({stopReason:'end_turn'});
  const idle=(await readFile(trace,'utf8')).trim().split('\n').map(x=>JSON.parse(x));
  expect(idle.filter(x=>x.cancel===true)).toHaveLength(cancelCount);
 } finally { await r.close({ mode: "shutdown" }); await rm(dir,{recursive:true}); }
})
test('configured cancel retry budget covers activation delayed beyond 500ms without leaking later',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'acp-')); const trace=join(dir,'trace');
 const r=await driver({TRACE:trace},{cancelRetryIntervalMs:50,cancelRetryTimeoutMs:2000}).open(context());
 try {
  const result=r.prompt([{type:'text',text:'very-slow-start'}],new AbortController().signal);
  await r.interrupt();
  expect(await result).toEqual({stopReason:'cancelled'});
  const afterCancel=(await readFile(trace,'utf8')).trim().split('\n').map(x=>JSON.parse(x));
  const cancels=afterCancel.filter(x=>x.cancel===true);
  expect(cancels[0]?.promptActive).toBe(false);
  expect(cancels.some(x=>x.promptActive===true)).toBe(true);
  const cancelCount=cancels.length;
  expect(await r.prompt([{type:'text',text:'hello'}],new AbortController().signal)).toEqual({stopReason:'end_turn'});
  await r.interrupt();
  const idle=(await readFile(trace,'utf8')).trim().split('\n').map(x=>JSON.parse(x));
  expect(idle.filter(x=>x.cancel===true)).toHaveLength(cancelCount);
 } finally { await r.close({ mode: "shutdown" }); await rm(dir,{recursive:true}); }
})

test('replay coalesced history does not start activity; live load burst does', async () => {
  const activity: any[] = []
  const updates: any[] = []
  const r = await driver({ LIVE_AFTER_LOAD: '1', START_CHUNKS: '2' }).open(context({
    resumeId: 'old',
    onUpdate: u => updates.push(u),
    onActivity: n => activity.push({ ...n }),
  }))
  try {
    await new Promise(resolve => setTimeout(resolve, 50))
    expect(updates.some(u => u.replay === true && u.protocol === 'native' && u.value?.params?.update?.sessionUpdate === 'user_message_chunk')).toBe(true)
    expect(activity.filter(a => a.id === 'hist-1')).toEqual([])
    expect(activity.filter(a => a.id === 'live-1').map(a => a.phase)).toEqual(['started', 'completed'])
    const liveIdx = updates.findIndex(u => !u.replay && u.protocol === 'native' && u.value?.params?.update?.sessionUpdate === 'user_message_chunk' && u.value?.params?.update?.prompt_id === 'live-1')
    const liveDone = updates.findIndex(u => !u.replay && u.protocol === 'native' && u.value?.params?.update?.sessionUpdate === 'turn_completed' && u.value?.params?.update?.prompt_id === 'live-1')
    expect(liveIdx).toBeGreaterThan(-1)
    expect(liveDone).toBeGreaterThan(liveIdx)
  } finally { await r.close({ mode: "shutdown" }) }
})

test('repeated start chunks coalesce; stale id completion is ignored', async () => {
  const activity: any[] = []
  const r = await driver().open(context({ onActivity: n => activity.push({ ...n }) }))
  try {
    await r.prompt([{ type: 'text', text: 'repeat-start' }], new AbortController().signal)
    expect(activity.filter(a => a.id === 'rep-1').map(a => a.phase)).toEqual(['started', 'completed'])
    activity.length = 0
    await r.prompt([{ type: 'text', text: 'stale-complete' }], new AbortController().signal)
    expect(activity.map(a => `${a.id}:${a.phase}`)).toEqual(['live-a:started', 'live-a:completed'])
  } finally { await r.close({ mode: "shutdown" }) }
})

test('owned prompt reports overlapping native activity without a second receipt', async () => {
  const activity: any[] = []
  const r = await driver().open(context({ onActivity: n => activity.push({ ...n }) }))
  try {
    expect(await r.prompt([{ type: 'text', text: 'handoff' }], new AbortController().signal)).toEqual({ stopReason: 'end_turn' })
    await new Promise(resolve => setTimeout(resolve, 40))
    expect(activity.map(a => `${a.id}:${a.phase}`)).toEqual(['owned-1:started', 'owned-1:completed', 'auto-2:started', 'auto-2:completed'])
  } finally { await r.close({ mode: "shutdown" }) }
})

test('owned prompt response does not complete a later native generation', async () => {
  const activity: any[] = []
  const r = await driver().open(context({ onActivity: n => activity.push({ ...n }) }))
  try {
    expect(await r.prompt([{ type: 'text', text: 'handoff-delay' }], new AbortController().signal)).toEqual({ stopReason: 'end_turn' })
    expect(activity.map(a => `${a.id}:${a.phase}`)).toEqual(['owned-1:started', 'owned-1:completed', 'auto-2:started'])
    await new Promise(resolve => setTimeout(resolve, 400))
    expect(activity.map(a => `${a.id}:${a.phase}`)).toEqual(['owned-1:started', 'owned-1:completed', 'auto-2:started', 'auto-2:completed'])
  } finally { await r.close({ mode: "shutdown" }) }
})

test('autonomous interrupt retries until vendor end and does not leak into the next turn', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'acp-'))
  const trace = join(dir, 'trace')
  const activity: any[] = []
  const r = await driver({ TRACE: trace, AUTONOMOUS_ON_OPEN: '1', AUTONOMOUS_HOLD: '1', NATIVE_SLOW: '1', TURN_ID: 'auto-1' }, { cancelRetryIntervalMs: 25, cancelRetryTimeoutMs: 500 }).open(context({ onActivity: n => activity.push(n) }))
  try {
    const wait = Date.now()
    while (!activity.some(a => a.phase === 'started') && Date.now() - wait < 1000) await new Promise(r => setTimeout(r, 10))
    expect(activity.some(a => a.phase === 'started')).toBe(true)
    await r.interrupt()
    const after = async () => (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(x => JSON.parse(x))
    await new Promise(resolve => setTimeout(resolve, 200))
    const cancels = (await after()).filter(x => x.cancel === true)
    expect(cancels[0]?.nativeTurn).toBe(false)
    expect(cancels.some(x => x.nativeTurn === true)).toBe(true)
    expect(cancels.length).toBeGreaterThan(1)
    const cancelCount = cancels.length
    await new Promise(resolve => setTimeout(resolve, 80))
    expect(await r.prompt([{ type: 'text', text: 'hello' }], new AbortController().signal)).toEqual({ stopReason: 'end_turn' })
    await r.interrupt()
    expect((await after()).filter(x => x.cancel === true)).toHaveLength(cancelCount)
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true }) }
})

test('EOF fails the context without synthesizing activity completion', async () => {
  const activity: any[] = []
  let report!: (error: Error) => void
  const reported = new Promise<Error>(resolve => { report = resolve })
  const r = await driver({ AUTONOMOUS_ON_OPEN: '1', AUTONOMOUS_HOLD: '1', TURN_ID: 'eof-1' }).open(context({ onActivity: n => activity.push({ ...n }), onExit: report }))
  try {
    await new Promise(resolve => setTimeout(resolve, 30))
    expect(activity.some(a => a.phase === 'started')).toBe(true)
    const before = activity.filter(a => a.phase === 'completed').length
    await expect(r.prompt([{ type: 'text', text: 'disconnect' }], new AbortController().signal)).rejects.toThrow()
    expect(await reported).toBeInstanceOf(Error)
    expect(activity.filter(a => a.phase === 'completed').length).toBe(before)
  } finally { await r.close({ mode: "shutdown" }) }
})

test('interrupt of autonomous native denies later permission for that generation', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'acp-'))
  const trace = join(dir, 'trace')
  const activity: any[] = []
  let release!: () => void
  const gate = new Promise<void>(r => { release = r })
  const r = await driver({ TRACE: trace, PERM_AFTER_CANCEL: '1' }).open(context({
    onActivity: n => activity.push({ ...n }),
    requestPermission: async (_req, signal) => {
      await gate
      if (signal.aborted) return { outcome: { outcome: 'cancelled' } }
      return { outcome: { outcome: 'selected', optionId: 'allow_once' } }
    },
  }))
  try {
    expect(await r.prompt([{ type: 'text', text: 'auto-perm-hold' }], new AbortController().signal)).toEqual({ stopReason: 'end_turn' })
    const wait = Date.now()
    while (!activity.some(a => a.id === 'auto-B' && a.phase === 'started') && Date.now() - wait < 1000) await new Promise(r => setTimeout(r, 10))
    await r.interrupt()
    release()
    await new Promise(r => setTimeout(r, 80))
    const lines = (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(x => JSON.parse(x))
    const perm = lines.find(x => x.tool === 'auto-perm')
    expect(perm?.permissionResponse?.outcome?.outcome).toBe('cancelled')
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true }) }
})

test('interrupt before native start keeps delayed generation permission aborted', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'acp-'))
  const trace = join(dir, 'trace')
  const r = await driver({ TRACE: trace, DELAYED_PERM: '1' }).open(context({
    requestPermission: async () => ({ outcome: { outcome: 'selected', optionId: 'allow_once' } }),
  }))
  try {
    const pending = r.prompt([{ type: 'text', text: 'delayed-perm' }], new AbortController().signal)
    await new Promise(r => setTimeout(r, 30))
    await r.interrupt()
    expect(await pending).toEqual({ stopReason: 'cancelled' })
    const lines = (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(x => JSON.parse(x))
    const perm = lines.find(x => x.tool === 'delayed')
    expect(perm?.permissionResponse?.outcome?.outcome).toBe('cancelled')
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true }) }
})

test('ambiguous permission is denied while cancelled A and live B are both outstanding; unique B after A end is allowed', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'acp-'))
  const trace = join(dir, 'trace')
  const activity: any[] = []
  const r = await driver({ TRACE: trace }).open(context({
    onActivity: n => activity.push({ ...n }),
    requestPermission: async (req, signal) => {
      if (req.toolCall.toolCallId === 'a-perm') await r.interrupt()
      if (signal.aborted) return { outcome: { outcome: 'cancelled' } }
      return { outcome: { outcome: 'selected', optionId: 'allow_once' } }
    },
  }))
  try {
    expect(await r.prompt([{ type: 'text', text: 'cancel-overlap-perm' }], new AbortController().signal)).toEqual({ stopReason: 'end_turn' })
    const wait = Date.now()
    while (!activity.some(a => a.id === 'own-B' && a.phase === 'completed') && Date.now() - wait < 2000) await new Promise(res => setTimeout(res, 15))
    const lines = (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(x => JSON.parse(x))
    expect(lines.find(x => x.tool === 'late-A')?.permissionResponse?.outcome?.outcome).toBe('cancelled')
    expect(lines.find(x => x.tool === 'b-perm')?.permissionResponse?.outcome?.outcome).toBe('selected')
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true }) }
})

test('native activity tracking fails loudly at the shared outstanding cap', async () => {
  let exit: Error | undefined
  const r = await driver().open(context({ onExit: error => { exit = error } }))
  try {
    await r.prompt([{ type: 'text', text: 'overflow-activity' }], new AbortController().signal).catch(() => {})
    const wait = Date.now()
    while (!exit && Date.now() - wait < 2000) await new Promise(res => setTimeout(res, 15))
    expect(exit).toMatchObject({ code: 'activity_overflow' })
  } finally { await r.close({ mode: "shutdown" }) }
})

test('ending native A cancels its pending permission while B is still active', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'acp-'))
  const trace = join(dir, 'trace')
  const activity: any[] = []
  let release!: () => void
  const gate = new Promise<void>(r => { release = r })
  const r = await driver({ TRACE: trace }).open(context({
    onActivity: n => activity.push({ ...n }),
    requestPermission: async (_req, signal) => {
      await gate
      if (signal.aborted) return { outcome: { outcome: 'cancelled' } }
      return { outcome: { outcome: 'selected', optionId: 'allow_once' } }
    },
  }))
  try {
    expect(await r.prompt([{ type: 'text', text: 'overlap-perm' }], new AbortController().signal)).toEqual({ stopReason: 'end_turn' })
    const wait = Date.now()
    while (!activity.some(a => a.id === 'own-A' && a.phase === 'completed') && Date.now() - wait < 1000) await new Promise(r => setTimeout(r, 10))
    release()
    await new Promise(r => setTimeout(r, 80))
    const lines = (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(x => JSON.parse(x))
    const perm = lines.find(x => x.tool === 'a-perm')
    expect(perm?.permissionResponse?.outcome?.outcome).toBe('cancelled')
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true }) }
})

test('permission after native completion does not borrow owned prompt lifetime', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'acp-'))
  const trace = join(dir, 'trace')
  const r = await driver({ TRACE: trace }).open(context({
    requestPermission: async () => ({ outcome: { outcome: 'selected', optionId: 'allow_once' } }),
  }))
  try {
    expect(await r.prompt([{ type: 'text', text: 'post-complete-perm' }], new AbortController().signal)).toEqual({ stopReason: 'cancelled' })
    const lines = (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(x => JSON.parse(x))
    const perm = lines.find(x => x.tool === 'stale-perm')
    expect(perm?.permissionResponse?.outcome?.outcome).toBe('cancelled')
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true }) }
})

test('native activity end cancels pending permission even if host later allows', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'acp-'))
  const trace = join(dir, 'trace')
  const activity: any[] = []
  let release!: () => void
  const gate = new Promise<void>(r => { release = r })
  const r = await driver({ TRACE: trace }).open(context({
    onActivity: n => activity.push({ ...n }),
    requestPermission: async (_req, signal) => {
      await gate
      if (signal.aborted) return { outcome: { outcome: 'cancelled' } }
      return { outcome: { outcome: 'selected', optionId: 'allow_once' } }
    },
  }))
  try {
    expect(await r.prompt([{ type: 'text', text: 'auto-perm-late' }], new AbortController().signal)).toEqual({ stopReason: 'end_turn' })
    const wait = Date.now()
    while (!activity.some(a => a.id === 'auto-B' && a.phase === 'completed') && Date.now() - wait < 1000) await new Promise(r => setTimeout(r, 10))
    release()
    await new Promise(r => setTimeout(r, 80))
    const lines = (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(x => JSON.parse(x))
    const perm = lines.find(x => x.tool === 'late-perm')
    expect(perm?.permissionResponse?.outcome?.outcome).toBe('cancelled')
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true }) }
})

test('autonomous native begin drives core running, queue, reject, and configure busy', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'acp-core-'))
  const core = createCore({
    stateDirectory,
    agents: [driver({ AUTONOMOUS_ON_OPEN: '1', AUTONOMOUS_HOLD: '1', TURN_ID: 'core-1' }, { cancelRetryIntervalMs: 25, cancelRetryTimeoutMs: 800 })],
    limits: { ...TEST_LIMITS, interruptTimeoutMs: 2_000 },
  })
  try {
    const session = await core.sessions.create({ id: nextId(), agent: 'fixture', cwd: process.cwd() })
    const started = Date.now()
    while (session.snapshot().state !== 'running' && Date.now() - started < 1000) await new Promise(r => setTimeout(r, 10))
    expect(session.snapshot().state).toBe('running')
    await expect(session.send({ content: [{ type: 'text', text: 'hello' }], whenBusy: 'reject' })).rejects.toMatchObject({ code: 'session_busy' })
    const queued = session.send({ content: [{ type: 'text', text: 'hello' }], whenBusy: 'queue' })
    await expect(session.configure({ model: 'x' })).rejects.toThrow()
    expect(await session.interrupt({ pending: "discard" })).toEqual({ status: 'stopped' })
    expect((await (await queued).completed).status).toBe('cancelled')
    session.pending.continue()
    const after = await session.send({ content: [{ type: 'text', text: 'hello' }], whenBusy: 'queue' })
    expect((await after.completed).status).toBe('completed')
  } finally { await core.close({ agents: "shutdown" }); await rm(stateDirectory, { recursive: true, force: true }) }
})
test('detach leaves the agent alive', async()=>{
 const dir=await mkdtemp(join(tmpdir(),'acp-detach-')); const pidFile=join(dir,'pid')
 const runtime=await driver({PID_FILE:pidFile}).open(context())
 const pid=Number(await readFile(pidFile,'utf8'))
 await runtime.close({ mode: 'detach' })
 expect(()=>process.kill(pid,0)).not.toThrow()
 try { process.kill(pid, 'SIGKILL') } catch { /* */ }
 await rm(dir,{recursive:true})
})
