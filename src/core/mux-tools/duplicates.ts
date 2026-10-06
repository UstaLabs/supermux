/**
 * C3b: detect a Claude session that ended up with TWO `mux-shim` MCP servers (every tool call would
 * then run twice: a reply sent twice; orchestration is de-duplicated by the shared handler).
 * The risk is a `mux-shim` entry left in a ~/.claude.json the broker did not clean (another
 * install's, a user-added one, a second HOME). Claude 2.1.289 keeps only the --mcp-config server
 * of a duplicated name, so this should never fire; it is here to notice if that changes.
 * Source: Claude's `system/init` frame (`mcp_servers`), which the core forwards as a native
 * session.update.
 */
import type { MuxShimMode } from "./mode"

type Init = { muxShim: number; muxChannel: number }

export function muxServersInInit(frame: unknown): Init | undefined {
  const f = frame as { type?: unknown; subtype?: unknown; mcp_servers?: unknown }
  if (!f || f.type !== "system" || f.subtype !== "init") return undefined
  const servers = Array.isArray(f.mcp_servers) ? f.mcp_servers as Array<{ name?: unknown }> : []
  return {
    muxShim: servers.filter(s => s?.name === "mux-shim").length,
    muxChannel: servers.filter(s => s?.name === "mux-channel").length,
  }
}

type CoreLike = { subscribe(listener: (event: any) => void): unknown }
type Log = { warn(event: string, fields: Record<string, unknown>): void; info(event: string, fields: Record<string, unknown>): void }

export function watchClaudeMuxShimDuplicates(core: CoreLike, log: Log, mode: () => MuxShimMode): void {
  const warned = new Set<string>()
  core.subscribe((event) => {
    if (event?.type !== "session.update" || event.update?.protocol !== "native") return
    const found = muxServersInInit(event.update.value)
    if (!found || warned.has(event.sessionId)) return
    const servers = (event.update.value.mcp_servers as Array<{ name?: string; status?: string }>)
      .filter(s => s?.name === "mux-shim" || s?.name === "mux-channel")
      .map(s => `${s.name}:${s.status ?? "?"}`)
    if (found.muxShim > 1) {
      warned.add(event.sessionId)
      log.warn("claude_duplicate_mux_shim", { session: event.sessionId, mode: mode(), servers })
    } else if (found.muxChannel > 0 && mode() === "host") {
      // A leftover ~/.claude.json mux-channel (zero tools): a wasted process, no double calls.
      warned.add(event.sessionId)
      log.info("claude_stray_mux_channel", { session: event.sessionId, servers })
    }
  })
}
