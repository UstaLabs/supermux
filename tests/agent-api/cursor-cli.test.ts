import { expect, test } from "bun:test"
import { cursorCliAdapter } from "../../src/core/agent-api/adapters/cursor-cli"

test("name is cursor-cli", () => {
  const a = cursorCliAdapter()
  expect(a.name).toBe("cursor-cli")
})

test("isAvailable() is true (cursor-agent assumed present under the broker)", () => {
  const a = cursorCliAdapter()
  expect(a.isAvailable()).toBe(true)
})

test("complete() builds the cursor-agent argv and returns trimmed stdout", async () => {
  let seen: { argv?: string[]; cwd?: string; timeoutMs?: number } = {}
  const run = async (argv: string[], cwd: string, timeoutMs: number) => {
    seen = { argv, cwd, timeoutMs }
    return { code: 0, out: "  hello world  \n" }
  }
  const a = cursorCliAdapter({ run })
  const out = await a.complete("Correct: helo wrld")

  expect(out).toBe("hello world")
  expect(seen.argv).toEqual([
    "cursor-agent",
    "-p",
    "Correct: helo wrld",
    "--output-format",
    "text",
    "--model",
    "composer-2.5-fast",
    "--force",
  ])
})

test("complete() honors opts.model", async () => {
  let seen: { argv?: string[] } = {}
  const run = async (argv: string[]) => {
    seen = { argv }
    return { code: 0, out: "ok" }
  }
  const a = cursorCliAdapter({ run })
  await a.complete("x", { model: "composer-1" })
  expect(seen.argv).toContain("composer-1")
  expect(seen.argv).not.toContain("composer-2.5-fast")
})

test("complete() passes the timeout through", async () => {
  let seen = 0
  const run = async (_argv: string[], _cwd: string, timeoutMs: number) => {
    seen = timeoutMs
    return { code: 0, out: "ok" }
  }
  const a = cursorCliAdapter({ run })
  await a.complete("x", { timeoutMs: 1234 })
  expect(seen).toBe(1234)
})

test("complete() throws on non-zero exit code", async () => {
  const run = async () => ({ code: 1, out: "partial" })
  const a = cursorCliAdapter({ run })
  await expect(a.complete("x")).rejects.toThrow()
})

test("complete() throws on empty stdout", async () => {
  const run = async () => ({ code: 0, out: "   \n" })
  const a = cursorCliAdapter({ run })
  await expect(a.complete("x")).rejects.toThrow()
})
