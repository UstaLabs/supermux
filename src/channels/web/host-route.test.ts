import { expect, test } from "bun:test"
import { buildHostBody } from "./host-route"

const info = { hostId: "abc23def", name: "Ahmet-MBP", platform: "macos", version: "0.11.0", protocolVersion: 1 }

test("unauthenticated body is identity-only", () => {
  expect(buildHostBody(info, false)).toEqual({ hostId: "abc23def", name: "Ahmet-MBP", protocolVersion: 1 })
})

test("authenticated body adds platform + version", () => {
  expect(buildHostBody(info, true)).toEqual({
    hostId: "abc23def", name: "Ahmet-MBP", protocolVersion: 1, platform: "macos", version: "0.11.0",
  })
})
