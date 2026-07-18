import { expect, test } from "bun:test"
import { resumedSessionPid } from "./resume-pid"

test("structured agent pid zero falls back to the broker pid", () => {
  expect(resumedSessionPid(null, 0, 4242)).toBe(4242)
})

test("Claude runtime pid wins over both stored and broker pids", () => {
  expect(resumedSessionPid(31337, 0, 4242)).toBe(31337)
})
