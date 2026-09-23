import {createInterface} from 'node:readline'
import {appendFileSync,writeFileSync} from 'node:fs'
if(process.env.PID_FILE) writeFileSync(process.env.PID_FILE,String(process.pid))
if(process.env.MODE==='stubborn') process.on('SIGTERM',()=>{})
const send=o=>process.stdout.write(JSON.stringify(o)+'\n')
let thread='native-1',turn=0, initialized=false
const completed=new Set()
const running=new Set()
const pendingApprovals=new Set()
const expectedPolicy=process.env.EXPECT_POLICY||'never'
const expectedSandbox=process.env.EXPECT_SANDBOX||'read-only'
const done=(id,status='completed',tid=thread)=>{if(tid===thread)completed.add(id);send({method:'turn/completed',params:{threadId:tid,turn:{id,status,error:status==='failed'?{message:'turn failed'}:null}}})}
createInterface({input:process.stdin}).on('line',line=>{
 const m=JSON.parse(line); if(process.env.TRACE) appendFileSync(process.env.TRACE,line+'\n')
 const reply=result=>send({id:m.id,result})
 if(m.method==='skills/list'){reply({data:[{skills:[{name:'demo',description:'stub',enabled:true}]}]});return}
 if(m.method==='initialize') {
  if(process.env.MODE==='setup-hang')return
  if(process.env.MODE==='setup-notice'){
   send({method:'account/rateLimits/updated',params:{rateLimits:{primary:{usedPercent:1}}}})
   send({method:'item/agentMessage/delta',params:{delta:'no-thread'}})
  }
  if(process.env.MODE==='setup-overflow'){
   for(let i=0;i<257;i++) send({method:'item/agentMessage/delta',params:{delta:'setup-'+i}})
  }
  reply({userAgent:'fixture'});return
 }
 if(m.method==='initialized'){initialized=true;return}
 const threadResult=(id,params)=>{
  const result={thread:{id}}
  if(params?.model) result.model=params.model
  if(process.env.NATIVE_MODEL) result.model=process.env.NATIVE_MODEL
  if(Object.prototype.hasOwnProperty.call(process.env,'NATIVE_EFFORT')) result.reasoningEffort=process.env.NATIVE_EFFORT===''?null:process.env.NATIVE_EFFORT
  return result
 }
 if(m.method==='thread/fork'){
  if(m.params.threadId!=='native-parent'||m.params.lastTurnId!=='checkpoint')process.exit(5)
  thread='native-fork';reply(threadResult(thread,m.params));return
 }
 if(m.method==='thread/start'||m.method==='thread/resume'){
  if(!initialized)process.exit(2)
  if(m.params.approvalPolicy!==expectedPolicy||m.params.sandbox!==expectedSandbox)process.exit(3)
  if(m.params.threadId==='missing'){send({id:m.id,error:{code:-1,message:'missing thread'}});return}
  thread=m.params.threadId??thread; reply(threadResult(process.env.MODE==='wrong-resume'?'different':thread,m.params))
  const mode=process.env.MODE
  if(mode==='setup-notice'){
   send({method:'item/agentMessage/delta',params:{threadId:thread,delta:'ok'}})
   send({method:'item/agentMessage/delta',params:{threadId:'other-thread',turnId:'x',delta:'nope'}})
  }
  if(mode==='rate-limits'||mode==='autonomous-on-open'||mode==='native-steer'||mode==='stale-complete'||mode==='native-ask'){
   send({method:'account/rateLimits/updated',params:{rateLimits:{primary:{usedPercent:23}}}})
  }
  if(mode==='autonomous-on-open'||mode==='native-steer'||mode==='stale-complete'||mode==='native-ask'){
   const auto=process.env.TURN_ID||'auto-1'
   send({method:'turn/started',params:{threadId:thread,turn:{id:auto,status:'inProgress'}}})
   send({method:'item/agentMessage/delta',params:{threadId:thread,turnId:auto,delta:'native activity'}})
   running.add(auto)
   if(mode==='native-ask'){
    pendingApprovals.add('native-approval')
    send({id:'native-approval',method:'item/commandExecution/requestApproval',params:{threadId:thread,turnId:auto,itemId:'item-1',command:'ls',approvalId:'na-1'}})
    return
   }
   if(mode==='stale-complete'){
    send({method:'turn/started',params:{threadId:thread,turn:{id:'auto-2',status:'inProgress'}}})
    running.add('auto-2')
    setTimeout(()=>done(auto),250)
    return
   }
   if(mode==='native-steer'||process.env.AUTONOMOUS_HOLD) return
   setTimeout(()=>done(auto),20)
  }
  return
 }
 if(m.method==='thread/read'){
  if(m.params.threadId!==thread)process.exit(8)
  if(m.params.includeTurns!==true)process.exit(9)
  if(process.env.MODE==='history-bad-id'){reply({thread:{id:'other-thread',turns:[{id:'hist-1'}]}});return}
  if(process.env.MODE==='history-no-turns'){reply({thread:{id:thread}});return}
  reply({thread:{id:thread,turns:[{id:'hist-1',status:'completed'},{id:'hist-2',status:'completed'},{id:'hist-3',status:'completed'}]}})
  return
 }
 if(m.method==='turn/start'){
  const id='turn-'+(++turn), text=m.params.input[0]?.text
  if(text==='disconnect'){process.exit(1)}
  if(text==='eof'){process.stdout.end();return}
  if(text==='malformed'){process.stdout.write('{bad}\n');return}
  if(text==='oversize'){process.stdout.write('x'.repeat(5000));return}
  done(id,'completed','other-thread');done('other-turn')
  const finish=()=>{send({method:'item/agentMessage/delta',params:{threadId:thread,turnId:id,delta:'héllo'}});done(id,text==='fail'?'failed':'completed')}
  if(text==='early')finish()
  setTimeout(()=>{
   reply({turn:{id}})
   if(text==='early')return
   setTimeout(()=>{
    running.add(id);send({method:'turn/started',params:{threadId:thread,turn:{id,status:'inProgress'}}})
    const ask=(approvalId,method,params)=>{pendingApprovals.add(approvalId);send({id:approvalId,method,params:{threadId:thread,turnId:id,itemId:'item-1',...params}})}
    if(text==='permission'){ask('approval','item/commandExecution/requestApproval',{command:'ls'});return}
    if(text==='ask-command'||text==='ask-hang'){ask('approval','item/commandExecution/requestApproval',{command:'npm test',approvalId:'cb-1'});return}
    if(text==='ask-always'){ask('approval','item/commandExecution/requestApproval',{command:'ls',approvalId:'al-1',proposedExecpolicyAmendment:['ls'],availableDecisions:['accept',{acceptWithExecpolicyAmendment:{execpolicy_amendment:['ls']}},'cancel']});return}
    if(text==='ask-file'){ask('approval','item/fileChange/requestApproval',{grantRoot:'/tmp'});return}
    // Real app-server shape for an MCP tool approval (captured 2026-09-23).
    if(text==='ask-mcp'){ask('elicit-1','mcpServer/elicitation/request',{serverName:'mux-shim',mode:'form',_meta:{codex_approval_kind:'mcp_tool_call',persist:['session','always'],tool_params:{name:'Node.js Runtime Test'}},message:'Allow the mux-shim MCP server to run tool "rename_session"?',requestedSchema:{type:'object',properties:{}}});return}
    if(text==='ask-stale'){pendingApprovals.add('stale');send({id:'stale',method:'item/commandExecution/requestApproval',params:{threadId:'other-thread',turnId:id,itemId:'item-1',command:'ls'}});return}
    if(text==='ask-unknown'){pendingApprovals.add('unknown');send({id:'unknown',method:'item/tool/requestUserInput',params:{threadId:thread,turnId:id}});return}
    if(text==='ask-permissions'){ask('perm-approval','item/permissions/requestApproval',{cwd:'/',reason:null,permissions:{network:null,fileSystem:null},startedAtMs:0,environmentId:null});return}
    if(text==='ask-dup'){ask('dup','item/commandExecution/requestApproval',{command:'ls',approvalId:'dup-1'});return}
    if(text==='ask-inline'){send({method:'item/completed',params:{threadId:thread,turnId:id,item:{type:'agentMessage',id:'q-item-1',text:'Which color?',phase:'final_answer',delivery:'async',questions:[{title:'Which color?',options:['Red','Blue']}]}}});return}
    if(text==='ask-late'){ask('approval','item/commandExecution/requestApproval',{command:'ls',approvalId:'late-1'});setTimeout(()=>done(id),10);return}
    if(text==='ask-id-num'){pendingApprovals.add(1);send({id:1,method:'item/commandExecution/requestApproval',params:{threadId:thread,turnId:id,itemId:'item-1',command:'ls',approvalId:'num-1'}});return}
    if(text==='ask-id-str'){pendingApprovals.add('1');send({id:'1',method:'item/commandExecution/requestApproval',params:{threadId:thread,turnId:id,itemId:'item-1',command:'ls',approvalId:'str-1'}});return}
    if(text==='hang' && process.env.MODE==='overlap-turns'){
     send({method:'turn/started',params:{threadId:thread,turn:{id:'auto-2',status:'inProgress'}}})
     running.add('auto-2')
     if(process.env.NATIVE_ASK){
      pendingApprovals.add('native-approval')
      send({id:'native-approval',method:'item/commandExecution/requestApproval',params:{threadId:thread,turnId:'auto-2',itemId:'item-1',command:'ls',approvalId:'na-2'}})
     }
    }
    if(text!=='hang')setTimeout(finish,10)
   },20)
  },10)
 }
 if(pendingApprovals.has(m.id)){
  pendingApprovals.delete(m.id)
  if(m.error){done('turn-'+turn,'failed');return}
  const current=process.env.MODE==='native-ask'?(process.env.TURN_ID||'auto-1'):'turn-'+turn
  if(m.id==='elicit-1'){
    const expected=process.env.EXPECT_ELICITATION||'decline'
    if(m.result?.action!==expected) process.exit(4)
    if(expected==='accept' && process.env.EXPECT_PERSIST && m.result?._meta?.persist!==process.env.EXPECT_PERSIST) process.exit(4)
    if(expected==='accept' && !process.env.EXPECT_PERSIST && m.result?._meta) process.exit(4)
  } else if(m.result && Object.prototype.hasOwnProperty.call(m.result,'permissions')){
    if(m.result.scope!=='turn' || Object.keys(m.result.permissions||{}).length!==0) process.exit(4)
  } else {
    const expected=process.env.EXPECT_DECISION
    if(process.env.EXPECT_AMENDMENT){
      const amendment=m.result?.decision?.acceptWithExecpolicyAmendment?.execpolicy_amendment
      if(!Array.isArray(amendment))process.exit(4)
    } else if(expected){if(m.result?.decision!==expected)process.exit(4)}
    else if(m.result?.decision!=='decline')process.exit(4)
  }
  if(process.env.MODE==='dup-after' && m.id==='dup'){
    pendingApprovals.add('dup')
    send({id:'dup',method:'item/commandExecution/requestApproval',params:{threadId:thread,turnId:current,itemId:'item-1',command:'ls',approvalId:'dup-1'}})
    process.env.MODE=''
    return
  }
  if(process.env.MODE==='id-types' && m.id===1){
    pendingApprovals.add('1')
    send({id:'1',method:'item/commandExecution/requestApproval',params:{threadId:thread,turnId:current,itemId:'item-1',command:'ls',approvalId:'str-1'}})
    process.env.MODE=''
    return
  }
  if(running.has(current) && !completed.has(current) && process.env.MODE==='approval-hang') return
  if(completed.has(current)) return
  done(current)
  return
 }
 if(m.method==='turn/interrupt'){if(!running.has(m.params.turnId)||completed.has(m.params.turnId)){send({id:m.id,error:{code:-1,message:'no active turn to interrupt'}});return}reply({});done(m.params.turnId,'interrupted')}
 if(m.method==='turn/steer'){reply({turnId:m.params.expectedTurnId});done(m.params.expectedTurnId)}
})
