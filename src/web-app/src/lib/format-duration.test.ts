import { test, expect } from "bun:test"
import { formatDuration } from "./format-duration"

test("seconds (singular/plural)", () => {
  expect(formatDuration(0)).toBe("0 seconds")
  expect(formatDuration(1)).toBe("1 second")
  expect(formatDuration(5)).toBe("5 seconds")
  expect(formatDuration(59)).toBe("59 seconds")
})

test("minutes + seconds", () => {
  expect(formatDuration(60)).toBe("1 minute")
  expect(formatDuration(65)).toBe("1 minute 5 seconds")
  expect(formatDuration(185)).toBe("3 minutes 5 seconds")
  expect(formatDuration(180)).toBe("3 minutes")
})

test("hours + minutes (no seconds)", () => {
  expect(formatDuration(3600)).toBe("1 hour")
  expect(formatDuration(3725)).toBe("1 hour 2 minutes")
  expect(formatDuration(7200)).toBe("2 hours")
})

test("days + hours", () => {
  expect(formatDuration(86400)).toBe("1 day")
  expect(formatDuration(90000)).toBe("1 day 1 hour")
  expect(formatDuration(180000)).toBe("2 days 2 hours")
})

test("clamps negatives / floors fractions", () => {
  expect(formatDuration(-5)).toBe("0 seconds")
  expect(formatDuration(5.9)).toBe("5 seconds")
})
