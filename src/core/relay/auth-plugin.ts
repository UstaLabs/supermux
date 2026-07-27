import { verifyLease, type VerifyFailureReason, type VerifyResult } from "./lease"

export type AuthRejectionReason =
  | "missing_lease"
  | "malformed_lease"
  | "invalid_lease_expiry"
  | "invalid_lease_signature"
  | "expired_lease"
  | "unsupported_proxy_type"
  | "subdomain_mismatch"

export interface AuthRejectionEvent {
  operation: "Login" | "NewProxy"
  reason: AuthRejectionReason
  hostId?: string
  leaseExpiresAt?: number
  expiredByMs?: number
  clientAddress?: string
  proxyType?: string
  subdomain?: string
}

export interface AuthOpCtx {
  secret: string
  now?: () => number
  onReject?: (event: AuthRejectionEvent) => void
}
export interface AuthResponse { reject: boolean; reject_reason?: string; unchange?: boolean }

// Shapes are frp's server-plugin protocol (verified against frp 0.61 in the
// spike — docs/relay/SPIKE.md). Login carries client `metas`; NewProxy carries
// `user.metas` plus a FLAT `subdomain`/`proxy_type` on `content` (NOT nested).
type Op =
  | { op: "Login"; content: { metas?: Record<string, string>; client_address?: string } }
  | { op: "NewProxy"; content: { user?: { metas?: Record<string, string> }; subdomain?: string; proxy_type?: string } }
  | { op: "Ping"; content: unknown }

const ok: AuthResponse = { reject: false, unchange: true }
const leaseRejectionReasons: Record<VerifyFailureReason, AuthRejectionReason> = {
  missing: "missing_lease",
  malformed: "malformed_lease",
  invalid_expiry: "invalid_lease_expiry",
  invalid_signature: "invalid_lease_signature",
  expired: "expired_lease",
}

function deny(ctx: AuthOpCtx, event: AuthRejectionEvent, externalReason: string): AuthResponse {
  try {
    ctx.onReject?.(event)
  } catch {}
  return { reject: true, reject_reason: externalReason }
}

function leaseRejectionEvent(
  operation: "Login" | "NewProxy",
  result: Extract<VerifyResult, { ok: false }>,
  now: number,
  clientAddress?: string,
): AuthRejectionEvent {
  const event: AuthRejectionEvent = { operation, reason: leaseRejectionReasons[result.reason] }
  if (clientAddress !== undefined) event.clientAddress = clientAddress
  if (result.reason === "expired") {
    event.hostId = result.hostId
    event.leaseExpiresAt = result.expiresAt
    event.expiredByMs = now - result.expiresAt
  }
  return event
}

export function handleAuthOp(op: Op, ctx: AuthOpCtx): AuthResponse {
  const now = ctx.now?.() ?? Date.now()
  if (op.op === "Login") {
    const verification = verifyLease(op.content.metas?.lease ?? "", { secret: ctx.secret, now })
    if (!verification.ok) {
      return deny(
        ctx,
        leaseRejectionEvent("Login", verification, now, op.content.client_address),
        "invalid or missing lease",
      )
    }
    return ok
  }
  if (op.op === "NewProxy") {
    const lease = op.content.user?.metas?.lease ?? ""
    const v = verifyLease(lease, { secret: ctx.secret, now })
    if (!v.ok) return deny(ctx, leaseRejectionEvent("NewProxy", v, now), "invalid lease")
    if (op.content.proxy_type !== "http") {
      return deny(ctx, {
        operation: "NewProxy",
        reason: "unsupported_proxy_type",
        hostId: v.hostId,
        proxyType: op.content.proxy_type,
      }, "only http proxies permitted")
    }
    if (op.content.subdomain !== `h-${v.hostId}`) {
      return deny(ctx, {
        operation: "NewProxy",
        reason: "subdomain_mismatch",
        hostId: v.hostId,
        subdomain: op.content.subdomain,
      }, "subdomain does not match leased hostId")
    }
    return ok
  }
  return ok // Ping and others pass through
}
