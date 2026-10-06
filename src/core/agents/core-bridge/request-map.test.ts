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
