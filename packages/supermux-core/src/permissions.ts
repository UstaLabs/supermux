import type { PermissionsSpec, ToolKind } from "./types.js"

const CLAUDE_MODES = new Set(["bypassPermissions", "acceptEdits", "default", "plan", "auto", "dontAsk"])
const CODEX_POLICIES = new Set(["never", "on-request", "untrusted"])
const CODEX_SANDBOXES = new Set(["read-only", "workspace-write", "danger-full-access"])
const ACP_POLICIES = new Set(["auto-approve", "ask", "read-only"])
const TOOL_KINDS = new Set<ToolKind>(["read", "edit", "delete", "move", "search", "execute", "fetch", "other"])

export function appliedFor(spec: PermissionsSpec): "now" | "next-turn" {
  return spec.kind === "codex" ? "next-turn" : "now"
}

export function validatePermissionsSpec(value: unknown): PermissionsSpec {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError("permissions spec is required")
  }
  const rec = value as Record<string, unknown>
  if (rec.kind === "claude") {
    if (typeof rec.permissionMode !== "string" || !CLAUDE_MODES.has(rec.permissionMode)) {
      throw new TypeError("Claude permissionMode is required")
    }
    return { kind: "claude", permissionMode: rec.permissionMode as Extract<PermissionsSpec, { kind: "claude" }>["permissionMode"] }
  }
  if (rec.kind === "codex") {
    if (typeof rec.approvalPolicy !== "string" || !CODEX_POLICIES.has(rec.approvalPolicy)) {
      throw new TypeError("Codex approvalPolicy is required")
    }
    if (typeof rec.sandbox !== "string" || !CODEX_SANDBOXES.has(rec.sandbox)) {
      throw new TypeError("Codex sandbox is required")
    }
    return {
      kind: "codex",
      approvalPolicy: rec.approvalPolicy as Extract<PermissionsSpec, { kind: "codex" }>["approvalPolicy"],
      sandbox: rec.sandbox as Extract<PermissionsSpec, { kind: "codex" }>["sandbox"],
    }
  }
  if (rec.kind === "acp") {
    if (typeof rec.policy !== "string" || !ACP_POLICIES.has(rec.policy)) {
      throw new TypeError("ACP policy is required")
    }
    if (rec.nativeMode !== null && typeof rec.nativeMode !== "string") {
      throw new TypeError("ACP nativeMode is required")
    }
    const spec: Extract<PermissionsSpec, { kind: "acp" }> = {
      kind: "acp",
      policy: rec.policy as Extract<PermissionsSpec, { kind: "acp" }>["policy"],
      nativeMode: rec.nativeMode,
    }
    if (rec.askKinds !== undefined) {
      if (!Array.isArray(rec.askKinds) || rec.askKinds.some(k => typeof k !== "string" || !TOOL_KINDS.has(k as ToolKind))) {
        throw new TypeError("ACP askKinds is required")
      }
      spec.askKinds = rec.askKinds as ToolKind[]
    }
    return spec
  }
  throw new TypeError("permissions spec kind is required")
}

const MUTATING: ReadonlySet<string> = new Set(["edit", "execute", "delete", "move"])
const READISH: ReadonlySet<string> = new Set(["read", "search", "fetch", "other"])

export function acpPermissionDecision(
  spec: Extract<PermissionsSpec, { kind: "acp" }>,
  toolKind: string | undefined,
  options: { optionId: string; kind: string }[],
): { auto: true; optionId: string; message?: string } | { auto: false } {
  const allow = options.find(o => o.kind === "allow_once" || o.kind === "allow_always")
  const reject = options.find(o => o.kind === "reject_once" || o.kind === "reject_always")
  const kind = toolKind ?? "other"
  if (spec.policy === "auto-approve") {
    if (!allow) return { auto: false }
    return { auto: true, optionId: allow.optionId }
  }
  if (spec.policy === "ask" && spec.askKinds?.length) {
    if (spec.askKinds.includes(kind as ToolKind)) return { auto: false }
    if (!allow) return { auto: false }
    return { auto: true, optionId: allow.optionId }
  }
  if (spec.policy === "read-only") {
    if (MUTATING.has(kind)) {
      if (!reject) return { auto: false }
      return { auto: true, optionId: reject.optionId, message: "read-only session" }
    }
    if (READISH.has(kind) || !MUTATING.has(kind)) {
      if (!allow) return { auto: false }
      return { auto: true, optionId: allow.optionId }
    }
  }
  return { auto: false }
}
