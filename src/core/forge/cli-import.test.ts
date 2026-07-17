// src/core/forge/cli-import.test.ts
import { test, expect } from "bun:test"
import { detectForgeClis, importCliToken } from "./cli-import"

// Fake exec: returns canned stdout per (cmd,args) or throws to simulate "not found / not authed".
function runner(map: Record<string, string>) {
  return (cmd: string, args: string[]) => {
    const key = `${cmd} ${args.join(" ")}`
    if (key in map) return map[key]!
    throw new Error(`not found: ${key}`)
  }
}

test("detectForgeClis reports github/gitlab availability + login", async () => {
  const run = runner({ "gh auth status": "Logged in to github.com account ahmet", "gh auth token": "gho_x" })
  const d = await detectForgeClis(run)
  expect(d.github).toMatchObject({ available: true })
  expect(d.gitlab).toMatchObject({ available: false })
})

test("detectForgeClis supports non-blocking async status probes", async () => {
  const d = await detectForgeClis(async (cmd) => {
    if (cmd === "gh") return "Logged in to github.com account ahmet"
    throw new Error("not authenticated")
  })
  expect(d.github).toMatchObject({ available: true, login: "ahmet" })
  expect(d.gitlab).toEqual({ available: false })
})

test("importCliToken returns the gh token", () => {
  const run = runner({ "gh auth token": "gho_secret" })
  expect(importCliToken("github", run)).toBe("gho_secret")
})

test("importCliToken throws when the cli isn't authed", () => {
  const run = runner({})
  expect(() => importCliToken("github", run)).toThrow()
})
