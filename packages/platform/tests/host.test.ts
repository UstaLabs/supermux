// The one read of "which OS is this?" outside a runtime: feature code takes the OS as a parameter defaulting to it.
import { expect, test } from "bun:test"
import { host } from "../src/index.js"

test("host.os() is the OS this process runs on", () => {
  expect(host.os()).toBe(process.platform)
})

test("host.windows() says whether that is Windows", () => {
  expect(host.windows()).toBe(process.platform === "win32")
})
