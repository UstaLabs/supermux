import { expect, test } from "bun:test"
import { sweepAdapterLiveness } from "./adapter-liveness"

test("reports only edges: a row whose adapter liveness differs from its connected flag", () => {
  const rows = [
    { id: "a", connected: false },  // adapter came up → true
    { id: "b", connected: true },   // adapter died → false
    { id: "c", connected: true },   // unchanged
    { id: "d", connected: false },  // suspended, no adapter: unchanged
  ]
  const alive = new Set(["a", "c"])
  const applied: Array<[string, boolean]> = []
  sweepAdapterLiveness(rows, (id) => alive.has(id), (id, connected) => applied.push([id, connected]))
  expect(applied).toEqual([["a", true], ["b", false]])
})
