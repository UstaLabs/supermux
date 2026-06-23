import { expect, test } from "bun:test"
import { createInMemoryRateLimiter, createRedisRateLimiter, type RedisLike } from "./rate-limiter"

test("in-memory: allows up to the limit per minute, then blocks", async () => {
  let t = 0
  const rl = createInMemoryRateLimiter(() => t)
  for (let i = 0; i < 3; i++) expect(await rl.allow("k", 3)).toBe(true)
  expect(await rl.allow("k", 3)).toBe(false)
  t = 61_000
  expect(await rl.allow("k", 3)).toBe(true)
})

test("in-memory: separate keys have separate windows", async () => {
  const rl = createInMemoryRateLimiter(() => 0)
  expect(await rl.allow("a", 1)).toBe(true)
  expect(await rl.allow("a", 1)).toBe(false)
  expect(await rl.allow("b", 1)).toBe(true)
})

test("redis: blocks when the counter exceeds the limit", async () => {
  let n = 0
  const client: RedisLike = { incrWithExpiry: async () => ++n }
  const rl = createRedisRateLimiter(client, createInMemoryRateLimiter(() => 0))
  expect(await rl.allow("k", 2)).toBe(true)
  expect(await rl.allow("k", 2)).toBe(true)
  expect(await rl.allow("k", 2)).toBe(false)
})

test("redis: a client error falls back to the in-memory limiter", async () => {
  const client: RedisLike = { incrWithExpiry: async () => { throw new Error("redis down") } }
  const rl = createRedisRateLimiter(client, createInMemoryRateLimiter(() => 0))
  expect(await rl.allow("k", 1)).toBe(true)
  expect(await rl.allow("k", 1)).toBe(false)
})
