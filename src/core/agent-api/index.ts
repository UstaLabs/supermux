// Registry for the direct-API agent adapter layer. Maps an engine string to an
// `AgentApi` instance via `select(engine)`. Voice cleanup picks the configured
// engine (env || app-config || default "codex") and falls back to cursor-cli.
//
// All adapter I/O stays injectable: `select` forwards an optional opts bag
// (fetchFn / readFileFn / run) into whichever adapter it constructs, so the
// orchestrator and tests can drive every engine with no network/disk/subprocess.

import { claudeAdapter } from "./adapters/claude"
import { codexAdapter } from "./adapters/codex"
import { opencodeAdapter } from "./adapters/opencode"
import { cursorCliAdapter, type RunFn } from "./adapters/cursor-cli"
import type { AgentApi, FetchFn, ReadFileFn } from "./types"

// Every engine string the registry understands.
export type Engine = "codex" | "opencode-zen" | "opencode-go" | "claude" | "cursor" | "cursor-cli"

export const ENGINES: Engine[] = ["codex", "opencode-zen", "opencode-go", "claude", "cursor", "cursor-cli"]

// The orchestrator's universal last-resort fallback engine.
export const FALLBACK_ENGINE: Engine = "cursor-cli"

// Injection seam shared by `select` and the orchestrator: any adapter that uses
// it ignores the fields it doesn't need.
export interface SelectOpts {
  fetchFn?: FetchFn
  readFileFn?: ReadFileFn
  run?: RunFn
}

// Registry of engine → adapter factory. `cursor` still maps to the cursor-cli
// adapter: the protobuf adapter exists (adapters/cursor.ts) and speaks the
// StreamChat wire format, but as of 2026-06-21 api2.cursor.sh has DEPRECATED that
// endpoint server-side (ERROR_DEPRECATED for any client version). The live
// replacement (agent.v1.AgentService/Run) is a heavier bidi protocol — out of
// scope for now. Re-point "cursor" at cursorAdapter once StreamChat is
// un-deprecated or the Run path is implemented. See adapters/cursor.ts header.
const registry: Record<Engine, (o: SelectOpts) => AgentApi> = {
  codex: (o) => codexAdapter({ fetchFn: o.fetchFn, readFileFn: o.readFileFn }),
  "opencode-zen": (o) => opencodeAdapter("zen", { fetchFn: o.fetchFn, readFileFn: o.readFileFn }),
  "opencode-go": (o) => opencodeAdapter("go", { fetchFn: o.fetchFn, readFileFn: o.readFileFn }),
  claude: (o) => claudeAdapter({ fetchFn: o.fetchFn, readFileFn: o.readFileFn }),
  cursor: (o) => cursorCliAdapter({ run: o.run }),
  "cursor-cli": (o) => cursorCliAdapter({ run: o.run }),
}

// Resolve an engine string to an adapter. An unknown engine degrades to codex
// (the sanctioned default) so a bad config row never breaks cleanup.
export function select(engine: Engine | string, opts: SelectOpts = {}): AgentApi {
  const factory = registry[engine as Engine] ?? registry.codex
  return factory(opts)
}

// Default engine + model resolution. Env wins; the orchestrator may also pass an
// explicit engine/model from app-config at call time (config is only available at
// runtime, so it is layered there rather than at module load).
export const VOICE_CLEANUP_ENGINE: Engine = ((): Engine => {
  const e = process.env.MUX_VOICE_CLEANUP_ENGINE
  return e && (ENGINES as string[]).includes(e) ? (e as Engine) : "codex"
})()

export const VOICE_CLEANUP_MODEL: string | undefined = process.env.MUX_VOICE_CLEANUP_MODEL || undefined
