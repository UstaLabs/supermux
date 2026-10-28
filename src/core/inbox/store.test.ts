import { afterEach, expect, test } from "bun:test"
import { appendFileSync, mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { InboxStore, newMessageId } from "./store"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })
function store() {
  const dir = mkdtempSync(join(tmpdir(), "inbox-store-"))
  dirs.push(dir)
  return new InboxStore(join(dir, "inbox"))
}
const A = { name: "Fix login", id: "aaaa-1" }

test("append writes one JSON line per message; read returns them in order", () => {
  const s = store()
  const m1 = s.append("bbbb-2", { sender: A, text: "one" }, new Date("2026-10-28T14:00:00.000Z"))
  const m2 = s.append("bbbb-2", { sender: A, text: "two" }, new Date("2026-10-28T14:00:01.000Z"))
  const lines = readFileSync(s.pathFor("bbbb-2"), "utf8").trimEnd().split("\n")
  expect(lines.map((l) => JSON.parse(l))).toEqual([
    { id: m1.id, sender: A, at: "2026-10-28T14:00:00.000Z", text: "one" },
    { id: m2.id, sender: A, at: "2026-10-28T14:00:01.000Z", text: "two" },
  ])
  expect(s.read("bbbb-2")).toEqual([m1, m2])
})

test("ids are unique and sortable within the same millisecond", () => {
  const a = newMessageId(1_700_000_000_000)
  const b = newMessageId(1_700_000_000_000)
  const c = newMessageId(1_700_000_000_001)
  expect(new Set([a, b, c]).size).toBe(3)
  expect([c, b, a].sort()).toEqual([a, b, c])
})

test("a torn last line is skipped, and the next append starts on a new line", () => {
  const s = store()
  s.append("bbbb-2", { sender: A, text: "kept" })
  appendFileSync(s.pathFor("bbbb-2"), '{"id":"m_torn","sen')
  const next = s.append("bbbb-2", { sender: A, text: "after" })
  expect(s.read("bbbb-2").map((m) => m.text)).toEqual(["kept", "after"])
  expect(s.read("bbbb-2").at(-1)?.id).toBe(next.id)
})

test("after() returns the messages following an id, and all of them for an unknown id", () => {
  const s = store()
  const m1 = s.append("bbbb-2", { sender: A, text: "one" })
  const m2 = s.append("bbbb-2", { sender: A, text: "two" })
  expect(s.after("bbbb-2", m1.id)).toEqual([m2])
  expect(s.after("bbbb-2", m2.id)).toEqual([])
  expect(s.after("bbbb-2", undefined)).toEqual([m1, m2])
  expect(s.after("bbbb-2", "m_gone")).toEqual([m1, m2])
})

test("sessions() lists the ids that have an inbox file; read() of a missing inbox is empty", () => {
  const s = store()
  expect(s.sessions()).toEqual([])
  expect(s.read("cccc-3")).toEqual([])
  s.append("bbbb-2", { sender: A, text: "x" })
  expect(s.sessions()).toEqual(["bbbb-2"])
})

test("pathFor refuses an id that could leave the inbox folder", () => {
  const s = store()
  expect(() => s.pathFor("../escape")).toThrow("invalid session id")
  expect(() => s.pathFor("a/b")).toThrow("invalid session id")
})
