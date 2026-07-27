import type { ModelInfo } from "../../models/discovery"
import { AcpClient } from "./acp-client"
import { realGrokRunner, type GrokRunner } from "./runner"

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
    return mapGrokModels(models)
  } catch {
    return []
  } finally {
    if (timer) clearTimeout(timer)
    child?.kill()
  }
}
