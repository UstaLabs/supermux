import { test, expect } from "bun:test"
import { answerFromTelegramText, mapPermissionRequest } from "./request-map"

test("telegram label match uses optionId", () => {
  const req = mapPermissionRequest({
    kind: "permission-request",
    requestId: "r1",
    toolCall: { callId: "c", tool: "execute", title: "Bash" },
    options: [
      { id: "allow_once", kind: "allow_once", label: "Allow once" },
      { id: "reject_once", kind: "reject_once", label: "Reject" },
    ],
    detail: { command: "ls" },
  })
  expect(answerFromTelegramText(req, "Allow once")).toEqual({ optionId: "allow_once" })
  expect(answerFromTelegramText(req, "nope")).toEqual({ optionId: "reject_once", message: "nope" })
})

// #143: Claude Code asks even in bypassPermissions when its own safety check trips; the prompt says why.
test("a permission request carries the agent's reason for asking", () => {
  const req = mapPermissionRequest({
    kind: "permission-request",
    requestId: "r1",
    toolCall: { callId: "c", tool: "execute", title: "Bash" },
    options: [{ id: "allow_once", kind: "allow_once", label: "Allow once" }],
    detail: { command: "rm -f $S/*", reason: "Dangerous rm operation on possibly-empty variable path" },
  })
  expect(req.reason).toBe("Dangerous rm operation on possibly-empty variable path")
  expect(req.body).toBe("execute rm -f $S/*")
})
