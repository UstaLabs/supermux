import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useDrafts } from "./drafts"

beforeEach(() => setActivePinia(createPinia()))

test("get returns empty string for a session with no draft", () => {
  expect(useDrafts().get("s1")).toBe("")
})

test("applyRemote stores a draft pushed from another device", () => {
  const drafts = useDrafts()
  drafts.applyRemote("s1", "hello from phone")
  expect(drafts.get("s1")).toBe("hello from phone")
})

test("applyRemote with empty string clears the draft", () => {
  const drafts = useDrafts()
  drafts.applyRemote("s1", "something")
  drafts.applyRemote("s1", "")
  expect(drafts.get("s1")).toBe("")
})

test("seed hydrates drafts from the snapshot", () => {
  const drafts = useDrafts()
  drafts.seed({ s1: "draft one", s2: "draft two" })
  expect(drafts.get("s1")).toBe("draft one")
  expect(drafts.get("s2")).toBe("draft two")
})

test("clear empties a session's draft locally", () => {
  const drafts = useDrafts()
  drafts.applyRemote("s1", "typed")
  drafts.clear("s1")
  expect(drafts.get("s1")).toBe("")
})

test("drafts are scoped per session", () => {
  const drafts = useDrafts()
  drafts.applyRemote("s1", "a")
  drafts.applyRemote("s2", "b")
  expect(drafts.get("s1")).toBe("a")
  expect(drafts.get("s2")).toBe("b")
})
