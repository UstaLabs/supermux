import { beforeEach, describe, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useProxies, type Proxy } from "./proxies"

beforeEach(() => setActivePinia(createPinia()))

function makeProxy(over: Partial<Proxy> = {}): Proxy {
  return {
    domain: "app.example.com",
    sessionName: "demo",
    port: 3000,
    createdAt: "2026-06-16T00:00:00.000Z",
    isPublic: false,
    url: "https://app.example.com",
    ...over,
  }
}

describe("proxies.setStatus", () => {
  test("patches the matching entry's status in place", () => {
    const proxies = useProxies()
    proxies.replace([makeProxy()])

    proxies.setStatus("app.example.com", "down")
    expect(proxies.list[0]!.status).toBe("down")

    proxies.setStatus("app.example.com", "up")
    expect(proxies.list[0]!.status).toBe("up")
  })

  test("is a no-op when the domain is absent", () => {
    const proxies = useProxies()
    proxies.replace([makeProxy()])

    proxies.setStatus("missing.example.com", "down")
    expect(proxies.list).toHaveLength(1)
    expect(proxies.list[0]!.status).toBeUndefined()
  })
})
