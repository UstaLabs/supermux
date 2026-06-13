import { test, expect } from "bun:test"
import { baseDomainOf } from "./proxy-url"

test("baseDomainOf — multi-label host keeps the last two labels", () => {
  expect(baseDomainOf("foo.example.com")).toBe("example.com")
  expect(baseDomainOf("a.b.c.example.com")).toBe("example.com")
})

test("baseDomainOf — bare two-label host is returned as-is", () => {
  expect(baseDomainOf("example.com")).toBe("example.com")
})

test("baseDomainOf — single-label host (e.g. localhost) is returned as-is", () => {
  expect(baseDomainOf("localhost")).toBe("localhost")
  expect(baseDomainOf("")).toBe("")
})

test("baseDomainOf — mirrors the existing Proxies-page heuristic on a raw IP", () => {
  // Proxies aren't used on raw-IP hosts; this only locks parity with the
  // original logic (slice last two labels), not that the result is meaningful.
  expect(baseDomainOf("127.0.0.1")).toBe("0.1")
})
