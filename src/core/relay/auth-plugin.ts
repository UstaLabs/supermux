import { verifyLease } from "./lease"

export interface AuthOpCtx { secret: string; now?: () => number }
export interface AuthResponse { reject: boolean; reject_reason?: string; unchange?: boolean }

// Shapes are frp's server-plugin protocol (subset we use). See docs/relay/SPIKE.md.
type Op =
  | { op: "Login"; content: { metas?: Record<string, string> } }
  | { op: "NewProxy"; content: { user?: { metas?: Record<string, string> }; proxy_config?: { subdomain?: string; proxy_type?: string } } }
  | { op: "Ping"; content: unknown }

const ok: AuthResponse = { reject: false, unchange: true }
const deny = (reason: string): AuthResponse => ({ reject: true, reject_reason: reason })

export function handleAuthOp(op: Op, ctx: AuthOpCtx): AuthResponse {
  const now = ctx.now?.() ?? Date.now()
  if (op.op === "Login") {
    const lease = op.content.metas?.lease
    if (!lease || !verifyLease(lease, { secret: ctx.secret, now }).ok) return deny("invalid or missing lease")
    return ok
  }
  if (op.op === "NewProxy") {
    const lease = op.content.user?.metas?.lease ?? ""
    const v = verifyLease(lease, { secret: ctx.secret, now })
    if (!v.ok) return deny("invalid lease")
    const cfg = op.content.proxy_config ?? {}
    if (cfg.proxy_type !== "http") return deny("only http proxies permitted")
    if (cfg.subdomain !== `h-${v.hostId}`) return deny("subdomain does not match leased hostId")
    return ok
  }
  return ok // Ping and others pass through
}
