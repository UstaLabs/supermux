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

// An empty variable counts as unset (locks in || over ??). Not compared with os.homedir(): on Windows an empty
// %USERPROFILE% makes the runtime's own answer throw, and home() falls back to %HOMEDRIVE%%HOMEPATH% there.
test("an empty variable counts as unset, and still gives a home (never '')", () => {
  let unset = ""
  withHome(undefined, () => { unset = home() })
  withHome("", () => {
    const h = home()
    expect(h.length).toBeGreaterThan(0)
    if (process.platform !== "win32") expect(h).toBe(unset)
  })
})
