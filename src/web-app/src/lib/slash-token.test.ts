import { test, expect } from "bun:test"
import { activeSlashToken } from "./slash-token"

test("activeSlashToken at start of text", () => {
  expect(activeSlashToken("/kill", 5)).toEqual({ start: 0, query: "kill" })
})

test("activeSlashToken after whitespace", () => {
  expect(activeSlashToken("please /kill", 12)).toEqual({ start: 7, query: "kill" })
})

test("activeSlashToken ignores path slashes", () => {
  expect(activeSlashToken("cd ~/projects/foo", 17)).toBeNull()
})

test("activeSlashToken partial query", () => {
  expect(activeSlashToken("run /mo", 7)).toEqual({ start: 4, query: "mo" })
})

test("activeSlashToken ignores closed token before cursor", () => {
  expect(activeSlashToken("/kill done", 10)).toBeNull()
})
