import { expect, test } from "bun:test"
import { moveId, moveIndex } from "./useSectionReorder"

test("moveId reorders within a section", () => {
  expect(moveId(["a", "b", "c"], "c", "a")).toEqual(["c", "a", "b"])
  expect(moveId(["a", "b", "c"], "a", "c")).toEqual(["b", "c", "a"])
  expect(moveId(["a", "b", "c"], "b", "b")).toBeNull()
  expect(moveId(["a", "b"], "x", "a")).toBeNull()
})

test("moveIndex live-shifts an item to a new slot", () => {
  expect(moveIndex(["a", "b", "c", "d"], 0, 2)).toEqual(["b", "c", "a", "d"])
  expect(moveIndex(["a", "b", "c", "d"], 3, 0)).toEqual(["d", "a", "b", "c"])
  expect(moveIndex(["a", "b", "c"], 1, 1)).toBeNull()
})
