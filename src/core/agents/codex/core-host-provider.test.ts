import { afterEach, expect, test } from "bun:test"
import {
  closeCodexCoreHost,
  getCodexCoreHost,
  resetCodexCoreHostProviderForTests,
  setCodexCoreHostFactoryForTests,
} from "./core-host-provider"
import type { CodexCoreHost } from "./core-host"

afterEach(() => {
  resetCodexCoreHostProviderForTests()
})

function fakeHost(state: { attempts: number; failUntil?: number }): CodexCoreHost {
  return {
    close: async () => {
      state.attempts++
      if (state.attempts <= (state.failUntil ?? 0)) throw new Error("close failed")
    },
  } as CodexCoreHost
}

test("lazy factory: getter creates once; shutdown forbids reopen", async () => {
  let created = 0
  setCodexCoreHostFactoryForTests(() => {
    created++
    return fakeHost({ attempts: 0 })
  })
  const a = getCodexCoreHost()
  const b = getCodexCoreHost()
  expect(a).toBe(b)
  expect(created).toBe(1)
  await closeCodexCoreHost()
  expect(() => getCodexCoreHost()).toThrow("Codex core host is closed")
})

test("failed close keeps the handle for retry", async () => {
  const state = { attempts: 0, failUntil: 1 }
  setCodexCoreHostFactoryForTests(() => fakeHost(state))
  getCodexCoreHost()
  await expect(closeCodexCoreHost()).rejects.toThrow("close failed")
  expect(state.attempts).toBe(1)
  await closeCodexCoreHost()
  expect(state.attempts).toBe(2)
})

test("factory seam refuses to replace a live owner", () => {
  setCodexCoreHostFactoryForTests(() => fakeHost({ attempts: 0 }))
  getCodexCoreHost()
  expect(() => setCodexCoreHostFactoryForTests(() => fakeHost({ attempts: 0 }))).toThrow("cannot replace a live Codex core host")
})
