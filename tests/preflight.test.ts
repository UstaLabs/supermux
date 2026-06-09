import { test, expect } from "bun:test"
import { checkPreflight } from "../src/shared/preflight"

const present = (...bins: string[]) => (b: string) => bins.includes(b)

test("missing tmux is fatal", () => {
  const r = checkPreflight(present("claude"))
  expect(r.fatal.some((m) => m.includes("tmux"))).toBe(true)
})

test("zero agent CLIs is fatal", () => {
  const r = checkPreflight(present("tmux"))
  expect(r.fatal.some((m) => m.toLowerCase().includes("agent cli"))).toBe(true)
})

test("tmux + one agent CLI: no fatals, warns about the missing optional ones", () => {
  const r = checkPreflight(present("tmux", "claude"))
  expect(r.fatal).toHaveLength(0)
  expect(r.warnings.some((m) => m.includes("codex"))).toBe(true)
  expect(r.warnings.some((m) => m.includes("cursor-agent"))).toBe(true)
})

test("all present: no fatals, no warnings", () => {
  const r = checkPreflight(present("tmux", "claude", "codex", "cursor-agent"))
  expect(r.fatal).toHaveLength(0)
  expect(r.warnings).toHaveLength(0)
})
