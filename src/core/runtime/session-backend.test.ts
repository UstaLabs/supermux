import { expect, test } from "bun:test"
import { verifySessionBackendContract } from "./session-backend.test-support"
import { createMemorySessionBackend } from "./session-backend.test-support"

test("memory backend satisfies the runtime contract", async () => {
  await verifySessionBackendContract(createMemorySessionBackend())
})
