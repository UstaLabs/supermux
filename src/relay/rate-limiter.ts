export interface RateLimiter {
  /** Returns true if this event is allowed under `limitPerMin` for `key`. */
  allow(key: string, limitPerMin: number): Promise<boolean>
}

export function createInMemoryRateLimiter(now: () => number = () => Date.now()): RateLimiter {
  const hits = new Map<string, number[]>()
  return {
    async allow(key, limitPerMin) {
      const t = now()
      const win = (hits.get(key) ?? []).filter((x) => t - x < 60_000)
      if (win.length >= limitPerMin) { hits.set(key, win); return false }
      win.push(t)
      hits.set(key, win)
      return true
    },
  }
}

/** Minimal Redis surface: atomically increment `key` and (re)set a TTL, returning the new count. */
export interface RedisLike { incrWithExpiry(key: string, windowSeconds: number): Promise<number> }

export function createRedisRateLimiter(client: RedisLike, fallback: RateLimiter): RateLimiter {
  return {
    async allow(key, limitPerMin) {
      try {
        return (await client.incrWithExpiry(`rl:${key}`, 60)) <= limitPerMin
      } catch {
        return fallback.allow(key, limitPerMin)
      }
    },
  }
}
