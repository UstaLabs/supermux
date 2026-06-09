import { test, expect } from "bun:test"
import { parseCuratorConfig, toCron, defaultCuratorConfig } from "../src/core/settings/curator-config"

test("parseCuratorConfig clamps hour/minute and ignores stray chatId", () => {
  expect(parseCuratorConfig({ enabled: true, hour: 9, minute: 30 })).toEqual({
    enabled: true, hour: 9, minute: 30,
  })
  // out of range → clamped
  expect(parseCuratorConfig({ hour: 99, minute: -5 })).toMatchObject({ hour: 23, minute: 0 })
  // a stray chatId from an older stored config is dropped
  expect(parseCuratorConfig({ enabled: true, hour: 2, minute: 0, chatId: "web:x" } as any)).toEqual({
    enabled: true, hour: 2, minute: 0,
  })
  // garbage → safe defaults, never throws
  expect(() => parseCuratorConfig("nonsense")).not.toThrow()
  expect(parseCuratorConfig(undefined)).toEqual(defaultCuratorConfig)
})

test("toCron compiles daily HH:MM", () => {
  expect(toCron({ enabled: true, hour: 1, minute: 0 })).toBe("0 1 * * *")
  expect(toCron({ enabled: true, hour: 13, minute: 45 })).toBe("45 13 * * *")
})
