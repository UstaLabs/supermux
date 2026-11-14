// #144: Antigravity's window from its own conversation DB (gen_metadata protobuf 1.9.10.4), read-only, defensively.
import { afterEach, expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { mkdirSync, mkdtempSync, rmSync } from "node:fs"
import { join } from "node:path"
import { tmpdir } from "node:os"
import { protoNumberAt, readConversationWindow } from "../src/antigravity/window.js"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

const varint = (n: number) => { const out: number[] = []; while (n >= 0x80) { out.push((n & 0x7f) | 0x80); n = Math.floor(n / 128) } out.push(n); return out }
const msg = (field: number, inner: number[]) => [...varint((field << 3) | 2), ...varint(inner.length), ...inner]
const num = (field: number, n: number) => [...varint(field << 3), ...varint(n)]
/** The shape agy 1.5.9 writes: 1 { 9 { 10 { 1: used, 4: max } } } among other fields. */
const meta = (used: number, max: number) => new Uint8Array([...num(2, 7), ...msg(1, [...num(3, 1), ...msg(9, msg(10, [...num(1, used), ...num(4, max)]))])])

test("protoNumberAt finds 1.9.10.4 and nothing at a wrong path or in broken bytes", () => {
  const bytes = meta(23748, 256000)
  expect(protoNumberAt(bytes, [1, 9, 10, 4])).toBe(256000)
  expect(protoNumberAt(bytes, [1, 9, 10, 1])).toBe(23748)
  expect(protoNumberAt(bytes, [1, 9, 11, 4])).toBeUndefined()
  expect(protoNumberAt(new Uint8Array([0xff, 0xff]), [1])).toBeUndefined()
})

test("readConversationWindow reads the newest generation's window; anything missing is unknown", async () => {
  const cli = mkdtempSync(join(tmpdir(), "agy-win-"))
  dirs.push(cli)
  mkdirSync(join(cli, "conversations"))
  const db = new Database(join(cli, "conversations", "conv-1.db"))
  db.run("CREATE TABLE gen_metadata (idx integer, data blob, size integer NOT NULL DEFAULT 0, PRIMARY KEY (idx))")
  db.query("INSERT INTO gen_metadata (idx, data) VALUES (?, ?)").run(1, meta(9000, 256000))
  db.query("INSERT INTO gen_metadata (idx, data) VALUES (?, ?)").run(2, meta(23748, 840000))
  db.close()
  expect(await readConversationWindow(cli, "conv-1")).toBe(840000)
  expect(await readConversationWindow(cli, "missing")).toBeUndefined()
  expect(await readConversationWindow(cli, "../conv-1")).toBeUndefined()
  const empty = new Database(join(cli, "conversations", "conv-2.db"))
  empty.run("CREATE TABLE gen_metadata (idx integer, data blob, size integer NOT NULL DEFAULT 0, PRIMARY KEY (idx))")
  empty.close()
  expect(await readConversationWindow(cli, "conv-2")).toBeUndefined()
})
