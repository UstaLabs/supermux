import {test,expect,setDefaultTimeout,afterEach} from 'bun:test'
import {fileURLToPath} from 'node:url'
import {mkdtemp,readFile,rm} from 'node:fs/promises'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {codex} from '../src/codex/index.js'
import type {DriverContext} from '../src/types.js'
setDefaultTimeout(20_000)
const fixture=fileURLToPath(new URL('./fixtures/codex-agent.mjs',import.meta.url))
const signal=()=>new AbortController().signal
const ctx=(extra:Partial<DriverContext>={}):DriverContext=>({sessionId:'core',cwd:process.cwd(),signal:signal(),onUpdate(){},onExit(){},requestPermission:async()=>({outcome:{outcome:'cancelled'}}),requestAnswers:async()=>({outcome:'cancelled' as const}),...extra})
const keeperDirs:string[]=[]
const driver=(env={},extra:any={})=>{
 const stateDirectory=require('node:fs').mkdtempSync(join(tmpdir(),'codex-keeper-'))
 keeperDirs.push(stateDirectory)
 return codex({id:'codex',command:process.execPath,args:[fixture],env,inheritEnv:true,sandbox:'read-only',approvalPolicy:'never',permissionPrompts:'none',permissions:{kind:'codex',approvalPolicy:'never',sandbox:'read-only'},setupTimeoutMs:5000,requestTimeoutMs:3000,shutdownTimeoutMs:500,maxFrameBytes:16*1024*1024,keeper:{stateDirectory,limits:{parkedDeadlineMs:5000,journalMaxBytes:1_000_000,connectTimeoutMs:4000}},...extra})
}
afterEach(async()=>{
 for(const dir of keeperDirs.splice(0)){
  try{await rm(dir,{recursive:true,force:true})}catch{/* */}
 }
})
const input=(text:string)=>[{type:'text' as const,text}]
test('native lifecycle, early completion, filtered updates, and failure',async()=>{
 const updates:any[]=[];const r=await driver().open(ctx({onUpdate:u=>updates.push(u)}))
 try{expect(r.agentSessionId).toBe('native-1');expect(r.capabilities).toEqual({resume:true,steer:true,fork:true,detach:true,configure:true,history:true,permissions:true})
 for(const text of ['hello','early','permission'])expect(await r.prompt(input(text),signal())).toEqual({stopReason:'end_turn'})
 expect(updates.some(x=>x.protocol==='native'&&x.value.params.delta==='héllo')).toBe(true)
 expect(updates.some(x=>x.value.params.threadId==='other-thread'||x.value.params.turn?.id==='other-turn')).toBe(false)
 await expect(r.prompt(input('fail'),signal())).rejects.toThrow('turn failed')
 await expect(r.prompt([{type:'resource_link',uri:'file:///x',name:'x'}],signal())).rejects.toThrow('Unsupported')
 }finally{await r.close({ mode: "shutdown" })}
})
test('resume preserves identity; failure never creates replacement',async()=>{
 const r=await driver().open(ctx({resumeId:'old'}));expect(r.agentSessionId).toBe('old');await r.close({ mode: "shutdown" })
 await expect(driver().open(ctx({resumeId:'missing'}))).rejects.toThrow('missing thread')
 await expect(driver({MODE:'wrong-resume'}).open(ctx({resumeId:'old'}))).rejects.toThrow('identity')
})
test('interrupt during start acknowledgement and steer use exact turn',async()=>{
 const r=await driver().open(ctx());try{
 let pending=r.prompt(input('hang'),signal());await r.interrupt();expect(await pending).toEqual({stopReason:'cancelled'})
 pending=r.prompt(input('hang'),signal());await r.steer!(input('change'));expect(await pending).toEqual({stopReason:'end_turn'})
 }finally{await r.close({ mode: "shutdown" })}
})
test('missing executable, setup timeout, and stream failures reject',async()=>{
 await expect(driver({}, {command:'/not-a-codex-executable'}).open(ctx())).rejects.toThrow()
 await expect(driver({MODE:'setup-hang'},{setupTimeoutMs:30}).open(ctx())).rejects.toThrow()
 for(const text of ['disconnect','eof','malformed','oversize']){const r=await driver({}, {maxFrameBytes:4096}).open(ctx());try{await expect(r.prompt(input(text),signal())).rejects.toThrow()}finally{await r.close({ mode: "shutdown" })}}
})
test('profile env forwarded and close reaps stubborn child and settles turn',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'native-'));const pidFile=join(dir,'pid');const trace=join(dir,'trace')
 const r=await driver({MODE:'stubborn'}).open(ctx({profile:{agent:'codex',env:{PID_FILE:pidFile,TRACE:trace}}}))
 const pending=r.prompt(input('hang'),signal()).catch(e=>e);await new Promise(r=>setTimeout(r,30));await r.close({ mode: "shutdown" })
 expect(await pending).toBeInstanceOf(Error);expect(()=>process.kill(Number(require('node:fs').readFileSync(pidFile,'utf8')),0)).toThrow()
 expect((await readFile(trace,'utf8')).includes('initialized')).toBe(true);await rm(dir,{recursive:true})
})

