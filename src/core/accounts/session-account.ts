// Moving a session to another account of its agent (POST /sessions/:id/account), running or asleep (#106).

import { AccountsApiError, toApiError } from "./broker-accounts"

export type SessionAccountDeps = {
  session(id: string): { id: string; agent: string } | undefined
  /** BrokerAccounts.resolveSessionAccount: the account exists, is the agent's, and can run here. */
  resolve(agent: string, account: string): Promise<string | undefined>
  /** The live adapter, when the session runs. */
  adapter(id: string): { setAccount?(id: string): Promise<void> } | undefined
  /** The session is starting: the start already read its account. */
  waking(id: string): boolean
  /** Persists the session's account (the system login as NULL) and tells the clients. */
  store(id: string, account: string): void
}

/**
 * A running session reopens on the account now (Core resume { account }); a sleeping one keeps it for its
 * next wake, which names it explicitly. The account it was on may be gone (removed): only the new one is checked.
 */
export async function setSessionAccount(deps: SessionAccountDeps, sessionId: string, account: string): Promise<{ session: string; account: string; applied: "now" | "next_wake" }> {
  const s = deps.session(sessionId)
  if (!s) throw new AccountsApiError(404, "session not found", "session_not_found")
  const resolved = (await deps.resolve(s.agent, account))!
  if (deps.waking(s.id)) throw new AccountsApiError(409, "the session is starting; try again once it is up", "session_busy")
  const adapter = deps.adapter(s.id)
  let applied: "now" | "next_wake" = "next_wake"
  if (adapter && typeof adapter.setAccount === "function") {
    try {
      await adapter.setAccount(resolved)
    } catch (err) {
      throw toApiError(err)
    }
    applied = "now"
  }
  deps.store(s.id, resolved)
  return { session: s.id, account: resolved, applied }
}
