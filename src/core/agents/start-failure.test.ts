import { expect, test } from "bun:test"
import { CoreError } from "../../../packages/supermux-core/src/errors.js"
import { AgentKind } from "../../shared/agents"
import { AgentNotInstalledError } from "./detect"
import { startFailureNotice, wakeFailureNotice } from "./start-failure"

const expired = () => new CoreError("account_expired", "Cursor sign-in expired — sign in to Cursor again.")

test("a refused login is said in its own words, for a start and for a resume", () => {
  expect(startFailureNotice("start", "fix-login", expired())).toBe(`Couldn't start "fix-login": Cursor sign-in expired — sign in to Cursor again.`)
  expect(startFailureNotice("resume", "fix-login", expired())).toBe(`Couldn't resume "fix-login": Cursor sign-in expired — sign in to Cursor again.`)
})

test("a refused login is found under a wrapper (the host's cleanup failure keeps it as the cause)", () => {
  const wrapped = new Error("Cursor sign-in expired — sign in to Cursor again.; cleanup failed: busy", { cause: expired() })
  expect(startFailureNotice("start", "fix-login", wrapped)).toBe(`Couldn't start "fix-login": Cursor sign-in expired — sign in to Cursor again.`)
})

test("a missing CLI keeps its own sentence; anything else stays generic", () => {
  expect(startFailureNotice("start", "x", new AgentNotInstalledError(AgentKind.Cursor))).toBe(new AgentNotInstalledError(AgentKind.Cursor).message)
  expect(startFailureNotice("start", "x", Object.assign(new Error("Internal error"), { code: -32603 }))).toBe(`Failed to start session "x".`)
  expect(startFailureNotice("resume", "x", undefined)).toBe(`Failed to resume session "x".`)
  // Telegram's own words for the rest; a refused login still says why.
  expect(startFailureNotice("resume", "x", undefined, "Failed to resume. Try /kill.")).toBe("Failed to resume. Try /kill.")
  expect(startFailureNotice("resume", "x", expired(), "Failed to resume. Try /kill.")).toBe(`Couldn't resume "x": Cursor sign-in expired — sign in to Cursor again.`)
})

// Card #75: a session's wake failed (a new session's first start, a dozing one's resume).
test("a failed wake says why in the error's own words, and that the next message tries again", () => {
  expect(wakeFailureNotice(AgentKind.Claude, new Error("fatal: invalid reference: release/9")))
    .toBe("Couldn't start Claude: fatal: invalid reference: release/9. Your next message tries again.")
  expect(wakeFailureNotice(AgentKind.Codex, new Error("the agent was not ready to take messages after 60 s.\n    at stack")))
    .toBe("Couldn't start Codex: the agent was not ready to take messages after 60 s. Your next message tries again.")
})

test("a failed wake: a refused login under a wrapper keeps its words; a missing CLI keeps its own sentence", () => {
  const wrapped = new Error("Cursor sign-in expired — sign in to Cursor again.; cleanup failed: busy", { cause: expired() })
  expect(wakeFailureNotice(AgentKind.Cursor, wrapped)).toBe("Couldn't start Cursor: Cursor sign-in expired — sign in to Cursor again. Your next message tries again.")
  expect(wakeFailureNotice(AgentKind.Grok, new AgentNotInstalledError(AgentKind.Grok))).toBe(new AgentNotInstalledError(AgentKind.Grok).message)
})

test("a failed wake with no usable reason still says it failed, and a long one is cut", () => {
  expect(wakeFailureNotice(AgentKind.OpenCode, undefined)).toBe("Couldn't start OpenCode: it did not come up. Your next message tries again.")
  const long = wakeFailureNotice(AgentKind.Claude, new Error("x".repeat(1000)))
  expect(long.length).toBeLessThan(400)
  expect(long.endsWith("…. Your next message tries again.")).toBe(true)
})