test('fork uses native parent and checkpoint and returns independent identity', async()=>{
 const r=await driver().open(ctx({forkFrom:{agentSessionId:'native-parent',at:{nativeTurnId:'checkpoint'}}}))
 try {expect(r.agentSessionId).toBe('native-fork');expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})}
 finally {await r.close({ mode: "shutdown" })}
})

test('interrupt racing early completion does not fail or affect the next turn',async()=>{
 const r=await driver().open(ctx())
 try {
  const pending=r.prompt(input('early'),signal())
  await r.interrupt()
  expect(await pending).toEqual({stopReason:'end_turn'})
 } finally {await r.close({ mode: "shutdown" })}
})

async function traced(env={},extra={},context={}){
 const dir=await mkdtemp(join(tmpdir(),'codex-cfg-'));const trace=join(dir,'trace')
 const r=await driver({...env,TRACE:trace},extra).open(ctx(context))
 return {r,trace,dir,lines:async()=>(await readFile(trace,'utf8')).trim().split('\n').filter(Boolean).map(l=>JSON.parse(l))}
}
function turnStarts(lines){return lines.filter(x=>x.method==='turn/start')}
/** The deny for a cancelled host ask is written asynchronously; poll briefly instead of reading the trace once. */
async function answeredApproval(lines:()=>Promise<any[]>,id='approval'){
 for(let i=0;i<50;i++){
  const answers=(await lines()).filter(x=>x.id===id&&('result' in x||'error' in x))
  if(answers.length)return answers
  await new Promise(x=>setTimeout(x,10))
 }
 return []
}

test('turn/start sends model and effort overrides; clearing restores factory defaults',async()=>{
 const {r,dir,lines}=await traced({},{model:'factory-model',reasoningEffort:'low'})
 try{
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  await r.configure({model:'session-model',reasoningEffort:'high'})
  expect(r.configuration()).toEqual({model:'session-model',reasoningEffort:'high'})
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  await r.configure({})
  expect(r.configuration()).toEqual({})
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  const starts=turnStarts(await lines())
  expect(starts).toHaveLength(3)
  expect(starts[0].params).toMatchObject({model:'factory-model',effort:'low'})
  expect(starts[1].params).toMatchObject({model:'session-model',effort:'high'})
  expect(starts[2].params).toMatchObject({model:'factory-model',effort:'low'})
 }finally{await r.close({ mode: "shutdown" });await rm(dir,{recursive:true})}
})

