import { expect, test } from "bun:test"
import { orderProjectsByRecency, recentWorkdirs } from "./recent-projects"

test("recentWorkdirs keeps newest-first order and dedupes repeats", () => {
  expect(recentWorkdirs([
    { workdir: "/a" }, { workdir: "/b" }, { workdir: "/a" }, { workdir: "/c" },
  ])).toEqual(["/a", "/b", "/c"])
})

test("recentWorkdirs ignores blank or missing workdirs", () => {
  expect(recentWorkdirs([
    { workdir: "" }, { workdir: "  " }, {}, { workdir: "/x" },
  ])).toEqual(["/x"])
})

test("recentWorkdirs is empty when there are no sessions", () => {
  expect(recentWorkdirs([])).toEqual([])
})

test("orderProjectsByRecency lists recent projects first, then remaining known", () => {
  const recent = ["/proj/b", "/proj/a"]
  const known = [{ path: "/proj/a" }, { path: "/proj/b" }, { path: "/proj/z" }]
  expect(orderProjectsByRecency(recent, known)).toEqual([
    { path: "/proj/b" }, { path: "/proj/a" }, { path: "/proj/z" },
  ])
})

test("orderProjectsByRecency dedupes recent against known and itself", () => {
  expect(orderProjectsByRecency(["/a", "/a"], [{ path: "/a" }, { path: "/b" }])).toEqual([
    { path: "/a" }, { path: "/b" },
  ])
})

test("orderProjectsByRecency preserves known order when there is no recency", () => {
  expect(orderProjectsByRecency([], [{ path: "/a" }, { path: "/b" }])).toEqual([
    { path: "/a" }, { path: "/b" },
  ])
})
