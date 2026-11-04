// The chat notice for an agent that could not start (a draft's first message) or resume (a suspended
// chat). An error that is already a sentence for the user keeps its words: the CLI is not installed, or
// the agent refused the login (an expired Cursor sign-in). Anything else stays generic; its details are
// in the broker log.
import { SIGN_IN_EXPIRED } from "../accounts/broker-accounts"
import { AgentNotInstalledError } from "./detect"

/** `generic`: the channel's own words for any other failure (default "Failed to start/resume session …"). */
export function startFailureNotice(what: "start" | "resume", name: string, error: unknown, generic?: string): string {
  if (error instanceof AgentNotInstalledError) return error.message
  const refused = signInRefusal(error)
  if (refused) return `Couldn't ${what} "${name}": ${refused.message}`
  return generic ?? (what === "start" ? `Failed to start session "${name}".` : `Failed to resume session "${name}".`)
}

/** The refused-login error in `error` or its causes (the host wraps a failed cleanup around it). */
function signInRefusal(error: unknown): Error | undefined {
  const seen = new Set<unknown>()
  for (let e = error; e instanceof Error && !seen.has(e); e = e.cause) {
    seen.add(e)
    if ((e as { code?: unknown }).code === SIGN_IN_EXPIRED) return e
  }
  return undefined
}
