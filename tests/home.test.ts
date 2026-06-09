import { test, expect } from "bun:test"
import { homedir } from "os"
import { home } from "../src/shared/home"

test("returns $HOME when set", () => {
  const prev = process.env.HOME
  process.env.HOME = "/home/tester"
  try { expect(home()).toBe("/home/tester") } finally { process.env.HOME = prev }
})

test("falls back to os.homedir() when HOME is empty/unset (never returns '')", () => {
  const prev = process.env.HOME
  delete process.env.HOME
  try {
    const h = home()
    expect(h).toBe(homedir())
    expect(h.length).toBeGreaterThan(0)
  } finally { process.env.HOME = prev }
})

test("falls back to os.homedir() when HOME is the empty string (locks in || over ??)", () => {
  const prev = process.env.HOME
  process.env.HOME = ""
  try {
    const h = home()
    expect(h).toBe(homedir())
    expect(h.length).toBeGreaterThan(0)
  } finally { process.env.HOME = prev }
})
