import { test, expect } from "bun:test"
import { waitForRegisteredSession } from "./spawn-registration"

test("returns as soon as the session registers (no blind wait)", async () => {
  let calls = 0
  const found = await waitForRegisteredSession<string>({
    id: "id1", name: "n1",
    lookup: () => (++calls >= 2 ? "session" : undefined),
    stillAlive: async () => true,
    intervalMs: 1, timeoutMs: 1000,
  })
  expect(found).toBe("session")
})

test("fast-fails when the window dies before registering", async () => {
  let alive = true
  const p = waitForRegisteredSession<string>({
    id: "id1", name: "n1",
    lookup: () => undefined,            // never registers
    stillAlive: async () => alive,
    intervalMs: 1, timeoutMs: 5000,
  })
  alive = false
  await expect(p).rejects.toThrow(/did not survive/)
})

test("times out if never registered but still alive", async () => {
  await expect(
    waitForRegisteredSession<string>({
      id: "id1", name: "n1",
      lookup: () => undefined,
      stillAlive: async () => true,
      intervalMs: 1, timeoutMs: 20,
    }),
  ).rejects.toThrow(/timed out/)
})
