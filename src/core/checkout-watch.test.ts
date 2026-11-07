import { expect, test } from "bun:test"
import { createCheckoutWatch } from "./checkout-watch"

// A broker run from a checkout keeps the code it started with, while what it starts later (keepers, sessions) loads the
// files on disk. When the checkout moves under it (a fast-forward), it says so, once in its log and on /host for the
// apps, and refuses nothing: sessions keep starting.

/** HEADs read one after another; the last one repeats. */
function heads(...values: Array<string | null>) {
  let i = 0
  return async () => values[Math.min(i++, values.length - 1)] ?? null
}

function watch(...values: Array<string | null>) {
  const logged: Array<{ event: string; fields: Record<string, string> }> = []
  const w = createCheckoutWatch({ readHead: heads(...values), log: (event, fields) => logged.push({ event, fields }) })
  return { w, logged }
}

const A = "aaaaaaaaa1111111111111111111111111111111"
const B = "bbbbbbbbb2222222222222222222222222222222"
const C = "ccccccccc3333333333333333333333333333333"

test("a checkout that has not moved: no warning", async () => {
  const { w, logged } = watch(A, A, A)
  await w.start()
  await w.check()
  expect(w.current()).toBeUndefined()
  expect(logged).toEqual([])
})

test("a checkout that moved: what the broker runs and where the checkout is, logged once", async () => {
  const { w, logged } = watch(A, B, B, B)
  await w.start()
  await w.check()
  await w.check()
  await w.check()
  expect(w.current()).toEqual({
    runs: "aaaaaaaaa",
    checkout: "bbbbbbbbb",
    message: "This broker runs aaaaaaaaa, the checkout is at bbbbbbbbb: restart it to run the new code.",
  })
  expect(logged).toEqual([{ event: "checkout_moved", fields: { runs: A, checkout: B } }])
})

test("moving again tells the new place and logs it; back where it started, the warning goes", async () => {
  const { w, logged } = watch(A, B, C, A)
  await w.start()
  await w.check()
  await w.check()
  expect(w.current()?.checkout).toBe("ccccccccc")
  expect(logged.map((l) => l.fields.checkout)).toEqual([B, C])
  await w.check()
  expect(w.current()).toBeUndefined()
})

test("a HEAD it cannot read: nothing is claimed at start, and later the last answer stands", async () => {
  const unknownAtStart = watch(null, B)
  await unknownAtStart.w.start()
  await unknownAtStart.w.check()
  expect(unknownAtStart.w.current()).toBeUndefined()

  const { w } = watch(A, B, null)
  await w.start()
  await w.check()
  await w.check()
  expect(w.current()?.checkout).toBe("bbbbbbbbb")
})

test("a reader that throws is a HEAD it cannot read", async () => {
  const w = createCheckoutWatch({ readHead: async () => { throw new Error("git is gone") }, log: () => {} })
  await w.start()
  await w.check()
  expect(w.current()).toBeUndefined()
})
