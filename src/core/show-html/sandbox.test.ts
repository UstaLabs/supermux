import { expect, test } from "bun:test"
import { UNSANDBOXED_NOTE, launchSandboxFirst } from "./check"

// Tally, 2026-11-10: Chrome's own sandbox first; where the host refuses it, run without and say so (notes and log).
test("the sandbox is tried first and kept when it starts", async () => {
  const tried: boolean[] = []
  const r = await launchSandboxFirst(async (sandbox) => { tried.push(sandbox); return "chrome" }, () => {})
  expect(tried).toEqual([true])
  expect(r).toEqual({ chrome: "chrome", sandboxed: true })
})

test("a host that refuses the sandbox gets an unsandboxed browser, logged", async () => {
  const tried: boolean[] = []
  const logged: string[] = []
  const r = await launchSandboxFirst(async (sandbox) => {
    tried.push(sandbox)
    if (sandbox) throw new Error("the browser exited before it was ready")
    return "chrome"
  }, (m) => logged.push(m))
  expect(tried).toEqual([true, false])
  expect(r).toEqual({ chrome: "chrome", sandboxed: false })
  expect(logged.join(" ")).toContain("without its sandbox")
})

test("when neither starts, the error is the unsandboxed one", async () => {
  await expect(launchSandboxFirst(async (s) => { throw new Error(s ? "a" : "b") }, () => {})).rejects.toThrow("b")
})

test("the note says what it means", () => {
  expect(UNSANDBOXED_NOTE).toContain("without Chrome's own sandbox")
})
