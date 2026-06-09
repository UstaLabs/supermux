export type SessionRole = "personal_assistant" | "worker"

export type Capabilities = {
  can_orchestrate: boolean
  persistent: boolean        // supervised / auto-respawned
  fallback_eligible: boolean // eligible as default + unrouted-inbound target
  user_named: boolean        // true => user-chosen name; false => auto-generated
}

export const POLICY: Record<SessionRole, Capabilities> = {
  personal_assistant: { can_orchestrate: true,  persistent: true,  fallback_eligible: true,  user_named: true },
  worker:             { can_orchestrate: false, persistent: false, fallback_eligible: false, user_named: false },
}

export function canOrchestrate(role: SessionRole): boolean { return POLICY[role].can_orchestrate }
export function isPersistent(role: SessionRole): boolean { return POLICY[role].persistent }
export function isFallbackEligible(role: SessionRole): boolean { return POLICY[role].fallback_eligible }
