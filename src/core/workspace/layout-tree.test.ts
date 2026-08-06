import { test, expect } from "bun:test"
import {
  type LayoutNode,
  collectViewIds,
  validateLayout,
  normalizeLayout,
  singleViewLayout,
  addViewToGroup,
  removeViewFromLayout,
} from "./layout-tree"

const group = (id: string, viewIds: string[], activeViewId?: string): LayoutNode =>
  ({ type: "group", id, viewIds, activeViewId: activeViewId ?? viewIds[0] })

test("singleViewLayout makes a one-group layout with the view active", () => {
  const l = singleViewLayout("g1", "v1")
  expect(l).toEqual({ type: "group", id: "g1", viewIds: ["v1"], activeViewId: "v1" })
  expect(validateLayout(l)).toBeNull()
})

test("collectViewIds walks the whole tree in document order", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.5, 0.5],
    children: [group("g1", ["v1"]), group("g2", ["v2", "v3"])],
  }
  expect(collectViewIds(l)).toEqual(["v1", "v2", "v3"])
})

test("validateLayout rejects a duplicate view id", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.5, 0.5],
    children: [group("g1", ["v1"]), group("g2", ["v1"])],
  }
  expect(validateLayout(l)).toBe("duplicate view id: v1")
})

test("validateLayout rejects an empty group", () => {
  expect(validateLayout({ type: "group", id: "g1", viewIds: [] })).toBe("empty group: g1")
})

test("validateLayout rejects an activeViewId that is not in the group", () => {
  expect(validateLayout(group("g1", ["v1"], "v9"))).toBe("activeViewId not in group g1: v9")
})

test("validateLayout rejects a split whose sizes length differs from its children length", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [1],
    children: [group("g1", ["v1"]), group("g2", ["v2"])],
  }
  expect(validateLayout(l)).toBe("split sizes length 1 does not match children length 2")
})

test("validateLayout rejects a split with fewer than two children", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [1], children: [group("g1", ["v1"])],
  }
  expect(validateLayout(l)).toBe("split needs at least 2 children, got 1")
})

test("validateLayout rejects sizes that do not add up to 1", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.5, 0.2],
    children: [group("g1", ["v1"]), group("g2", ["v2"])],
  }
  expect(validateLayout(l)).toBe("split sizes must add up to 1, got 0.7")
})

test("validateLayout rejects a non-positive size", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0, 1],
    children: [group("g1", ["v1"]), group("g2", ["v2"])],
  }
  expect(validateLayout(l)).toBe("split sizes must all be greater than 0")
})

test("validateLayout accepts a valid nested tree", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.5, 0.5],
    children: [
      group("g1", ["v1"]),
      { type: "split", direction: "column", sizes: [0.6, 0.4], children: [group("g2", ["v2", "v3"], "v2"), group("g3", ["v4"])] },
    ],
  }
  expect(validateLayout(l)).toBeNull()
})

test("normalizeLayout drops an empty group and collapses the single-child split", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.5, 0.5],
    children: [group("g1", ["v1"]), { type: "group", id: "g2", viewIds: [] }],
  }
  expect(normalizeLayout(l)).toEqual(group("g1", ["v1"]))
})

test("normalizeLayout returns null when every group is empty", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.5, 0.5],
    children: [{ type: "group", id: "g1", viewIds: [] }, { type: "group", id: "g2", viewIds: [] }],
  }
  expect(normalizeLayout(l)).toBeNull()
})

test("normalizeLayout repairs sizes after a child is dropped", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.2, 0.3, 0.5],
    children: [group("g1", ["v1"]), { type: "group", id: "gx", viewIds: [] }, group("g3", ["v3"])],
  }
  expect(normalizeLayout(l)).toEqual({
    type: "split", direction: "row", sizes: [0.5, 0.5],
    children: [group("g1", ["v1"]), group("g3", ["v3"])],
  })
})

test("normalizeLayout keeps the sizes when no child was dropped", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.2, 0.8],
    children: [group("g1", ["v1"]), group("g2", ["v2"])],
  }
  expect(normalizeLayout(l)).toEqual(l)
})

test("normalizeLayout repairs an activeViewId that left the group", () => {
  const l = { type: "group", id: "g1", viewIds: ["v1", "v2"], activeViewId: "v9" } as LayoutNode
  expect(normalizeLayout(l)).toEqual(group("g1", ["v1", "v2"], "v1"))
})

test("addViewToGroup appends the view and makes it active", () => {
  const l = singleViewLayout("g1", "v1")
  expect(addViewToGroup(l, "g1", "v2")).toEqual(group("g1", ["v1", "v2"], "v2"))
})

test("addViewToGroup leaves the tree alone when the group id is unknown", () => {
  const l = singleViewLayout("g1", "v1")
  expect(addViewToGroup(l, "nope", "v2")).toEqual(l)
})

test("removeViewFromLayout removes the view and normalizes", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.5, 0.5],
    children: [group("g1", ["v1"]), group("g2", ["v2"])],
  }
  expect(removeViewFromLayout(l, "v2")).toEqual(group("g1", ["v1"]))
})

test("removeViewFromLayout returns null when the last view goes", () => {
  expect(removeViewFromLayout(singleViewLayout("g1", "v1"), "v1")).toBeNull()
})

test("removeViewFromLayout picks a new active view when the active one goes", () => {
  const l = group("g1", ["v1", "v2"], "v1")
  expect(removeViewFromLayout(l, "v1")).toEqual(group("g1", ["v2"], "v2"))
})
