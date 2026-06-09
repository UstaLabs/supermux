import { expect, test } from "bun:test"
import { waitForRegisteredSession } from "../src/core/session-manager/spawn-registration"

test("waitForRegisteredSession resolves after the session appears", async () => {
  let calls = 0
  const session = { id: "sid-1", name: "alpha" }

  const found = await waitForRegisteredSession({
    id: "sid-1",
    name: "alpha",
    intervalMs: 1,
    timeoutMs: 50,
    lookup: () => (++calls >= 3 ? session : undefined),
  })

  expect(found).toBe(session)
  expect(calls).toBe(3)
})

test("waitForRegisteredSession throws when registration never arrives", async () => {
  await expect(waitForRegisteredSession({
    id: "sid-2",
    name: "beta",
    intervalMs: 1,
    timeoutMs: 3,
    lookup: () => undefined,
  })).rejects.toThrow(/timed out waiting for session "beta" to register/)
})
