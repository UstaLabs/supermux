import type { ModelInfo } from "../../models/discovery"
import { realGrokRunner, type GrokRunner } from "./runner"

/** Minimal JSON-RPC client for the discovery handshake (inlined from the deleted tmux-era ACP client). */
class AcpClient {
  private nextId = 1
  private pending = new Map<number, { resolve: (v: unknown) => void; reject: (e: Error) => void }>()
  private buf = ""
  private write: (line: string) => void
  constructor(write: (line: string) => void) { this.write = write }
  setWrite(fn: (line: string) => void): void { this.write = fn }
  request<T = unknown>(method: string, params: unknown): Promise<T> {
    const id = this.nextId++
    const line = JSON.stringify({ jsonrpc: "2.0", id, method, params })
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve: resolve as (v: unknown) => void, reject })
      this.write(line)
    })
  }
  feed(chunk: string): void {
    this.buf += chunk
    let i: number
    while ((i = this.buf.indexOf("\n")) >= 0) {
      const line = this.buf.slice(0, i)
      this.buf = this.buf.slice(i + 1)
      if (!line.trim()) continue
      let m: { id?: number; result?: unknown; error?: { message?: string } }
      try { m = JSON.parse(line) as typeof m } catch { continue }
      if (m.id == null) continue
      const p = this.pending.get(m.id)
      if (!p) continue
      this.pending.delete(m.id)
      if (m.error) p.reject(new Error(typeof m.error.message === "string" ? m.error.message : "jsonrpc error"))
      else p.resolve(m.result)
    }
  }
  fail(err: Error): void {
    for (const p of this.pending.values()) p.reject(err)
    this.pending.clear()
  }
}

// Shape of `_meta.modelState` from ACP `initialize` (verified live, grok 0.2.99).
export type GrokModelEntry = {
  modelId: string
  name?: string
  description?: string
  _meta?: {
    supportsReasoningEffort?: boolean
    reasoningEfforts?: { id: string; label?: string; description?: string; default?: boolean }[]
  }
}

/** An unauthenticated Grok ACP handshake advertises this one synthetic fallback
 * model instead of returning an auth error. Treating it as a successful catalog
 * refresh would replace a previously good list (for example grok-4.5) every time
 * the six-hour access token expires. */
function isUnauthenticatedFallback(models: GrokModelEntry[]): boolean {
  return models.length === 1 && models[0]?.modelId === "grok-build"
}

/** Map grok's ACP modelState into the broker's ModelInfo. Reasoning levels come
 * straight from the agent, so a model that doesn't support effort correctly gets
 * none (the picker then hides the control) instead of an assumed high/medium/low. */
export function mapGrokModels(models: GrokModelEntry[]): ModelInfo[] {
  return models.map((m) => {
    const meta = m._meta
    const efforts = meta?.supportsReasoningEffort ? (meta.reasoningEfforts ?? []) : []
    return {
      id: m.modelId,
      displayName: m.name ?? m.modelId,
      agent: "grok" as const,
      ...(efforts.length
        ? { reasoningLevels: efforts.map((r) => ({ id: r.id, description: r.description })) }
        : {}),
    }
  })
}

/** grok has no JSON-emitting `models` command — the text list carries ids only, no
 * per-model reasoning metadata. The ACP handshake does carry it, so discovery
 * speaks the same protocol the adapter does: spawn `grok agent stdio`, initialize,
 * read modelState, kill. Bounded by timeoutMs so a hung/unauthed CLI can't stall
 * the periodic refresh. */
export async function discoverGrokModels(opts?: {
  runner?: GrokRunner
  timeoutMs?: number
  workdir?: string
}): Promise<ModelInfo[]> {
  const runner = opts?.runner ?? realGrokRunner
  const timeoutMs = opts?.timeoutMs ?? 20_000
  const client = new AcpClient(() => {})
  let child: { kill: () => void } | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    child = runner({
      workdir: opts?.workdir ?? process.cwd(),
      env: {},
      client,
      onExit: () => client.fail(new Error("grok agent exited during model discovery")),
    })
    const init = await Promise.race([
      client.request("initialize", {
        protocolVersion: 1,
        // Match GrokAdapter: do not advertise client FS we don't implement.
        clientCapabilities: { fs: { readTextFile: false, writeTextFile: false } },
      }) as Promise<any>,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error("grok model discovery timed out")), timeoutMs)
      }),
    ])
    const models = init?._meta?.modelState?.availableModels ?? init?.modelState?.availableModels ?? []
    if (isUnauthenticatedFallback(models)) return []
    return mapGrokModels(models)
  } catch {
    return []
  } finally {
    if (timer) clearTimeout(timer)
    child?.kill()
  }
}
