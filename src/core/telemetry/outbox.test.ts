import { describe, expect, test } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { TelemetryOutbox } from "./outbox"

function fresh(opts?: { maxRows?: number; maxAgeMs?: number }) {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  let now = 1_000_000
  const box = new TelemetryOutbox(db, { now: () => now, ...opts })
  return { box, advance: (ms: number) => { now += ms } }
}
const ev = (uuid: string) => ({ uuid, event: "broker.started" }) as any

describe("TelemetryOutbox", () => {
  test("add then take returns events oldest first", () => {
    const { box, advance } = fresh()
    box.add(ev("a")); advance(1); box.add(ev("b"))
    expect(box.take(10).map((e) => e.uuid)).toEqual(["a", "b"])
    expect(box.size()).toBe(2)
  })
  test("sent events leave the outbox and are stamped in recent", () => {
    const { box } = fresh()
    box.add(ev("a"))
    box.markSent(["a"])
    expect(box.size()).toBe(0)
    expect(box.recent()[0]).toMatchObject({ uuid: "a", sent: true })
  })
  test("a batch that fails 5 times is dropped", () => {
    const { box } = fresh()
    box.add(ev("a"))
    for (let i = 0; i < 4; i++) box.markFailed(["a"], 5)
    expect(box.size()).toBe(1)
    box.markFailed(["a"], 5)
    expect(box.size()).toBe(0)
  })
  test("row cap drops the oldest", () => {
    const { box, advance } = fresh({ maxRows: 2 })
    box.add(ev("a")); advance(1); box.add(ev("b")); advance(1); box.add(ev("c"))
    expect(box.take(10).map((e) => e.uuid)).toEqual(["b", "c"])
  })
  test("events older than maxAge are dropped", () => {
    const { box, advance } = fresh({ maxAgeMs: 1000 })
    box.add(ev("a")); advance(2000); box.add(ev("b"))
    expect(box.take(10).map((e) => e.uuid)).toEqual(["b"])
  })
  test("clear empties the outbox and recent", () => {
    const { box } = fresh()
    box.add(ev("a"))
    box.clear()
    expect(box.size()).toBe(0)
    expect(box.recent()).toEqual([])
  })
  test("recent keeps the last 50", () => {
    const { box, advance } = fresh()
    for (let i = 0; i < 60; i++) { box.add(ev(`e${i}`)); advance(1) }
    const r = box.recent()
    expect(r.length).toBe(50)
    expect(r[0]!.uuid).toBe("e59")
  })
})
