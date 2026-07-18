import { expect, test } from "bun:test"
import { createMemorySessionBackend } from "./session-backend.test-support"
import { getSessionBackend, setSessionBackendForTests } from "./index"

test("session backend test override can be reset without leaking state", () => {
  const platformDefault = getSessionBackend()
  const memory = createMemorySessionBackend()

  try {
    setSessionBackendForTests(memory)
    expect(getSessionBackend()).toBe(memory)
  } finally {
    setSessionBackendForTests()
  }

  expect(getSessionBackend()).toBe(platformDefault)
})
