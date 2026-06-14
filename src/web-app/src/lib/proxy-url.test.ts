import { test, expect } from "bun:test"
import { displayUrl } from "./proxy-url"

test("displayUrl — strips scheme and trailing slash (subdomain URL)", () => {
  expect(displayUrl("https://happy-otter.example.com")).toBe("happy-otter.example.com")
})

test("displayUrl — strips scheme and trailing slash (path-mode URL)", () => {
  expect(displayUrl("https://broker.example.com/p/happy-otter/")).toBe("broker.example.com/p/happy-otter")
})

test("displayUrl — handles http and a port", () => {
  expect(displayUrl("http://localhost:8787/p/app/")).toBe("localhost:8787/p/app")
})
