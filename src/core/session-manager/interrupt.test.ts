import { test, expect } from "bun:test"
import { runInterrupt } from "./interrupt"

test("no adapter: clears state and reports not-interruptible", async () => {
  let cleared = 0
  const r = await runInterrupt({ adapter: undefined, onClear: () => { cleared++ } })
  expect(r).toEqual({ ok: false, reason: "session not interruptible" })
  expect(cleared).toBe(1)
})

test("adapter throws: clears state and reports the reason", async () => {
  let cleared = 0
  const r = await runInterrupt({
    adapter: { interrupt: async () => { throw new Error("tmux window gone") } },
    onClear: () => { cleared++ },
  })
  expect(r).toEqual({ ok: false, reason: "tmux window gone" })
  expect(cleared).toBe(1)
})

test("adapter succeeds: clears state and reports ok", async () => {
  let cleared = 0
  const r = await runInterrupt({
    adapter: { interrupt: async () => {} },
    onClear: () => { cleared++ },
  })
  expect(r).toEqual({ ok: true })
  expect(cleared).toBe(1)
})
