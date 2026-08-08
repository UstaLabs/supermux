import { test, expect } from "bun:test"
import { agentAuthCapabilitiesOf, capabilitiesOf } from "./agent-capabilities"

test("capabilitiesOf prefers server flags over kind fallbacks", () => {
  const caps = capabilitiesOf({
    agent: "claude",
    capabilities: { hasAgentTerminal: false, supportsLiveConfigChange: true },
  })
  expect(caps).toEqual({ hasAgentTerminal: false, supportsLiveConfigChange: true })
})

test("capabilitiesOf falls back to pre-flag kind rules on an old broker", () => {
  // Old broker: no capabilities on the session DTO.
  expect(capabilitiesOf({ agent: "claude" })).toEqual({
    hasAgentTerminal: true,
    supportsLiveConfigChange: false,
  })
  expect(capabilitiesOf({ agent: "codex" })).toEqual({
    hasAgentTerminal: false,
    supportsLiveConfigChange: true,
  })
})

test("capabilitiesOf is safe on a missing session", () => {
  expect(capabilitiesOf(undefined)).toEqual({
    hasAgentTerminal: false,
    supportsLiveConfigChange: false,
  })
})

test("agentAuthCapabilitiesOf prefers server flags", () => {
  const caps = agentAuthCapabilitiesOf({
    kind: "opencode",
    capabilities: { supportsDeviceLogin: true, acceptsPastedKey: true, usableWithoutAuth: false },
  })
  expect(caps).toEqual({ supportsDeviceLogin: true, acceptsPastedKey: true, usableWithoutAuth: false })
})

test("agentAuthCapabilitiesOf falls back to the pre-flag kind lists", () => {
  expect(agentAuthCapabilitiesOf({ kind: "grok" })).toEqual({
    supportsDeviceLogin: true,
    acceptsPastedKey: false,
    usableWithoutAuth: false,
  })
  expect(agentAuthCapabilitiesOf({ kind: "opencode" })).toEqual({
    supportsDeviceLogin: false,
    acceptsPastedKey: false,
    usableWithoutAuth: true,
  })
  expect(agentAuthCapabilitiesOf({ kind: "cursor" })).toEqual({
    supportsDeviceLogin: true,
    acceptsPastedKey: true,
    usableWithoutAuth: false,
  })
})
