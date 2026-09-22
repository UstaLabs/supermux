import type { AgentAdapter } from "../types"
import { CoreAdapter } from "./core-adapter"

/** Core-backed adapters emit `activity` cards themselves; `tool-call` is status-only. */
export function isCoreBacked(adapter: AgentAdapter): boolean {
  return adapter instanceof CoreAdapter
}
