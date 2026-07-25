import { expect, test } from "bun:test"
import { moveId } from "./useSectionReorder"

test("moveId reorders within a section", () => {
  expect(moveId(["a", "b", "c"], "c", "a")).toEqual(["c", "a", "b"])
  expect(moveId(["a", "b", "c"], "a", "c")).toEqual(["b", "c", "a"])
  expect(moveId(["a", "b", "c"], "b", "b")).toBeNull()
  expect(moveId(["a", "b"], "x", "a")).toBeNull()
})
