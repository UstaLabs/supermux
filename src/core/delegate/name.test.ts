import { expect, test } from "bun:test"
import { nameFromTask } from "./name"

test("a short task is its own name; a long one is cut at a word; only the first line counts", () => {
  expect(nameFromTask("Review the auth changes")).toBe("Review the auth changes")
  expect(nameFromTask("  Review   the auth\nthen fix the tests")).toBe("Review the auth")
  expect(nameFromTask("Compare the three caching strategies we discussed yesterday and pick one")).toBe("Compare the three caching strategies we")
  expect(nameFromTask("\n\n")).toBe("Subagent")
})
