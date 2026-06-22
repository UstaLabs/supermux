// Core interface for the direct-API agent adapter layer. Each agent implements
// `AgentApi` to do a one-shot text completion against its model backend using its
// existing subscription credentials. All HTTP via injectable `fetchFn`, all file
// reads via injectable `readFileFn` → fully unit-testable.

export interface CompleteOpts {
  model?: string
  timeoutMs?: number
  signal?: AbortSignal
}

export interface AgentApi {
  readonly name: string // "codex" | "opencode-zen" | ...
  isAvailable(): boolean // creds on disk + (gated agents) opt-in flag set; NO network call
  complete(prompt: string, opts?: CompleteOpts): Promise<string> // one-shot; returns assistant text; THROWS on failure
}

export type FetchFn = typeof fetch
export type ReadFileFn = (path: string) => string // throws if missing

export const DEFAULT_TIMEOUT_MS = Number(process.env.MUX_VOICE_CLEANUP_TIMEOUT_MS ?? 30_000)
