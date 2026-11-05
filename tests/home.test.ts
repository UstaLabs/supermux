import { test, expect } from "bun:test"
import { homedir } from "os"
import { home } from "../src/shared/home"

// The OS's own home variable (Q2): %USERPROFILE% on Windows, $HOME elsewhere.
const VAR = process.platform === "win32" ? "USERPROFILE" : "HOME"

function withHome(value: string | undefined, body: () => void): void {
  const prev = process.env[VAR]
  if (value === undefined) delete process.env[VAR]
  else process.env[VAR] = value
  try { body() } finally {
    if (prev === undefined) delete process.env[VAR]
    else process.env[VAR] = prev
  }
}

test("returns the OS's home variable when set ($HOME, or %USERPROFILE% on Windows)", () => {
  withHome("/home/tester", () => expect(home()).toBe("/home/tester"))
})

test("falls back to os.homedir() when the variable is unset (never returns '')", () => {
  withHome(undefined, () => {
    const h = home()
    expect(h).toBe(homedir())
    expect(h.length).toBeGreaterThan(0)
  })
})

test("falls back to os.homedir() when the variable is the empty string (locks in || over ??)", () => {
  withHome("", () => {
    const h = home()
    expect(h).toBe(homedir())
    expect(h.length).toBeGreaterThan(0)
  })
})
