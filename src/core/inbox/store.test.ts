import { afterEach, expect, test } from "bun:test"
import { appendFileSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs"
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

test("ids stay unique and keep increasing when the clock steps back", () => {
  const a = newMessageId(2000)
  const b = newMessageId(2001)
  const c = newMessageId(2000)
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

test("lines that parse as JSON but are not messages are skipped, like a torn line", () => {
  const s = store()
  const m1 = s.append("bbbb-2", { sender: A, text: "one" })
  const ok = { id: "m_ok", sender: A, at: "2026-10-28T14:00:00.000Z", text: "t" }
  const junk = [
    "null",
    "{}",
    "[]",
    "42",
    '"just a string"',
    JSON.stringify({ ...ok, id: 7 }),
    JSON.stringify({ ...ok, at: 7 }),
    JSON.stringify({ ...ok, text: null }),
    JSON.stringify({ ...ok, sender: null }),
    JSON.stringify({ ...ok, sender: "aaaa-1" }),
    JSON.stringify({ ...ok, sender: { name: "Fix login" } }),
    JSON.stringify({ ...ok, sender: { id: "aaaa-1" } }),
  ]
  appendFileSync(s.pathFor("bbbb-2"), `${junk.join("\n")}\n`)
  const m2 = s.append("bbbb-2", { sender: A, text: "two" })
  expect(s.read("bbbb-2")).toEqual([m1, m2])
  expect(s.after("bbbb-2", undefined)).toEqual([m1, m2])
  expect(s.after("bbbb-2", m1.id)).toEqual([m2])
  expect(s.after("bbbb-2", m2.id)).toEqual([])
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
  // a stray file whose name is not a session id is not listed (read() would refuse it)
  writeFileSync(join(s.dir, "notes.v2.jsonl"), "")
  expect(s.sessions()).toEqual(["bbbb-2"])
})

test("append stores only the sender's name and id", () => {
  const s = store()
  const noisy = { ...A, token: "do-not-persist" }
  const m = s.append("bbbb-2", { sender: noisy, text: "x" })
  expect(m.sender).toEqual(A)
  expect(s.read("bbbb-2")[0]?.sender).toEqual(A)
  expect(readFileSync(s.pathFor("bbbb-2"), "utf8")).not.toContain("do-not-persist")
})

test("the inbox folder is private to the user (0700) and so is each inbox file (0600)", () => {
  const s = store()
  s.append("bbbb-2", { sender: A, text: "x" })
  expect(statSync(s.dir).mode & 0o777).toBe(0o700)
  expect(statSync(s.pathFor("bbbb-2")).mode & 0o777).toBe(0o600)
})

test("pathFor refuses an id that could leave the inbox folder", () => {
  const s = store()
  expect(() => s.pathFor("../escape")).toThrow("invalid session id")
  expect(() => s.pathFor("a/b")).toThrow("invalid session id")
})
