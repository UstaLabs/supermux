import type { AgentKind } from "../../shared/agents"
import { makeLogger } from "../../shared/log"

const log = makeLogger("agent-rpc")

export interface AgentRpcCall {
  key: string
  taskType: string
  payload: unknown
  agent?: AgentKind
  model?: string
  timeoutMs?: number
}

export interface AgentRpcDeps {
  spawnWorker: (o: { key: string; agent: AgentKind; model?: string }) => Promise<{ sessionId: string }>
  deliver: (sessionId: string, text: string) => Promise<void>
  killWorker: (sessionId: string) => Promise<void>
  isAlive: (sessionId: string) => boolean
  buildPrompt: (taskType: string, payload: unknown, requestId: string) => string
  newRequestId: () => string
  now: () => number
  defaultAgent: AgentKind
  defaultModel?: string
  defaultTimeoutMs?: number
}

interface Pending { resolve: (d: unknown) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout>; workerKey: string }
interface QueuedCall { call: AgentRpcCall; resolve: (d: unknown) => void; reject: (e: Error) => void }
interface Worker { key: string; sessionId: string; busy: boolean; queue: QueuedCall[]; lastUsedAt: number }

export interface AgentRpc {
  callAgent: (call: AgentRpcCall) => Promise<unknown>
  settle: (requestId: string, data: unknown) => void
  fail: (requestId: string, error: string) => void
  reapIdle: (maxIdleMs: number) => Promise<void>
  internalSessionIds: () => string[]
}

export function createAgentRpc(deps: AgentRpcDeps): AgentRpc {
  const workers = new Map<string, Worker>()
  const pending = new Map<string, Pending>()
  // In-flight spawns, keyed by worker key, so concurrent callAgent() calls for the
  // same key coalesce onto a single spawnWorker() instead of racing into duplicates.
  const spawning = new Map<string, Promise<Worker>>()

  function ensureWorker(call: AgentRpcCall): Promise<Worker> {
    const existing = workers.get(call.key)
    if (existing && deps.isAlive(existing.sessionId)) return Promise.resolve(existing)
    if (existing) workers.delete(call.key)
    const inFlight = spawning.get(call.key)
    if (inFlight) return inFlight
    const spawn = (async () => {
      const { sessionId } = await deps.spawnWorker({ key: call.key, agent: call.agent ?? deps.defaultAgent, model: call.model ?? deps.defaultModel })
      const w: Worker = { key: call.key, sessionId, busy: false, queue: [], lastUsedAt: deps.now() }
      workers.set(call.key, w)
      return w
    })()
    spawning.set(call.key, spawn)
    spawn.finally(() => { if (spawning.get(call.key) === spawn) spawning.delete(call.key) })
    return spawn
  }

  function pump(w: Worker) {
    if (w.busy) return
    const next = w.queue.shift()
    if (!next) return
    w.busy = true
    w.lastUsedAt = deps.now()
    const requestId = deps.newRequestId()
    const timeoutMs = next.call.timeoutMs ?? deps.defaultTimeoutMs ?? 30_000
    const timer = setTimeout(() => {
      pending.delete(requestId)
      finishWorker(w)
      next.reject(new Error(`agent-rpc timeout after ${timeoutMs}ms`))
      void recycle(w)
    }, timeoutMs)
    pending.set(requestId, {
      workerKey: w.key,
      resolve: (d) => { finishWorker(w); next.resolve(d) },
      reject: (e) => { finishWorker(w); next.reject(e) },
      timer,
    })
    deps.deliver(w.sessionId, deps.buildPrompt(next.call.taskType, next.call.payload, requestId)).catch((e) => {
      const p = pending.get(requestId)
      if (p) { clearTimeout(p.timer); pending.delete(requestId); finishWorker(w); next.reject(e instanceof Error ? e : new Error(String(e))) }
    })
  }

  function finishWorker(w: Worker) { w.busy = false; w.lastUsedAt = deps.now(); pump(w) }

  async function recycle(w: Worker) {
    workers.delete(w.key)
    try { await deps.killWorker(w.sessionId) } catch (e) { log.warn("worker_kill_failed", { err: String(e) }) }
  }

  function settlePending(requestId: string, ok: boolean, value: unknown, error?: string) {
    const p = pending.get(requestId)
    if (!p) { log.warn("rpc_settle_unknown_request", { requestId }); return }
    pending.delete(requestId)
    clearTimeout(p.timer)
    if (ok) p.resolve(value)
    else p.reject(new Error(error ?? "agent-rpc rejected"))
  }

  return {
    callAgent(call) {
      return new Promise<unknown>((resolve, reject) => {
        ensureWorker(call).then((w) => { w.queue.push({ call, resolve, reject }); pump(w) }).catch(reject)
      })
    },
    settle(requestId, data) { settlePending(requestId, true, data) },
    fail(requestId, error) { settlePending(requestId, false, undefined, error) },
    async reapIdle(maxIdleMs) {
      const cutoff = deps.now() - maxIdleMs
      for (const w of [...workers.values()]) {
        if (!w.busy && w.queue.length === 0 && w.lastUsedAt < cutoff) await recycle(w)
      }
    },
    internalSessionIds() { return [...workers.values()].map(w => w.sessionId) },
  }
}
