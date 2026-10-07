/**
 * C3b rollout switch: how agents get the broker's own tools (mux-shim, mux-rpc).
 *
 *   "external" (default): the C3a launch, byte for byte: each agent starts `src/shim` (a stdio
 *              MCP server) that talks to the broker over the per-session socket; Claude reads it
 *              from ~/.claude.json (system account) or the account's --mcp-config.
 *   "host":    a host MCP server inside the broker (src/core/mux-tools/server.ts) given to every
 *              agent through its session context (the core's bridge); nothing in ~/.claude.json.
 *
 * Cursor follows the setting like every agent (host servers through the bridge proven live on
 * Cursor, C2 2026-10-05).
 * Resolved ONCE at boot (setting `muxShim` in the settings table → env MUX_SHIM → "external");
 * changing it takes a broker restart. A session's context is replaced on every resume, so a
 * flip reaches existing sessions at their next launch.
 */
export type MuxShimMode = "external" | "host"

export const SETTINGS_KEY_MUX_SHIM = "muxShim"

export function parseMuxShimMode(value: unknown): MuxShimMode | undefined {
  return value === "external" || value === "host" ? value : undefined
}

/** stored setting → env MUX_SHIM → "external". */
export function resolveMuxShimMode(stored: unknown, env: string | undefined): MuxShimMode {
  return parseMuxShimMode(stored) ?? parseMuxShimMode(env) ?? "external"
}

let current: MuxShimMode = "external"

export function muxShimMode(): MuxShimMode {
  return current
}

/** Boot (and tests) only. */
export function setMuxShimMode(mode: MuxShimMode): void {
  current = mode
}

/** The mode an agent's launch uses (the same for every agent). */
export function muxShimModeFor(_agent: string): MuxShimMode {
  return current
}
