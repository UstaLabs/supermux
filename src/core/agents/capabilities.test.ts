import { test, expect } from "bun:test"
import { AGENT_KINDS } from "../../shared/agents"
import { agentAuthCapabilities, sessionCapabilities } from "./capabilities"

test("claude sessions have an agent terminal and no live-config restart", () => {
  expect(sessionCapabilities("claude")).toEqual({
    hasAgentTerminal: true,
    supportsLiveConfigChange: false,
  })
})

test("non-claude sessions have no agent terminal and support Change-now restarts", () => {
  for (const kind of AGENT_KINDS) {
    if (kind === "claude") continue
    expect(sessionCapabilities(kind)).toEqual({
      hasAgentTerminal: false,
      supportsLiveConfigChange: true,
    })
  }
})

test("auth capabilities per kind match today's login panel behavior", () => {
  expect(agentAuthCapabilities("claude")).toEqual({
    supportsDeviceLogin: true, acceptsPastedKey: true, usableWithoutAuth: false,
  })
  expect(agentAuthCapabilities("codex")).toEqual({
    supportsDeviceLogin: true, acceptsPastedKey: true, usableWithoutAuth: false,
  })
  expect(agentAuthCapabilities("cursor")).toEqual({
    supportsDeviceLogin: true, acceptsPastedKey: true, usableWithoutAuth: false,
  })
  expect(agentAuthCapabilities("opencode")).toEqual({
    supportsDeviceLogin: false, acceptsPastedKey: false, usableWithoutAuth: true,
  })
  expect(agentAuthCapabilities("grok")).toEqual({
    supportsDeviceLogin: true, acceptsPastedKey: false, usableWithoutAuth: false,
  })
  expect(agentAuthCapabilities("antigravity", "linux")).toEqual({
    supportsDeviceLogin: true, acceptsPastedKey: true, usableWithoutAuth: false,
  })
  expect(agentAuthCapabilities("antigravity", "darwin").supportsDeviceLogin).toBe(true)
})

test("antigravity has no device login on Windows (agy's login needs a pty)", () => {
  expect(agentAuthCapabilities("antigravity", "win32")).toEqual({
    supportsDeviceLogin: false, acceptsPastedKey: true, usableWithoutAuth: false,
  })
  expect(agentAuthCapabilities("grok", "win32").supportsDeviceLogin).toBe(true)
})
