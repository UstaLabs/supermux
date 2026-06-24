import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useGitStatus } from "./gitStatus"

beforeEach(() => setActivePinia(createPinia()))

const sample = { mode: "base" as const, compareRef: "main", ahead: 2, behind: 1, dirty: 3, computedAt: 1 }

test("get is undefined for an unknown session", () => {
  expect(useGitStatus().get("s1")).toBeUndefined()
})
test("set then get returns the status", () => {
  const s = useGitStatus(); s.set("s1", sample)
  expect(s.get("s1")).toEqual(sample)
})
test("set null clears a session", () => {
  const s = useGitStatus(); s.set("s1", sample); s.set("s1", null)
  expect(s.get("s1")).toBeUndefined()
})
test("fromSnapshot ignores empty and stores present", () => {
  const s = useGitStatus(); s.fromSnapshot("s1", undefined); s.fromSnapshot("s2", sample)
  expect(s.get("s1")).toBeUndefined()
  expect(s.get("s2")).toEqual(sample)
})
