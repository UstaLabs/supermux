import { expect, test } from "bun:test"
import { AGENT_KINDS } from "../../shared/agents"
import { shellReadsFor } from "./shell-reads"

const FILE = "/home/u/.mux/session_inboxes/7c1d2e9a-0000.jsonl"
const HOST = { cat: "/usr/bin/cat", head: "/usr/bin/head", tail: "/usr/bin/tail", wc: "/usr/bin/wc" }

test("Codex reads through the absolute host binaries its rules were written for, and through nothing else", () => {
  expect(shellReadsFor("codex", FILE, HOST)).toEqual(HOST)
  expect(shellReadsFor("codex", FILE, { cat: "/usr/bin/cat", tail: "/usr/bin/tail" })).toEqual({ cat: "/usr/bin/cat", tail: "/usr/bin/tail" })
  // No rules written (Windows, no trusted binary): no shell reads, and never a bare name.
  expect(shellReadsFor("codex", FILE)).toEqual({})
  expect(shellReadsFor("codex", FILE, {})).toEqual({})
  // The answer is a copy: changing it does not change what the host reported.
  const reads = shellReadsFor("codex", FILE, HOST)
  reads.cat = "/tmp/evil/cat"
  expect(HOST.cat).toBe("/usr/bin/cat")
})

test("Claude and Grok type the bare names; Claude only for a path a shell takes unquoted", () => {
  const bare = { cat: "cat", head: "head", tail: "tail", wc: "wc" }
  expect(shellReadsFor("claude", FILE)).toEqual(bare)
  expect(shellReadsFor("grok", FILE)).toEqual(bare)
  // Claude's exact rules match the text as typed: a path that needs quotes has none. Grok parses the command.
  expect(shellReadsFor("claude", "/Users/John Smith/.mux/session_inboxes/x.jsonl")).toEqual({})
  expect(shellReadsFor("grok", "/Users/John Smith/.mux/session_inboxes/x.jsonl")).toEqual(bare)
  // Host binaries are Codex's: they never change what the others type.
  expect(shellReadsFor("claude", FILE, HOST)).toEqual(bare)
  expect(shellReadsFor("grok", FILE, HOST)).toEqual(bare)
})

test("every other kind has no shell reads: its file-read tool needs no prompt", () => {
  const others = AGENT_KINDS.filter(kind => kind !== "codex" && kind !== "claude" && kind !== "grok")
  expect([...others].sort()).toEqual(["antigravity", "cursor", "opencode"])
  for (const kind of others) expect(shellReadsFor(kind, FILE, HOST)).toEqual({})
  expect(shellReadsFor(undefined, FILE, HOST)).toEqual({})
})

test("only a canonical absolute POSIX file path has shell reads (no root, no Windows path, no ..)", () => {
  for (const file of ["/", "", "relative/x.jsonl", "/a/", "/a//b.jsonl", "/a/../b.jsonl", "/a/./b.jsonl", "C:\\Users\\u\\.mux\\session_inboxes\\x.jsonl"]) {
    for (const kind of AGENT_KINDS) expect(shellReadsFor(kind, file, HOST)).toEqual({})
  }
})
