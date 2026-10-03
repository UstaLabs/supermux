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

test("direct loopback callers also get build, mode, managedBy and stateDir", () => {
  const full = { ...info, build: "1.5.0 (abc1234)", mode: "binary" as const, managedBy: "desktop", stateDir: "/Users/a/.mux/state" }
  expect(buildHostBody(full, false, true)).toEqual({
    hostId: info.hostId, name: info.name, protocolVersion: info.protocolVersion,
    platform: info.platform, version: info.version,
    build: "1.5.0 (abc1234)", mode: "binary", managedBy: "desktop", stateDir: "/Users/a/.mux/state",
  })
})

test("remote callers never see the local-only fields, even when authed", () => {
  const full = { ...info, build: "1.5.0 (abc1234)", mode: "binary" as const, managedBy: "desktop", stateDir: "/s" }
  const body = buildHostBody(full, true, false)
  expect(body).not.toHaveProperty("stateDir")
  expect(body).not.toHaveProperty("managedBy")
  expect(body).not.toHaveProperty("build")
  expect(body).not.toHaveProperty("mode")
})

test("gitAvailable reaches direct loopback callers only", () => {
  const noGit = { ...info, gitAvailable: false }
  expect(buildHostBody(noGit, false, true).gitAvailable).toBe(false)
  expect(buildHostBody(noGit, true, false)).not.toHaveProperty("gitAvailable")
  expect(buildHostBody(noGit, false, false)).not.toHaveProperty("gitAvailable")
  expect(buildHostBody(info, false, true)).not.toHaveProperty("gitAvailable")
})

test("requirements reach authed and direct loopback callers, never public ones", () => {
  const requirements = { git: { ok: false, install: "manual" as const, hint: "Install git" } }
  const withReq = { ...info, requirements }
  expect(buildHostBody(withReq, true, false).requirements).toEqual(requirements)
  expect(buildHostBody(withReq, false, true).requirements).toEqual(requirements)
  expect(buildHostBody(withReq, false, false)).not.toHaveProperty("requirements")
})
