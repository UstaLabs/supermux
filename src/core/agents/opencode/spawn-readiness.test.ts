import { expect, test } from "bun:test"
import { EventEmitter } from "events"
import type { ChildProcess } from "child_process"
import { awaitServerReady } from "./spawn-readiness"

function fakeChild(): ChildProcess {
  const c = new EventEmitter() as unknown as ChildProcess
  ;(c as any).pid = 4321
  ;(c as any).kill = () => {}
  return c
}

// A readiness probe that never succeeds — so the ONLY thing that can settle the
// race is the child dying. Proves we don't wait out the readiness timeout.
const never = (): Promise<void> => new Promise<void>(() => {})

test("rejects fast with an install hint when the child errors with ENOENT", async () => {
  const child = fakeChild()
  const p = awaitServerReady(child, never())
  queueMicrotask(() =>
    child.emit("error", Object.assign(new Error("spawn opencode ENOENT"), { code: "ENOENT" })),
  )
  let msg = ""
  try {
    await p
  } catch (e) {
    msg = (e as Error).message
  }
  expect(msg).toMatch(/opencode failed to start/i)
  expect(msg).toMatch(/installed|PATH/i)
})

test("rejects when the child exits before becoming ready", async () => {
  const child = fakeChild()
  const p = awaitServerReady(child, never())
  queueMicrotask(() => child.emit("exit", 1, null))
  await expect(p).rejects.toThrow(/exited before becoming ready/i)
})

test("resolves when ready wins and removes its child listeners", async () => {
  const child = fakeChild()
  let resolveReady!: () => void
  const ready = new Promise<void>((r) => {
    resolveReady = r
  })
  const p = awaitServerReady(child, ready)
  resolveReady()
  await expect(p).resolves.toBeUndefined()
  // No dangling listeners → a later child death can't surface as an unhandled
  // rejection on a promise nobody awaits.
  expect(child.listenerCount("error")).toBe(0)
  expect(child.listenerCount("exit")).toBe(0)
})
