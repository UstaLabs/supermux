import { expect, test } from "bun:test"
import { CoreError, UnsupportedOperation } from "../../../packages/supermux-core/src/errors.js"
import { subagentActionError } from "./subagent-action-error"

test("subagent action errors map to client statuses, not 500", () => {
  expect(subagentActionError(new CoreError("session_busy", "The OpenCode subagent has not reported its session yet"))).toEqual({ ok: false, status: 409, error: "The OpenCode subagent has not reported its session yet" })
  expect(subagentActionError(new UnsupportedOperation("subagent stop", "cursor"))).toMatchObject({ status: 409 })
  expect(subagentActionError(new CoreError("subagent_not_found", "Unknown subagent x"))).toMatchObject({ status: 404 })
  expect(subagentActionError(new CoreError("invalid_input", "empty"))).toMatchObject({ status: 400 })
  expect(subagentActionError(new Error("boom"))).toMatchObject({ status: 500 })
})
