import { expect, test } from "bun:test"
import { createMemorySessionBackend } from "./session-backend.test-support"
import { createPlatformSessionBackend, getSessionBackend, setSessionBackendForTests } from "./index"

test("session backend test override can be reset without leaking state", () => {
  const memory = createMemorySessionBackend()

  try {
    setSessionBackendForTests(memory)
    expect(getSessionBackend()).toBe(memory)
  } finally {
    setSessionBackendForTests()
  }

  if (process.platform === "win32") {
    expect(getSessionBackend).toThrow("Windows session backend is not initialized")
  } else {
    expect(getSessionBackend()).not.toBe(memory)
  }
})

test("Windows platform selection constructs only the native sessiond backend", () => {
  let posixConstructed = false
  const windows = createMemorySessionBackend()
  expect(createPlatformSessionBackend("win32", () => {
    posixConstructed = true
    return createMemorySessionBackend()
  }, () => windows)).toBe(windows)
  expect(posixConstructed).toBe(false)
})