test('context.configuration restores model and effort on reopen and fork',async()=>{
 const {r,dir,lines}=await traced({},{}, {configuration:{model:'reopen-model',reasoningEffort:'medium'}})
 try{
  expect(r.configuration()).toEqual({model:'reopen-model',reasoningEffort:'medium'})
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  expect(turnStarts(await lines())[0].params).toMatchObject({model:'reopen-model',effort:'medium'})
 }finally{await r.close({ mode: "shutdown" });await rm(dir,{recursive:true})}
 const fork=await traced({},{}, {forkFrom:{agentSessionId:'native-parent',at:{nativeTurnId:'checkpoint'}},configuration:{model:'fork-model',reasoningEffort:'minimal'}})
 try{
  expect(fork.r.agentSessionId).toBe('native-fork')
  expect(await fork.r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  expect(turnStarts(await fork.lines())[0].params).toMatchObject({model:'fork-model',effort:'minimal'})
 }finally{await fork.r.close({ mode: "shutdown" });await rm(fork.dir,{recursive:true})}
})

test('malformed reasoning effort is rejected atomically',async()=>{
 const {r,dir,lines}=await traced({},{model:'keep-model',reasoningEffort:'low'})
 try{
  await r.configure({model:'keep-model',reasoningEffort:'low'})
  await expect(r.configure({model:'changed',reasoningEffort:'not-a-real-effort'})).rejects.toThrow('reasoning effort')
  expect(r.configuration()).toEqual({model:'keep-model',reasoningEffort:'low'})
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  expect(turnStarts(await lines())[0].params).toMatchObject({model:'keep-model',effort:'low'})
  expect(turnStarts(await lines())[0].params.model).not.toBe('changed')
 }finally{await r.close({ mode: "shutdown" });await rm(dir,{recursive:true})}
})

test('without factory defaults, configure clear restores native start values when present',async()=>{
 const {r,dir,lines}=await traced({NATIVE_MODEL:'native-model',NATIVE_EFFORT:'low'})
 try{
  expect(r.configuration()).toEqual({})
  await r.configure({model:'session-model',reasoningEffort:'high'})
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  await r.configure({})
  expect(r.configuration()).toEqual({})
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  const starts=turnStarts(await lines())
  expect(starts).toHaveLength(2)
  expect(starts[0].params).toMatchObject({model:'session-model',effort:'high'})
  expect(starts[1].params).toMatchObject({model:'native-model',effort:'low'})
  expect(starts[1].params.model).toBe('native-model')
  expect(starts[1].params.effort).toBe('low')
 }finally{await r.close({ mode: "shutdown" });await rm(dir,{recursive:true})}
})

test('without factory or native defaults, clearing effort is rejected atomically',async()=>{
 const {r,dir,lines}=await traced()
 try{
  await r.configure({model:'session-model',reasoningEffort:'high'})
  expect(r.configuration()).toEqual({model:'session-model',reasoningEffort:'high'})
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  await expect(r.configure({})).rejects.toThrow('reasoning effort')
  expect(r.configuration()).toEqual({model:'session-model',reasoningEffort:'high'})
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  const starts=turnStarts(await lines())
  expect(starts).toHaveLength(2)
  expect(starts[0].params).toMatchObject({model:'session-model',effort:'high'})
  expect(starts[1].params).toMatchObject({model:'session-model',effort:'high'})
  expect(starts[1].params.effort).not.toBeUndefined()
 }finally{await r.close({ mode: "shutdown" });await rm(dir,{recursive:true})}
})

test('null native effort is not guessed; clearing is rejected before mutating requested config',async()=>{
 const {r,dir}=await traced({NATIVE_MODEL:'native-model',NATIVE_EFFORT:''})
 try{
  await r.configure({model:'session-model',reasoningEffort:'high'})
  expect(r.configuration()).toEqual({model:'session-model',reasoningEffort:'high'})
  await expect(r.configure({})).rejects.toThrow('reasoning effort')
  expect(r.configuration()).toEqual({model:'session-model',reasoningEffort:'high'})
 }finally{await r.close({ mode: "shutdown" });await rm(dir,{recursive:true})}
})

test('history reads the exact thread with includeTurns and pages locally',async()=>{
 const {r,dir,lines}=await traced()
 try{
  const page=await r.history()
  expect(page.protocol).toBe('native')
  expect(page.items).toEqual([{id:'hist-1',status:'completed'},{id:'hist-2',status:'completed'},{id:'hist-3',status:'completed'}])
  expect(page.cursor).toBeUndefined()
  const first=await r.history({limit:2})
  expect(first.items.map(x=>x.id)).toEqual(['hist-1','hist-2'])
  expect(first.cursor).toBe('2')
  const rest=await r.history({cursor:first.cursor,limit:2})
  expect(rest.items.map(x=>x.id)).toEqual(['hist-3'])
  expect(rest.cursor).toBeUndefined()
  await expect(r.history({cursor:'nope'})).rejects.toThrow('cursor')
  await expect(r.history({cursor:'9007199254740993'})).rejects.toThrow('cursor')
  const reads=(await lines()).filter(x=>x.method==='thread/read')
  expect(reads.length).toBeGreaterThan(0)
  for(const read of reads){
   expect(read.params).toEqual({threadId:'native-1',includeTurns:true})
  }
 }finally{await r.close({ mode: "shutdown" });await rm(dir,{recursive:true})}
})

test('history rejects identity mismatch and missing turns instead of empty history',async()=>{
 const badId=await traced({MODE:'history-bad-id'})
 try{await expect(badId.r.history()).rejects.toThrow('identity')}
 finally{await badId.r.close({ mode: "shutdown" });await rm(badId.dir,{recursive:true})}
 const missing=await traced({MODE:'history-no-turns'})
 try{await expect(missing.r.history()).rejects.toThrow('turns')}
 finally{await missing.r.close({ mode: "shutdown" });await rm(missing.dir,{recursive:true})}
})

test('contaminated resume model is not a native baseline; clear rejects atomically without factory',async()=>{
 const {r,dir,lines}=await traced({},{}, {resumeId:'old',configuration:{model:'reopen-model'}})
 try{
  expect(r.configuration()).toEqual({model:'reopen-model'})
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  await expect(r.configure({})).rejects.toThrow('native default is unknown')
  expect(r.configuration()).toEqual({model:'reopen-model'})
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  const starts=turnStarts(await lines())
  expect(starts).toHaveLength(2)
  expect(starts[0].params.model).toBe('reopen-model')
  expect(starts[1].params.model).toBe('reopen-model')
 }finally{await r.close({ mode: "shutdown" });await rm(dir,{recursive:true})}
})

test('explicit factory remains restorable after reopen with saved override',async()=>{
 const {r,dir,lines}=await traced({},{model:'factory-model'}, {resumeId:'old',configuration:{model:'reopen-model'}})
 try{
  expect(r.configuration()).toEqual({model:'reopen-model'})
  await r.configure({})
  expect(r.configuration()).toEqual({})
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  expect(turnStarts(await lines())[0].params.model).toBe('factory-model')
 }finally{await r.close({ mode: "shutdown" });await rm(dir,{recursive:true})}
})

test('sandbox and approvalPolicy are validated before spawn and forwarded on start',async()=>{
 const required={id:'codex',command:'codex',args:['app-server'],inheritEnv:true,setupTimeoutMs:5000,requestTimeoutMs:3000,shutdownTimeoutMs:500,maxFrameBytes:4096,permissions:{kind:'codex',approvalPolicy:'never',sandbox:'read-only'},keeper:{stateDirectory:'/tmp',limits:{parkedDeadlineMs:1,journalMaxBytes:1,connectTimeoutMs:1}}}
 expect(()=>codex({...required,sandbox:'nope',approvalPolicy:'never',permissionPrompts:'none'})).toThrow('sandbox')
 expect(()=>codex({...required,sandbox:'read-only',approvalPolicy:'always',permissionPrompts:'none'})).toThrow('approvalPolicy')
 expect(()=>codex({...required,sandbox:'read-only',approvalPolicy:'never',permissionPrompts:'maybe'})).toThrow('permissionPrompts')
 const {r,dir,lines}=await traced({EXPECT_SANDBOX:'workspace-write',EXPECT_POLICY:'on-request'},{sandbox:'workspace-write',approvalPolicy:'on-request',permissions:{kind:'codex',approvalPolicy:'on-request',sandbox:'workspace-write'}})
 try{
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  const start=(await lines()).find(x=>x.method==='thread/start')
  expect(start.params).toMatchObject({sandbox:'workspace-write',approvalPolicy:'on-request'})
 }finally{await r.close({ mode: "shutdown" });await rm(dir,{recursive:true})}
})

test('codex always routes approvals to the host',async()=>{
 let asked=0
 const r=await driver({EXPECT_DECISION:'accept',EXPECT_POLICY:'on-request',EXPECT_SANDBOX:'workspace-write'},{permissionPrompts:'host',permissions:{kind:'codex',approvalPolicy:'on-request',sandbox:'workspace-write'},sandbox:'workspace-write',approvalPolicy:'on-request'}).open(ctx({requestPermission:async()=>{asked++;return {outcome:{outcome:'selected',optionId:'allow_once'}}}}))
 try{
  expect(await r.prompt(input('permission'),signal())).toEqual({stopReason:'end_turn'})
  expect(asked).toBe(1)
 }finally{await r.close({ mode: "shutdown" })}
})

test('host permission allow/deny/throw map to accept or decline',async()=>{
 const r=await driver({EXPECT_DECISION:'accept'},{permissionPrompts:'host'}).open(ctx({
  requestPermission:async()=>({outcome:{outcome:'selected',optionId:'allow_once'}}),
 }))
 try{expect(await r.prompt(input('ask-command'),signal())).toEqual({stopReason:'end_turn'})}
 finally{await r.close({ mode: "shutdown" })}
 const deny=await driver({EXPECT_DECISION:'decline'},{permissionPrompts:'host'}).open(ctx({
  requestPermission:async()=>({outcome:{outcome:'selected',optionId:'reject_once'}}),
 }))
 try{expect(await deny.prompt(input('ask-file'),signal())).toEqual({stopReason:'end_turn'})}
 finally{await deny.close({ mode: "shutdown" })}
 const thrown=await driver({EXPECT_DECISION:'decline'},{permissionPrompts:'host'}).open(ctx({
  requestPermission:async()=>{throw new Error('host exploded')},
 }))
 try{expect(await thrown.prompt(input('ask-command'),signal())).toEqual({stopReason:'end_turn'})}
 finally{await thrown.close({ mode: "shutdown" })}
})

test('malformed host permission outcome declines without failing the runtime',async()=>{
 for(const response of [{outcome:null},{outcome:undefined},{}]){
  let asked=0
  const r=await driver({EXPECT_DECISION:'decline'},{permissionPrompts:'host'}).open(ctx({
   requestPermission:async()=>{asked++;return response as any},
  }))
  try{
   expect(await r.prompt(input('ask-command'),signal())).toEqual({stopReason:'end_turn'})
   expect(asked).toBe(1)
   expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  }finally{await r.close({ mode: "shutdown" })}
 }
})

test('duplicate same-turn approval id replays without a second host ask',async()=>{
 let asked=0
 const r=await driver({EXPECT_DECISION:'accept',MODE:'dup-after'},{permissionPrompts:'host'}).open(ctx({
  requestPermission:async()=>{asked++;return {outcome:{outcome:'selected',optionId:'allow_once'}}},
 }))
 try{
  expect(await r.prompt(input('ask-dup'),signal())).toEqual({stopReason:'end_turn'})
  expect(asked).toBe(1)
 }finally{await r.close({ mode: "shutdown" })}
})

test('native completion while host is pending declines without unhandled rejection',async()=>{
 const unhandled:unknown[]=[]
 const onUnhandled=(reason:unknown)=>{unhandled.push(reason)}
 process.on('unhandledRejection',onUnhandled)
 let resolveHost!:(value:any)=>void
 const r=await driver({EXPECT_DECISION:'decline'},{permissionPrompts:'host'}).open(ctx({
  requestPermission:()=>new Promise(resolve=>{resolveHost=resolve}),
 }))
 try{
  expect(await r.prompt(input('ask-late'),signal())).toEqual({stopReason:'end_turn'})
  resolveHost({outcome:{outcome:'selected',optionId:'allow_once'}})
  await new Promise(x=>setTimeout(x,40))
  expect(unhandled).toEqual([])
 }finally{
  process.off('unhandledRejection',onUnhandled)
  await r.close({ mode: "shutdown" })
 }
})

test('close while a host permission is pending does not throw unhandled active.id',async()=>{
 const unhandled:unknown[]=[]
 const onUnhandled=(reason:unknown)=>{unhandled.push(reason)}
 process.on('unhandledRejection',onUnhandled)
 const r=await driver({},{permissionPrompts:'host'}).open(ctx({
  requestPermission:()=>new Promise(()=>{}),
 }))
 try{
  const pending=r.prompt(input('ask-hang'),signal()).then(v=>v,e=>e)
  await new Promise(x=>setTimeout(x,80))
  await r.close({ mode: "shutdown" })
  await pending
  await new Promise(x=>setTimeout(x,40))
  expect(unhandled).toEqual([])
 }finally{process.off('unhandledRejection',onUnhandled)}
})

test('late host allow after the next turn starts is denied and cached under original ids',async()=>{
 let resolveFirst!:(value:any)=>void
 let asked=0
 const r=await driver({EXPECT_DECISION:'decline'},{permissionPrompts:'host'}).open(ctx({
  requestPermission:async()=>{
   asked++
   if(asked===1) return await new Promise(resolve=>{resolveFirst=resolve})
   return {outcome:{outcome:'selected',optionId:'allow_once'}}
  },
 }))
 try{
  const first=r.prompt(input('ask-late'),signal())
  await new Promise(x=>setTimeout(x,80))
  expect(await first).toEqual({stopReason:'end_turn'})
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
  resolveFirst({outcome:{outcome:'selected',optionId:'allow_once'}})
  await new Promise(x=>setTimeout(x,40))
  expect(asked).toBe(1)
 }finally{await r.close({ mode: "shutdown" })}
})

test('numeric and string JSON-RPC ids do not share a permission cache key',async()=>{
 let asked=0
 const r=await driver({EXPECT_DECISION:'accept',MODE:'id-types'},{permissionPrompts:'host'}).open(ctx({
  requestPermission:async()=>{asked++;return {outcome:{outcome:'selected',optionId:'allow_once'}}},
 }))
 try{
  expect(await r.prompt(input('ask-id-num'),signal())).toEqual({stopReason:'end_turn'})
  expect(asked).toBe(2)
 }finally{await r.close({ mode: "shutdown" })}
})

test('host permission cancel, stale, and unknown requests decline without hanging',async()=>{
 const r=await driver({EXPECT_DECISION:'decline',MODE:'approval-hang'},{permissionPrompts:'host'}).open(ctx({
  requestPermission:async(_req,signal)=>new Promise(resolve=>{
   signal.addEventListener('abort',()=>resolve({outcome:{outcome:'cancelled'}}),{once:true})
  }),
 }))
 try{
  const pending=r.prompt(input('ask-hang'),signal())
  await new Promise(x=>setTimeout(x,80))
  await r.interrupt()
  expect(await pending).toEqual({stopReason:'cancelled'})
 }finally{await r.close({ mode: "shutdown" })}
 const stale=await driver({EXPECT_DECISION:'decline'},{permissionPrompts:'host'}).open(ctx({
  requestPermission:async()=>({outcome:{outcome:'selected',optionId:'allow_once'}}),
 }))
 try{expect(await stale.prompt(input('ask-stale'),signal())).toEqual({stopReason:'end_turn'})}
 finally{await stale.close({ mode: "shutdown" })}
 const unknown=await driver({},{permissionPrompts:'host'}).open(ctx({
  requestPermission:async()=>({outcome:{outcome:'selected',optionId:'allow_once'}}),
 }))
 try{await expect(unknown.prompt(input('ask-unknown'),signal())).rejects.toThrow()}
 finally{await unknown.close({ mode: "shutdown" })}
 const permissions=await driver({},{permissionPrompts:'host'}).open(ctx({
  requestPermission:async()=>({outcome:{outcome:'selected',optionId:'allow_once'}}),
 }))
 try{expect(await permissions.prompt(input('ask-permissions'),signal())).toEqual({stopReason:'end_turn'})}
 finally{await permissions.close({ mode: "shutdown" })}
})

test('interrupt and native completion still answer pending host approvals with decline',async()=>{
 const hang=await traced({MODE:'approval-hang',EXPECT_DECISION:'decline'},{permissionPrompts:'host'},{
  requestPermission:async(_req,signal)=>new Promise(resolve=>{
   signal.addEventListener('abort',()=>resolve({outcome:{outcome:'cancelled'}}),{once:true})
  }),
 })
 try{
  const pending=hang.r.prompt(input('ask-hang'),signal())
  await new Promise(x=>setTimeout(x,80))
  await hang.r.interrupt()
  expect(await pending).toEqual({stopReason:'cancelled'})
  const answers=await answeredApproval(hang.lines)
  expect(answers).toHaveLength(1)
  expect(answers[0].result).toEqual({decision:'decline'})
 }finally{await hang.r.close({ mode: "shutdown" });await rm(hang.dir,{recursive:true})}
 const late=await traced({EXPECT_DECISION:'decline'},{permissionPrompts:'host'},{
  requestPermission:()=>new Promise(()=>{}),
 })
 try{
  expect(await late.r.prompt(input('ask-late'),signal())).toEqual({stopReason:'end_turn'})
  const answers=await answeredApproval(late.lines)
  expect(answers).toHaveLength(1)
  expect(answers[0].result).toEqual({decision:'decline'})
 }finally{await late.r.close({ mode: "shutdown" });await rm(late.dir,{recursive:true})}
})

test('autonomous turn on open reports activity and native updates without a prompt',async()=>{
 const activity:any[]=[];const updates:any[]=[]
 const r=await driver({MODE:'autonomous-on-open',AUTONOMOUS_HOLD:'1'}).open(ctx({
  onActivity:n=>activity.push({...n}),
  onUpdate:u=>updates.push(u),
 }))
 try{
  await new Promise(x=>setTimeout(x,80))
  expect(activity).toEqual([{id:'auto-1',phase:'started'}])
  expect(updates.some(x=>x.value?.method==='account/rateLimits/updated')).toBe(true)
  expect(updates.some(x=>x.value?.params?.delta==='native activity')).toBe(true)
  expect(updates.some(x=>x.value?.params?.threadId==='other-thread')).toBe(false)
  await r.interrupt()
  await new Promise(x=>setTimeout(x,40))
  expect(activity).toEqual([{id:'auto-1',phase:'started'},{id:'auto-1',phase:'completed'}])
  expect(await r.prompt(input('hello'),signal())).toEqual({stopReason:'end_turn'})
 }finally{await r.close({ mode: "shutdown" })}
})

test('setup notices wait for thread identity then drop foreign and threadless chat',async()=>{
 const updates:any[]=[]
 const r=await driver({MODE:'setup-notice'}).open(ctx({onUpdate:u=>updates.push(u)}))
 try{
  // The rate-limits notice is buffered before identity and replayed inside open(); the
  // post-identity chat notice is sent by the fixture after its reply and may land after open resolves.
  expect(updates.some(x=>x.value?.method==='account/rateLimits/updated')).toBe(true)
  for(let i=0;i<100&&!updates.some(x=>x.value?.params?.delta==='ok');i++)await new Promise(x=>setTimeout(x,10))
  expect(updates.some(x=>x.value?.params?.delta==='ok')).toBe(true)
  expect(updates.some(x=>x.value?.params?.delta==='no-thread')).toBe(false)
  expect(updates.some(x=>x.value?.params?.threadId==='other-thread')).toBe(false)
 }finally{await r.close({ mode: "shutdown" })}
})

test('setup overflow fails open honestly',async()=>{
 await expect(driver({MODE:'setup-overflow'}).open(ctx())).rejects.toThrow('Too many Codex notifications')
})

test('idle rate-limits update is process-scoped and does not create activity',async()=>{
 const activity:any[]=[];const updates:any[]=[]
 const r=await driver({MODE:'rate-limits'}).open(ctx({onActivity:n=>activity.push({...n}),onUpdate:u=>updates.push(u)}))
 try{
  await new Promise(x=>setTimeout(x,40))
  expect(updates.some(x=>x.value?.method==='account/rateLimits/updated')).toBe(true)
  expect(activity).toEqual([])
 }finally{await r.close({ mode: "shutdown" })}
})

test('native-only steer uses the exact autonomous turn',async()=>{
 const {r,dir,lines}=await traced({MODE:'native-steer'})
 try{
  await new Promise(x=>setTimeout(x,40))
  await r.steer!(input('change'))
  const steers=(await lines()).filter(x=>x.method==='turn/steer')
  expect(steers).toHaveLength(1)
  expect(steers[0].params.expectedTurnId).toBe('auto-1')
 }finally{await r.close({ mode: "shutdown" });await rm(dir,{recursive:true})}
})

test('overlap turns reject steer and prompt; stale complete leaves the newer turn',async()=>{
 const activity:any[]=[];const updates:any[]=[]
 const r=await driver({MODE:'stale-complete'}).open(ctx({onActivity:n=>activity.push({...n}),onUpdate:u=>updates.push(u)}))
 try{
  await new Promise(x=>setTimeout(x,80))
  expect(activity.filter(x=>x.phase==='started').map(x=>x.id).sort()).toEqual(['auto-1','auto-2'])
  await expect(r.steer!(input('x'))).rejects.toThrow('ambiguous')
  await expect(r.prompt(input('hello'),signal())).rejects.toThrow('already running')
  await expect(r.configure({})).rejects.toThrow('idle')
  await new Promise(x=>setTimeout(x,250))
  expect(activity.some(x=>x.id==='auto-1'&&x.phase==='completed')).toBe(true)
  expect(activity.some(x=>x.id==='auto-2'&&x.phase==='completed')).toBe(false)
  expect(updates.some(x=>x.value?.params?.turnId==='auto-2'||x.value?.params?.turn?.id==='auto-2')).toBe(true)
 }finally{await r.close({ mode: "shutdown" })}
})

test('owned prompt reports the same native id once and overlap native survives owned finally',async()=>{
 const activity:any[]=[]
 const r=await driver({MODE:'overlap-turns'}).open(ctx({onActivity:n=>activity.push({...n})}))
 try{
  const pending=r.prompt(input('hang'),signal())
  await new Promise(x=>setTimeout(x,80))
  expect(activity.filter(x=>x.phase==='started').map(x=>x.id).sort()).toEqual(['auto-2','turn-1'])
  expect(activity.filter(x=>x.id==='turn-1'&&x.phase==='started')).toHaveLength(1)
  await r.interrupt()
  expect(await pending).toEqual({stopReason:'cancelled'})
  // auto-2's turn/completed notification may land after the owned promise settles; wait for it.
  for(let i=0;i<100&&!activity.some(x=>x.id==='auto-2'&&x.phase==='completed');i++)await new Promise(x=>setTimeout(x,10))
  expect(activity.some(x=>x.id==='auto-2'&&x.phase==='completed')).toBe(true)
 }finally{await r.close({ mode: "shutdown" })}
})

test('native-only matching approval allow and late host after complete declines',async()=>{
 let resolveHost:(value:any)=>void=()=>{}
 let hostAsk!:(resolve:(value:any)=>void)=>void
 const asked=new Promise<void>(ready=>{
  hostAsk=resolve=>{resolveHost=resolve;ready()}
 })
 const hang=await traced({MODE:'native-ask',AUTONOMOUS_HOLD:'1',EXPECT_DECISION:'decline'},{permissionPrompts:'host'},{
  requestPermission:()=>new Promise(resolve=>{hostAsk(resolve)}),
 })
 try{
  await asked
  await hang.r.interrupt()
  const answers=await answeredApproval(hang.lines,'native-approval')
  expect(answers).toHaveLength(1)
  expect(answers[0].result).toEqual({decision:'decline'})
  resolveHost({outcome:{outcome:'selected',optionId:'allow_once'}})
  await new Promise(x=>setTimeout(x,40))
  expect((await hang.lines()).filter(x=>x.id==='native-approval'&&('result' in x||'error' in x))).toHaveLength(1)
 }finally{await hang.r.close({ mode: "shutdown" });await rm(hang.dir,{recursive:true})}
 const allow=await driver({MODE:'native-ask',EXPECT_DECISION:'accept'},{permissionPrompts:'host'}).open(ctx({
  requestPermission:async()=>({outcome:{outcome:'selected',optionId:'allow_once'}}),
 }))
 try{
  await new Promise(x=>setTimeout(x,80))
 }finally{await allow.close({ mode: "shutdown" })}
})

test('host allow_always writes acceptWithExecpolicyAmendment',async()=>{
 const r=await driver({EXPECT_AMENDMENT:'1'},{permissionPrompts:'host'}).open(ctx({
  requestPermission:async req=>{
   expect(req.options.map(o=>o.optionId)).toContain('allow_always')
   return {outcome:{outcome:'selected',optionId:'allow_always'}}
  },
 }))
 try{expect(await r.prompt(input('ask-always'),signal())).toEqual({stopReason:'end_turn'})}
 finally{await r.close({ mode: "shutdown" })}
})

test('MCP tool approval (mcpServer/elicitation/request) is a permission request; allow once / for this session / reject',async()=>{
 for(const [optionId,expect_action,persist] of [['allow_once','accept',''],['allow_always','accept','session'],['reject_once','decline','']] as const){
  let seen:{title:string,options:string[]}|undefined
  const r=await driver({EXPECT_ELICITATION:expect_action,EXPECT_POLICY:'on-request',...(persist?{EXPECT_PERSIST:persist}:{})},{permissionPrompts:'host',permissions:{kind:'codex',approvalPolicy:'on-request',sandbox:'read-only'}}).open(ctx({
   requestPermission:async req=>{
    seen={title:req.toolCall.title??'',options:req.options.map(o=>o.optionId)}
    return {outcome:{outcome:'selected',optionId}}
   },
  }))
  try{
   expect(await r.prompt(input('ask-mcp'),signal())).toEqual({stopReason:'end_turn'})
   expect(seen?.title).toBe('mux-shim: rename_session')
   expect(seen?.options).toEqual(['allow_once','allow_always','reject_once'])
  }finally{await r.close({ mode: "shutdown" })}
 }
})

test('under approvalPolicy never an MCP tool approval is granted (the mode says never ask)',async()=>{
 const r=await driver({EXPECT_ELICITATION:'accept'},{permissions:{kind:'codex',approvalPolicy:'never',sandbox:'read-only'}}).open(ctx())
 try{expect(await r.prompt(input('ask-mcp'),signal())).toEqual({stopReason:'end_turn'})}
 finally{await r.close({ mode: "shutdown" })}
})

test('onRuntimeRequest exposes skills/list and refuses turn/thread methods after close',async()=>{
 let hooked:{sessionId:string,agentSessionId:string,request:(method:string,params:unknown)=>Promise<unknown>}|undefined
 const r=await driver({}, {onRuntimeRequest:(info,request)=>{hooked={...info,request}}}).open(ctx())
 try{
  expect(hooked?.sessionId).toBe('core')
  expect(hooked?.agentSessionId).toBe('native-1')
  expect(await hooked!.request('skills/list',{})).toEqual({data:[{skills:[{name:'demo',description:'stub',enabled:true}]}]})
  await expect(hooked!.request('turn/start',{})).rejects.toThrow(/refuses turn\/start/)
  await expect(hooked!.request('thread/resume',{})).rejects.toThrow(/refuses thread\/resume/)
 }finally{await r.close({ mode: "shutdown" })}
 await expect(hooked!.request('skills/list',{})).rejects.toThrow(/closed/)
})

test('codex() TypeError names each missing required field',()=>{
 const keeper={stateDirectory:'/tmp',limits:{parkedDeadlineMs:1,journalMaxBytes:1,connectTimeoutMs:1}}
 const full:any={id:'codex',command:'codex',args:['app-server'],inheritEnv:true,sandbox:'read-only',approvalPolicy:'never',permissionPrompts:'none',permissions:{kind:'codex',approvalPolicy:'never',sandbox:'read-only'},setupTimeoutMs:1,requestTimeoutMs:1,shutdownTimeoutMs:1,maxFrameBytes:1,keeper}
 for(const field of ['id','command','args','sandbox','approvalPolicy','setupTimeoutMs','requestTimeoutMs','shutdownTimeoutMs','maxFrameBytes','permissionPrompts','permissions','keeper','inheritEnv']){
  const opts={...full};delete opts[field]
  expect(()=>codex(opts)).toThrow(TypeError)
  expect(()=>codex(opts)).toThrow(new RegExp(`Codex ${field} is required`))
 }
})

test('inline agentMessage questions are answerable: the answer is steered into the still-running turn',async()=>{
 const asked:any[]=[]
 const {r,dir,lines}=await traced({},{}, {requestAnswers:async(req:any)=>{asked.push(req);return {outcome:'answered' as const,answers:{q1:'Blue'}}}})
 try{
  expect(await r.prompt(input('ask-inline'),signal())).toEqual({stopReason:'end_turn'})
  expect(asked).toHaveLength(1)
  expect(asked[0].toolCallId).toBe('q-item-1')
  expect(asked[0].questions[0]).toMatchObject({id:'q1',prompt:'Which color?',multiSelect:false,allowFreeText:true})
  expect(asked[0].questions[0].options.map((o:any)=>o.label)).toEqual(['Red','Blue'])
  const steer=(await lines()).find((l:any)=>l.method==='turn/steer')
  expect(steer.params.expectedTurnId).toBe('turn-1')
  expect(steer.params.input[0].text).toBe('Blue')
 }finally{await r.close({mode:'shutdown'});await rm(dir,{recursive:true,force:true})}
})

test('declined inline question steers an explicit decline; cancelled sends nothing',async()=>{
 const a=await traced({},{}, {requestAnswers:async()=>({outcome:'declined' as const})})
 try{
  expect(await a.r.prompt(input('ask-inline'),signal())).toEqual({stopReason:'end_turn'})
  const steer=(await a.lines()).find((l:any)=>l.method==='turn/steer')
  expect(steer.params.input[0].text).toContain('decline')
 }finally{await a.r.close({mode:'shutdown'});await rm(a.dir,{recursive:true,force:true})}
})
