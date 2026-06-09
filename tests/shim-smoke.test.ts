import { test, expect } from "bun:test"

test("shim entry module imports cleanly", async () => {
  // Don't run main(); just check imports succeed.
  const mod = await import("../src/shim/tools")
  expect(typeof mod.listTools).toBe("function")
  expect(typeof mod.callTool).toBe("function")
})
