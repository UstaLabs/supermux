import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { loadAccess, isAllowed, Access } from "../src/channels/telegram/access"

let dir: string
beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "agentmux-access-")) })
afterEach(() => rmSync(dir, { recursive: true, force: true }))

function writeAccess(obj: unknown): string {
  const p = join(dir, "access.json")
  writeFileSync(p, JSON.stringify(obj))
  return p
}

test("loadAccess returns deny-all defaults when file is missing", () => {
  const a = loadAccess(join(dir, "missing.json"))
  expect(a.dmPolicy).toBe("allowlist")
  expect(a.allowFrom).toEqual([])
  expect(a.groups).toEqual({})
})

test("loadAccess returns deny-all defaults when file is malformed JSON", () => {
  const p = join(dir, "access.json")
  writeFileSync(p, "{not json")
  const a = loadAccess(p)
  expect(a.allowFrom).toEqual([])
  expect(a.groups).toEqual({})
})

test("loadAccess reads the existing telegram plugin shape", () => {
  const p = writeAccess({
    dmPolicy: "pairing",
    allowFrom: ["12345", "67890"],
    groups: { "-100999": { requireMention: true, allowFrom: ["12345"] } },
    pending: {},  // present in migrated file; ignored here
  })
  const a = loadAccess(p)
  expect(a.dmPolicy).toBe("pairing")
  expect(a.allowFrom).toEqual(["12345", "67890"])
  expect(a.groups["-100999"]?.allowFrom).toEqual(["12345"])
})

test("isAllowed: DM from allowlisted sender", () => {
  const a: Access = { dmPolicy: "allowlist", allowFrom: ["111"], groups: {} }
  expect(isAllowed(a, { chatType: "private", chatId: "111", senderId: "111" })).toBe(true)
})

test("isAllowed: DM from stranger denied", () => {
  const a: Access = { dmPolicy: "allowlist", allowFrom: ["111"], groups: {} }
  expect(isAllowed(a, { chatType: "private", chatId: "222", senderId: "222" })).toBe(false)
})

test("isAllowed: dmPolicy=disabled blocks even allowlisted sender", () => {
  const a: Access = { dmPolicy: "disabled", allowFrom: ["111"], groups: {} }
  expect(isAllowed(a, { chatType: "private", chatId: "111", senderId: "111" })).toBe(false)
})

test("isAllowed: group requires group entry in `groups`", () => {
  const a: Access = { dmPolicy: "allowlist", allowFrom: [], groups: {} }
  expect(isAllowed(a, { chatType: "group", chatId: "-100abc", senderId: "111" })).toBe(false)
})

test("isAllowed: group with empty allowFrom permits any sender", () => {
  const a: Access = { dmPolicy: "allowlist", allowFrom: [], groups: { "-100abc": {} } }
  expect(isAllowed(a, { chatType: "group", chatId: "-100abc", senderId: "111" })).toBe(true)
})

test("isAllowed: group with allowFrom denies non-listed senders", () => {
  const a: Access = {
    dmPolicy: "allowlist",
    allowFrom: [],
    groups: { "-100abc": { allowFrom: ["222"] } },
  }
  expect(isAllowed(a, { chatType: "group", chatId: "-100abc", senderId: "111" })).toBe(false)
  expect(isAllowed(a, { chatType: "group", chatId: "-100abc", senderId: "222" })).toBe(true)
})

test("isAllowed: channel chats never delivered", () => {
  const a: Access = { dmPolicy: "allowlist", allowFrom: ["111"], groups: {} }
  expect(isAllowed(a, { chatType: "channel", chatId: "111", senderId: "111" })).toBe(false)
})
