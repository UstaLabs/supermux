import { expect, test } from "bun:test"
import { drain } from "./run"

test("three runs in a row with no answer stop the queue: a usage limit can't fake hours of timeouts", async () => {
  const ran: number[] = []
  const end = await drain([1, 2, 3, 4, 5, 6], 1, async (job) => { ran.push(job); return false })
  expect(end).toBe("no-answer")
  expect(ran).toEqual([1, 2, 3])
})

test("an answer resets the count, so scattered failures don't stop a run", async () => {
  const ran: number[] = []
  const end = await drain([1, 2, 3, 4, 5, 6], 1, async (job) => { ran.push(job); return job % 3 === 0 })
  expect(end).toBe("done")
  expect(ran).toEqual([1, 2, 3, 4, 5, 6])
})

test("once stopped it stays stopped: a run still in flight that answers doesn't restart the queue", async () => {
  const ran: number[] = []
  const end = await drain([1, 2, 3, 4, 5, 6, 7, 8], 2, async (job) => {
    ran.push(job)
    if (job === 2) { await Bun.sleep(50); return true }
    return false
  })
  expect(end).toBe("no-answer")
  expect(ran).toEqual([1, 2, 3, 4])
})
