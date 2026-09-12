import { afterEach, expect, test } from "bun:test"
import {
  closeGrokCoreHost,
  getGrokCoreHost,
  resetGrokCoreHostProviderForTests,
  setGrokCoreHostFactoryForTests,
} from "./core-host-provider"
import type { GrokCoreHost } from "./core-host"

afterEach(() => {
  resetGrokCoreHostProviderForTests()
})

function fakeHost(state: { attempts: number; failUntil?: number }): GrokCoreHost {
  return {
    close: async () => {
      state.attempts++
      if (state.attempts <= (state.failUntil ?? 0)) throw new Error("close failed")
    },
  } as GrokCoreHost
}

test("lazy factory: getter creates once; shutdown forbids reopen", async () => {
  let created = 0
  setGrokCoreHostFactoryForTests(() => {
    created++
    return fakeHost({ attempts: 0 })
  })
  const a = getGrokCoreHost()
  const b = getGrokCoreHost()
  expect(a).toBe(b)
  expect(created).toBe(1)
  await closeGrokCoreHost()
  expect(() => getGrokCoreHost()).toThrow("Grok core host is closed")
})

test("failed close keeps the handle for retry", async () => {
  const state = { attempts: 0, failUntil: 1 }
  setGrokCoreHostFactoryForTests(() => fakeHost(state))
  getGrokCoreHost()
  await expect(closeGrokCoreHost()).rejects.toThrow("close failed")
  expect(state.attempts).toBe(1)
  await closeGrokCoreHost()
  expect(state.attempts).toBe(2)
})

test("factory seam refuses to replace a live owner", () => {
  setGrokCoreHostFactoryForTests(() => fakeHost({ attempts: 0 }))
  getGrokCoreHost()
  expect(() => setGrokCoreHostFactoryForTests(() => fakeHost({ attempts: 0 }))).toThrow("cannot replace a live Grok core host")
})
