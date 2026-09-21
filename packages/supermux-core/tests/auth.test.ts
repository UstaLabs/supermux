import { afterEach, expect, test } from 'bun:test'
import { mkdtemp, readFile, rm, writeFile, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { copiedCredentials, withAuth } from '../src/auth/index.js'
import type { DriverContext } from '../src/types.js'
const dirs: string[] = []
afterEach(async () => { for (const dir of dirs.splice(0)) await rm(dir, {recursive:true,force:true}) })
async function setup() {
 const dir = await mkdtemp(join(tmpdir(),'core-auth-')); dirs.push(dir)
 const source = join(dir,'auth.json'); await writeFile(source, JSON.stringify({token:'old'}))
 const provider = copiedCredentials({source,homesDirectory:join(dir,'homes'),filename:'auth.json',homeVariable:'CODEX_HOME'})
 const ctx = {sessionId:'session-1',cwd:dir,signal:new AbortController().signal,onUpdate(){},onExit(){},requestPermission:async()=>({outcome:{outcome:'cancelled' as const}})} satisfies DriverContext
 return {dir,source,provider,ctx}
}
test('materializes owner-only credentials and preserves native session files on release',async()=>{
 const {source,provider,ctx}=await setup(); const lease=await provider.prepare(ctx)
 const home=lease.env.CODEX_HOME!; expect(await readFile(join(home,'auth.json'),'utf8')).toBe(await readFile(source,'utf8'))
 expect((await stat(join(home,'auth.json'))).mode&0o777).toBe(0o600)
 await writeFile(join(home,'native-history'),'history'); await lease.release()
 expect(await readFile(join(home,'native-history'),'utf8')).toBe('history')
})
test('promotes refreshed copy only while canonical still matches acquisition',async()=>{
 const {source,provider,ctx}=await setup(); const lease=await provider.prepare(ctx)
 await writeFile(join(lease.env.CODEX_HOME!,'auth.json'),JSON.stringify({token:'refreshed'})); await lease.release()
 expect(JSON.parse(await readFile(source,'utf8')).token).toBe('refreshed')
})
test('never overwrites a changed account or resurrects host logout',async()=>{
 const {source,provider,ctx}=await setup(); let lease=await provider.prepare(ctx)
 await writeFile(join(lease.env.CODEX_HOME!,'auth.json'),JSON.stringify({token:'refreshed'}))
 await writeFile(source,JSON.stringify({token:'other-account'})); await lease.release()
 expect(JSON.parse(await readFile(source,'utf8')).token).toBe('other-account')
 lease=await provider.prepare(ctx); await rm(source); await lease.release()
 await expect(provider.prepare(ctx)).rejects.toMatchObject({code:'auth_missing'})
})
test('malformed refresh cannot replace canonical and cleanup can retry',async()=>{
 const {source,provider,ctx}=await setup(); const lease=await provider.prepare(ctx)
 await writeFile(join(lease.env.CODEX_HOME!,'auth.json'),'broken'); await expect(lease.release()).rejects.toMatchObject({code:'auth_invalid'})
 expect(JSON.parse(await readFile(source,'utf8')).token).toBe('old')
 await writeFile(join(lease.env.CODEX_HOME!,'auth.json'),JSON.stringify({token:'valid'})); await lease.release()
})
test('leases exclude concurrent writers to the same agent home',async()=>{
 const {provider,ctx}=await setup(); const lease=await provider.prepare(ctx)
 await expect(provider.prepare(ctx)).rejects.toMatchObject({code:'auth_home_locked'})
 await lease.release(); const next=await provider.prepare(ctx); await next.release()
})
test('unsafe paths and aborted setup are rejected',async()=>{
 const {provider,ctx}=await setup()
 await expect(provider.prepare({...ctx,sessionId:'../escape'})).rejects.toThrow()
 await expect(provider.prepare({...ctx,signal:AbortSignal.abort()})).rejects.toThrow()
})
test('withAuth forwards environment and releases only after runtime closes',async()=>{
 const {provider,ctx}=await setup(); let home=''; let attempts=0
 const wrapped=withAuth({id:'codex',open:async context=>{
  home=context.profile!.env!.CODEX_HOME!
  return {agentSessionId:'n',capabilities:{resume:true,steer:false,fork:false,detach:false},prompt:async()=>({stopReason:'end_turn'}),interrupt:async()=>{},close:async()=>{if(++attempts===1) throw new Error('alive')}}
 }},provider)
 const runtime=await wrapped.open(ctx); await expect(runtime.close({ mode: "shutdown" })).rejects.toThrow('alive')
 await expect(provider.prepare(ctx)).rejects.toMatchObject({code:'auth_home_locked'})
 await writeFile(join(home,'auth.json'),JSON.stringify({token:'fresh'})); await runtime.close({ mode: "shutdown" })
 const next=await provider.prepare(ctx); await next.release()
})
test('withAuth releases on failed open',async()=>{
 const {provider,ctx}=await setup(); const wrapped=withAuth({id:'codex',open:async()=>{throw new Error('open failed')}},provider)
 await expect(wrapped.open(ctx)).rejects.toThrow('open failed')
 const next=await provider.prepare(ctx); await next.release()
})
test('withAuth forwards configure, configuration, and history without cloning or rebinding',async()=>{
 const {ctx}=await setup()
 const configurationArg={model:'m'}
 const historyArg={limit:2}
 const configurationReturn={model:'inner'}
 const historyReturn={protocol:'native' as const,value:[]}
 let inner: {
  seen?: boolean
  configureArg?: unknown
  historyArg?: unknown
  steerArg?: unknown
  agentSessionId: string
  capabilities: {resume:boolean;steer:boolean;fork:boolean;detach:boolean;configure:boolean;history:boolean}
  prompt: () => Promise<{stopReason:'end_turn'}>
  interrupt: () => Promise<void>
  close: () => Promise<void>
  steer: (content: unknown) => Promise<void>
  configure: (configuration: unknown) => Promise<void>
  configuration: () => unknown
  history: (options: unknown) => Promise<unknown>
 }
 inner = {
  agentSessionId:'n',
  capabilities:{resume:true,steer:true,fork:true,detach:false,configure:true,history:true},
  prompt:async()=>({stopReason:'end_turn'}),
  interrupt:async()=>{},
  close:async()=>{},
  async steer(content){ this.steerArg=content },
  async configure(configuration){ this.seen=true; this.configureArg=configuration },
  configuration(){ return configurationReturn },
  async history(options){ this.historyArg=options; return historyReturn },
 }
 const wrapped=withAuth({id:'fake',open:async()=>inner}, {prepare:async()=>({env:{},release:async()=>{}}),close:async()=>{}})
 const runtime=await wrapped.open(ctx)
 expect(runtime.capabilities).toEqual({resume:true,steer:true,fork:false,detach:false,configure:true,history:true})
 expect(typeof runtime.steer).toBe('function')
 expect(typeof runtime.configure).toBe('function')
 expect(typeof runtime.configuration).toBe('function')
 expect(typeof runtime.history).toBe('function')
 await runtime.configure!(configurationArg)
 await runtime.steer!([])
 const history=await runtime.history!(historyArg)
 expect(runtime.configuration!()).toBe(configurationReturn)
 expect(history).toBe(historyReturn)
 expect(inner.configureArg).toBe(configurationArg)
 expect(inner.historyArg).toBe(historyArg)
 expect(inner.seen).toBe(true)
 await runtime.close({ mode: "shutdown" })
})
test('withAuth omits optional methods the inner runtime does not implement',async()=>{
 const {ctx}=await setup()
 const wrapped=withAuth({id:'fake',open:async()=>({
  agentSessionId:'n',
  capabilities:{resume:true,steer:false,fork:false,detach:false},
  prompt:async()=>({stopReason:'end_turn'}),
  interrupt:async()=>{},
  close:async()=>{},
 })}, {prepare:async()=>({env:{},release:async()=>{}}),close:async()=>{}})
 const runtime=await wrapped.open(ctx)
 expect(runtime.steer).toBeUndefined()
 expect(runtime.configure).toBeUndefined()
 expect(runtime.configuration).toBeUndefined()
 expect(runtime.history).toBeUndefined()
 await runtime.close({ mode: "shutdown" })
})
test('withAuth rejects fork before opening the inner runtime',async()=>{
 const {ctx}=await setup(); let opened=false
 const wrapped=withAuth({id:'fake',open:async()=>{opened=true; throw new Error('inner open must not run')}}, {prepare:async()=>({env:{},release:async()=>{}}),close:async()=>{}})
 await expect(wrapped.open({...ctx,forkFrom:{agentSessionId:'x'}})).rejects.toMatchObject({code:'unsupported_operation'})
 expect(opened).toBe(false)
})
test('overlapping source promotion fails with auth_source_locked and close retries cleanup',async()=>{
 const {source,provider,ctx}=await setup()
 const other={...ctx,sessionId:'session-2'}
 const first=await provider.prepare(ctx)
 const second=await provider.prepare(other)
 await writeFile(join(first.env.CODEX_HOME!,'auth.json'),JSON.stringify({token:'first'}))
 await writeFile(`${source}.supermux-lock`,'busy',{mode:0o600})
 await expect(first.release()).rejects.toMatchObject({code:'auth_source_locked'})
 await expect(provider.prepare(ctx)).rejects.toMatchObject({code:'auth_home_locked'})
 await second.release()
 await rm(`${source}.supermux-lock`)
 await provider.close({ mode: "shutdown" })
 expect(JSON.parse(await readFile(source,'utf8')).token).toBe('first')
 const again=await provider.prepare(ctx); await again.release()
})
test('prepare rejects malformed empty and non-object credentials before leasing a home',async()=>{
 const {source,provider,ctx,dir}=await setup()
 for (const body of ['{}','[]','null','"token"','not-json']) {
  await writeFile(source,body)
  await expect(provider.prepare(ctx)).rejects.toMatchObject({code:'auth_invalid'})
 }
 await expect(stat(join(dir,'homes','session-1','.auth.lock'))).rejects.toMatchObject({code:'ENOENT'})
 await writeFile(source,JSON.stringify({token:'ok'}))
 const lease=await provider.prepare(ctx); await lease.release()
})
test('failed inner open plus failed lease release keeps both errors and a retryable lease',async()=>{
 const {provider,ctx}=await setup()
 const native=Object.assign(new Error('native token expired'),{code:'native_auth_expired'})
 const wrapped=withAuth({id:'codex',open:async context=>{
  await writeFile(join(context.profile!.env!.CODEX_HOME!,'auth.json'),'broken')
  throw native
 }},provider)
 const error=await wrapped.open(ctx).catch(e=>e)
 expect(error).toBeInstanceOf(AggregateError)
 expect(error.message).toMatch(/retry provider\.close\(\)/)
 expect(error.errors[0]).toBe(native)
 expect(error.errors[1]).toMatchObject({code:'auth_invalid'})
 await expect(provider.prepare(ctx)).rejects.toMatchObject({code:'auth_home_locked'})
 await writeFile(join(ctx.cwd,'homes','session-1','auth.json'),JSON.stringify({token:'fixed'}))
 await provider.close({ mode: "shutdown" })
 const next=await provider.prepare(ctx); await next.release()
})
test('withAuth propagates opaque native auth failures without remapping to missing or invalid credentials',async()=>{
 const {provider,ctx}=await setup()
 const native=Object.assign(new Error('agent refused expired session token'),{code:'native_auth_expired'})
 const wrapped=withAuth({id:'codex',open:async()=>{throw native}},provider)
 const error=await wrapped.open(ctx).catch(e=>e)
 expect(error).toBe(native)
 expect(error.code).toBe('native_auth_expired')
 expect(error.code).not.toBe('auth_missing')
 expect(error.code).not.toBe('auth_invalid')
 const next=await provider.prepare(ctx); await next.release()
})
test('withAuth configure failures do not release the auth lease',async()=>{
 const {provider,ctx}=await setup()
 const wrapped=withAuth({id:'fake',open:async()=>({
  agentSessionId:'n',
  capabilities:{resume:true,steer:false,fork:false,detach:false,configure:true},
  prompt:async()=>({stopReason:'end_turn'}),
  interrupt:async()=>{},
  close:async()=>{},
  configure:async()=>{throw new Error('configure failed')},
 })},provider)
 const runtime=await wrapped.open(ctx)
 await expect(runtime.configure!({})).rejects.toThrow('configure failed')
 await expect(provider.prepare(ctx)).rejects.toMatchObject({code:'auth_home_locked'})
 await runtime.close({ mode: "shutdown" })
 const next=await provider.prepare(ctx); await next.release()
})
