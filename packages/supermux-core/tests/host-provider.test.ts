import { afterEach, expect, test } from "bun:test"
import { createHostProvider } from "../src/host/provider.js"
import type { Host } from "../src/host/index.js"

function fakeHost(state: { attempts: number; failUntil?: number }): Host {
  return {
    close: async () => {
      state.attempts++
      if (state.attempts <= (state.failUntil ?? 0)) throw new Error("close failed")
    },
  } as Host
}

const providers: ReturnType<typeof createHostProvider>[] = []

afterEach(() => {
  for (const p of providers.splice(0)) p.resetForTests()
})

test("lazy factory: getter creates once; shutdown forbids reopen", async () => {
  let created = 0
  const provider = createHostProvider({
    create: () => fakeHost({ attempts: 0 }),
  })
  providers.push(provider)
  provider.setFactoryForTests(() => {
    created++
    return fakeHost({ attempts: 0 })
  })
  const a = provider.get()
  const b = provider.get()
  expect(a).toBe(b)
  expect(created).toBe(1)
  await provider.close({ agents: "shutdown" })
  expect(() => provider.get()).toThrow("core host is closed")
})

test("failed close keeps the handle for retry", async () => {
  const state = { attempts: 0, failUntil: 1 }
  const provider = createHostProvider({ create: () => fakeHost(state) })
  providers.push(provider)
  provider.get()
  await expect(provider.close({ agents: "shutdown" })).rejects.toThrow("close failed")
  expect(state.attempts).toBe(1)
  await provider.close({ agents: "shutdown" })
  expect(state.attempts).toBe(2)
})

test("factory seam refuses to replace a live owner", () => {
  const provider = createHostProvider({ create: () => fakeHost({ attempts: 0 }) })
  providers.push(provider)
  provider.setFactoryForTests(() => fakeHost({ attempts: 0 }))
  provider.get()
  expect(() => provider.setFactoryForTests(() => fakeHost({ attempts: 0 }))).toThrow("cannot replace a live core host")
})

test("isShutdown is false until close", async () => {
  const provider = createHostProvider({ create: () => fakeHost({ attempts: 0 }) })
  providers.push(provider)
  expect(provider.isShutdown()).toBe(false)
  await provider.close({ agents: "shutdown" })
  expect(provider.isShutdown()).toBe(true)
})
