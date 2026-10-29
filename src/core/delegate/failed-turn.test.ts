import { expect, test } from "bun:test"
import { failedTurn } from "./failed-turn"

function setup(over: { alive?: boolean; continues?: Promise<boolean> } = {}) {
  const reports: string[] = []
  const waits: number[] = []
  return {
    reports,
    waits,
    deps: {
      isAlive: () => over.alive ?? true,
      continues: over.continues ?? Promise.resolve(false),
      report: (error: string) => { reports.push(error) },
      wait: async (ms: number) => { waits.push(ms) },
    },
  }
}

test("a failed turn is reported after the grace period", async () => {
  const t = setup()
  await failedTurn("boom", t.deps, 1000)
  expect(t.waits).toEqual([1000])
  expect(t.reports).toEqual(["boom"])
})

test("an agent that died with its turn is left to the crash notice", async () => {
  const t = setup({ alive: false })
  await failedTurn("boom", t.deps)
  expect(t.reports).toEqual([])
})

test("an account switch that moves the session on means no report; a failed check still reports", async () => {
  const moved = setup({ continues: Promise.resolve(true) })
  await failedTurn("limit", moved.deps)
  expect(moved.reports).toEqual([])
  const broken = setup({ continues: Promise.reject(new Error("core closed")) })
  await failedTurn("limit", broken.deps)
  expect(broken.reports).toEqual(["limit"])
})
