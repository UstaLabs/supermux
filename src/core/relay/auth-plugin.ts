import { verifyLease } from "./lease"

export interface AuthOpCtx { secret: string; now?: () => number }
export interface AuthResponse { reject: boolean; reject_reason?: string; unchange?: boolean }

// Shapes are frp's server-plugin protocol (verified against frp 0.61 in the
// spike — docs/relay/SPIKE.md). Login carries client `metas`; NewProxy carries
// `user.metas` plus a FLAT `subdomain`/`proxy_type` on `content` (NOT nested).
type Op =
  | { op: "Login"; content: { metas?: Record<string, string> } }
  | { op: "NewProxy"; content: { user?: { metas?: Record<string, string> }; subdomain?: string; proxy_type?: string } }
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
    if (op.content.proxy_type !== "http") return deny("only http proxies permitted")
    if (op.content.subdomain !== `h-${v.hostId}`) return deny("subdomain does not match leased hostId")
    return ok
  }
  return ok // Ping and others pass through
}
