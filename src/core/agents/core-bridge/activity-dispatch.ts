import type { AgentAdapter } from "../types"
import { CoreCodexAdapter } from "../codex/core-adapter"
import { CoreGrokAdapter } from "../grok/core-adapter"
import { CoreOpenCodeAdapter } from "../opencode/core-adapter"

/** Core-backed adapters emit `activity` cards themselves; `tool-call` is status-only. */
export function isCoreBacked(adapter: AgentAdapter): boolean {
  return adapter instanceof CoreCodexAdapter || adapter instanceof CoreGrokAdapter || adapter instanceof CoreOpenCodeAdapter
}
