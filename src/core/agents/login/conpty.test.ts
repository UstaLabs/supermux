import { expect, test } from "bun:test"
import { conptyLoginProc } from "./conpty"

test("a conpty descriptor runs through the shared spawner with its env and cwd, and cleans up on exit", () => {
  const calls: unknown[] = []
  let exit: (code: number | null) => void = () => {}
  let cleaned = 0
  const written: string[] = []
  const proc = conptyLoginProc(
    { cmd: "claude", args: ["auth", "login"], cwd: "C:\\tmp", conpty: true },
    { Path: "C:\\bin" },
    () => { cleaned++ },
    () => { throw new Error("no spawn error expected") },
    input => {
      calls.push(input)
      return { onOutput: () => {}, onExit: cb => { exit = cb }, write: data => { written.push(data) }, kill: () => {} }
    },
  )
  expect(calls).toEqual([{ command: "claude", args: ["auth", "login"], env: { Path: "C:\\bin" }, cwd: "C:\\tmp" }])
  proc.write("code#state")
  expect(written).toEqual(["code#state"])
  exit(0)
  expect(cleaned).toBe(1)
})

test("a spawn failure (no Bun, missing CLI) is reported as a failed login and still cleans up", async () => {
  let cleaned = 0
  const errors: string[] = []
  const proc = conptyLoginProc(
    { cmd: "agy", args: [], conpty: true }, {}, () => { cleaned++ }, message => errors.push(message),
    () => { throw new Error("no pty here") },
  )
  const out: string[] = []
  const code = await new Promise<number | null>(resolve => { proc.onStdout(c => out.push(c)); proc.onExit(resolve) })
  expect(code).toBe(127)
  expect(out.join("")).toContain("agy is not available: no pty here")
  expect(errors).toEqual(["no pty here"])
  expect(cleaned).toBe(1)
})
