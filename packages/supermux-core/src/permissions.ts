import { isAbsolute, resolve, sep } from "node:path"
import type { PermissionsSpec, ToolKind } from "./types.js"

const CLAUDE_MODES = new Set(["bypassPermissions", "acceptEdits", "default", "plan", "auto", "dontAsk"])
const CODEX_POLICIES = new Set(["never", "on-request", "untrusted"])
const CODEX_SANDBOXES = new Set(["read-only", "workspace-write", "danger-full-access"])
const ACP_POLICIES = new Set(["auto-approve", "ask", "read-only"])
const ANTIGRAVITY_MODES = new Set(["bypass", "acceptEdits", "plan", "default"])
const TOOL_KINDS = new Set<ToolKind>(["read", "edit", "delete", "move", "search", "execute", "fetch", "other"])

export function appliedFor(spec: PermissionsSpec): "now" | "next-turn" {
  return spec.kind === "codex" || spec.kind === "antigravity" ? "next-turn" : "now"
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
    if (rec.readableRoots !== undefined) {
      if (!Array.isArray(rec.readableRoots) || rec.readableRoots.some(r => typeof r !== "string" || !isAbsolute(r))) {
        throw new TypeError("ACP readableRoots must be absolute paths")
      }
      spec.readableRoots = rec.readableRoots as string[]
    }
    return spec
  }
  if (rec.kind === "antigravity") {
    if (typeof rec.mode !== "string" || !ANTIGRAVITY_MODES.has(rec.mode)) {
      throw new TypeError("Antigravity mode is required")
    }
    return { kind: "antigravity", mode: rec.mode as Extract<PermissionsSpec, { kind: "antigravity" }>["mode"] }
  }
  throw new TypeError("permissions spec kind is required")
}

/**
 * The spec a driver launches under: the session's own when it is of the driver's kind (validated,
 * so a malformed one throws before anything starts), else the factory's. A spec of another kind is
 * some other agent's, not an error.
 */
export function launchPermissions<K extends PermissionsSpec["kind"]>(kind: K, session: PermissionsSpec | undefined, factory: Extract<PermissionsSpec, { kind: K }>): Extract<PermissionsSpec, { kind: K }> {
  return session?.kind === kind ? validatePermissionsSpec(session) as Extract<PermissionsSpec, { kind: K }> : factory
}

const MUTATING: ReadonlySet<string> = new Set(["edit", "execute", "delete", "move"])
const READISH: ReadonlySet<string> = new Set(["read", "search", "fetch", "other"])

function withinRoots(path: string, roots: readonly string[]): boolean {
  if (!isAbsolute(path)) return false
  const target = resolve(path)
  return roots.some(root => {
    const base = resolve(root)
    return target === base || target.startsWith(base + sep)
  })
}

export function acpPermissionDecision(
  spec: Extract<PermissionsSpec, { kind: "acp" }>,
  toolKind: string | undefined,
  options: { optionId: string; kind: string }[],
  paths: readonly string[] = [],
): { auto: true; optionId: string; message?: string } | { auto: false } {
  const allow = options.find(o => o.kind === "allow_once" || o.kind === "allow_always")
  const reject = options.find(o => o.kind === "reject_once" || o.kind === "reject_always")
  const kind = toolKind ?? "other"
  if (allow && spec.readableRoots?.length && (kind === "read" || kind === "search")
      && paths.length > 0 && paths.every(p => withinRoots(p, spec.readableRoots!))) {
    return { auto: true, optionId: allow.optionId }
  }
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
