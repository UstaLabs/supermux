import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useActivity, type ActivityEntry } from "./activity"

function ev(title: string, ts = "2026-05-29T00:00:00.000Z"): ActivityEntry {
  return { ts, kind: "tool", tool: "Bash", title }
}

beforeEach(() => setActivePinia(createPinia()))

test("append accumulates per session", () => {
  const a = useActivity()
  a.append("s1", ev("a")); a.append("s1", ev("b"))
  expect(a.bySession["s1"].map((e) => e.title)).toEqual(["a", "b"])
})

test("replace overwrites a session's list", () => {
  const a = useActivity()
  a.append("s1", ev("a"))
  a.replace("s1", [ev("x"), ev("y")])
  expect(a.bySession["s1"].map((e) => e.title)).toEqual(["x", "y"])
})
