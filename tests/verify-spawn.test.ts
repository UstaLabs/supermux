import { test, expect } from "bun:test"
import { verifySpawnSurvived } from "../src/core/session-manager/verify-spawn"

test("returns true when the window is still listed after the wait", async () => {
  const ok = await verifySpawnSurvived({
    name: "alive",
    listWindows: async () => ["alive", "ana"],
    waitMs: 10,
  })
  expect(ok).toBe(true)
})

test("returns false when the window is gone after the wait (silent spawn failure)", async () => {
  const ok = await verifySpawnSurvived({
    name: "dead",
    listWindows: async () => ["ana"],
    waitMs: 10,
  })
  expect(ok).toBe(false)
})

test("returns false when listWindows returns empty (tmux unreachable / no session)", async () => {
  const ok = await verifySpawnSurvived({
    name: "lost",
    listWindows: async () => [],
    waitMs: 10,
  })
  expect(ok).toBe(false)
})

test("does not poll — calls listWindows exactly once after the wait", async () => {
  let calls = 0
  const ok = await verifySpawnSurvived({
    name: "x",
    listWindows: async () => { calls++; return ["x"] },
    waitMs: 10,
  })
  expect(ok).toBe(true)
  expect(calls).toBe(1)
})
