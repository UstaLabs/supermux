import { test, expect } from "bun:test"
import { allocateFreePort, allocateDisplayNumber } from "../src/core/display/ports"

test("allocateFreePort returns a bindable TCP port", async () => {
  const port = await allocateFreePort()
  expect(port).toBeGreaterThan(1024)
  // Should be immediately bindable since we released it.
  const server = Bun.listen({ hostname: "127.0.0.1", port, socket: { data() {} } })
  expect(server.port).toBe(port)
  server.stop()
})

test("allocateDisplayNumber returns an integer >= 99", () => {
  const n = allocateDisplayNumber()
  expect(Number.isInteger(n)).toBe(true)
  expect(n).toBeGreaterThanOrEqual(99)
})
