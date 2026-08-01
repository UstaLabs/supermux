import { test, expect } from "bun:test"
import { CuratorScheduler } from "./scheduler"
import type { CuratorConfig } from "../settings/curator-config"

const cfg = (o: Partial<CuratorConfig>): CuratorConfig => ({ enabled: true, hour: 1, minute: 0, agent: "claude", ...o })

test("enabled config produces a nextRun; disabled clears it", () => {
  const s = new CuratorScheduler(async () => {})
  s.reconfigure(cfg({ enabled: true, hour: 1, minute: 0 }))
  const next = s.nextRun()
  expect(next).not.toBeNull()
  expect(next!.getHours()).toBe(1)
  expect(next!.getMinutes()).toBe(0)

  s.reconfigure(cfg({ enabled: false }))
  expect(s.nextRun()).toBeNull()
  s.stop()
})

test("retiming changes the nextRun", () => {
  const s = new CuratorScheduler(async () => {})
  s.reconfigure(cfg({ hour: 3, minute: 30 }))
  const a = s.nextRun()!
  expect(a.getHours()).toBe(3)
  expect(a.getMinutes()).toBe(30)
  s.reconfigure(cfg({ hour: 7, minute: 45 }))
  const b = s.nextRun()!
  expect(b.getHours()).toBe(7)
  expect(b.getMinutes()).toBe(45)
  s.stop()
})

test("stop() leaves no schedule", () => {
  const s = new CuratorScheduler(async () => {})
  s.reconfigure(cfg({}))
  s.stop()
  expect(s.nextRun()).toBeNull()
})
