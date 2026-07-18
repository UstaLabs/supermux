import { expect, test } from "bun:test"
import { verifySessionBackendContract } from "./session-backend.test-support"
import { createMemorySessionBackendHarness } from "./session-backend.test-support"

test("memory backend satisfies the runtime contract", async () => {
  const { backend, observation } = createMemorySessionBackendHarness()
  await verifySessionBackendContract(backend, observation)
})
