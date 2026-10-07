// src/core/forge/host.test.ts
import { test, expect } from "bun:test"
import { parseHostInput, schemeOf, withScheme } from "./host"

test("parseHostInput keeps an explicit http scheme and returns a bare host", () => {
  expect(parseHostInput("http://git.acme.com")).toEqual({ host: "git.acme.com", scheme: "http" })
})

test("parseHostInput defaults to https when the user types no scheme", () => {
  expect(parseHostInput("git.acme.com")).toEqual({ host: "git.acme.com", scheme: "https" })
})

test("parseHostInput strips an explicit https scheme and trailing slashes", () => {
  expect(parseHostInput("https://git.acme.com/")).toEqual({ host: "git.acme.com", scheme: "https" })
})

test("parseHostInput preserves a port and a LAN hostname", () => {
  expect(parseHostInput("http://git.local:8080")).toEqual({ host: "git.local:8080", scheme: "http" })
  expect(parseHostInput("http://localhost:3000/")).toEqual({ host: "localhost:3000", scheme: "http" })
})

test("schemeOf reads the scheme back off a stored apiBase, defaulting to https", () => {
  expect(schemeOf("http://git.acme.com/api/v4")).toBe("http")
  expect(schemeOf("https://gitlab.com/api/v4")).toBe("https")
  expect(schemeOf("gitlab.com/api/v4")).toBe("https")
})

test("withScheme rewrites the scheme of an adapter-derived base", () => {
  expect(withScheme("https://git.acme.com/api/v4", "http")).toBe("http://git.acme.com/api/v4")
  expect(withScheme("https://git.acme.com/api/v4", "https")).toBe("https://git.acme.com/api/v4")
})
