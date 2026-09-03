import { test, expect } from "bun:test"
import { formatAsOf } from "./format-relative-time"

const NOW = Date.parse("2026-09-02T12:00:00.000Z")

test("missing or invalid timestamps render as empty", () => {
  expect(formatAsOf(null, NOW)).toBe("")
  expect(formatAsOf(undefined, NOW)).toBe("")
  expect(formatAsOf("", NOW)).toBe("")
  expect(formatAsOf("not-a-date", NOW)).toBe("")
})

test("under a minute is just now", () => {
  expect(formatAsOf("2026-09-02T12:00:00.000Z", NOW)).toBe("just now")
  expect(formatAsOf("2026-09-02T11:59:01.000Z", NOW)).toBe("just now")
  expect(formatAsOf("2026-09-02T12:00:30.000Z", NOW)).toBe("just now")
})

test("minutes ago", () => {
  expect(formatAsOf("2026-09-02T11:58:00.000Z", NOW)).toBe("as of 2m ago")
  expect(formatAsOf("2026-09-02T11:01:00.000Z", NOW)).toBe("as of 59m ago")
})

test("hours ago", () => {
  expect(formatAsOf("2026-09-02T11:00:00.000Z", NOW)).toBe("as of 1h ago")
  expect(formatAsOf("2026-09-01T13:00:00.000Z", NOW)).toBe("as of 23h ago")
})

test("days ago", () => {
  expect(formatAsOf("2026-09-01T12:00:00.000Z", NOW)).toBe("as of 1d ago")
  expect(formatAsOf("2026-08-31T12:00:00.000Z", NOW)).toBe("as of 2d ago")
})
