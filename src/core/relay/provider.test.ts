import { expect, test } from "bun:test"
import { NullRelayProvider } from "./provider"

test("null provider reports disabled and never throws", async () => {
  const p = new NullRelayProvider()
  expect(p.status().state).toBe("disabled")
  await p.start()
  expect(p.status().state).toBe("disabled")
  await p.stop()
})
