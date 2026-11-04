import { expect, test } from "bun:test"
import { CoreError } from "../../../packages/supermux-core/src/errors.js"
import { AgentKind } from "../../shared/agents"
import { AgentNotInstalledError } from "./detect"
import { startFailureNotice } from "./start-failure"

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
