import { expect, test } from "bun:test"
import { AGENT_KINDS } from "../../shared/agents"
import {
  defaultPermissionMode,
  driverSettingsFor,
  isPermissionMode,
  modesFor,
  permissionCatalog,
  resolvePermissionMode,
} from "./permission-modes"

test("every agent has exactly one default and unique ids", () => {
  const catalog = permissionCatalog()
  for (const agent of AGENT_KINDS) {
    const modes = catalog[agent]
    expect(modes.length).toBeGreaterThan(0)
    const defaults = modes.filter((m) => m.default === true)
    expect(defaults).toHaveLength(1)
    const ids = modes.map((m) => m.id)
    expect(new Set(ids).size).toBe(ids.length)
    for (const m of modes) {
      expect(m.label.length).toBeGreaterThan(0)
      expect(m.description.length).toBeGreaterThan(0)
    }
  }
})

test("driverSettingsFor covers every catalog id", () => {
  for (const agent of AGENT_KINDS) {
    for (const m of modesFor(agent)) {
      const settings = driverSettingsFor(agent, m.id)
      expect(settings.agent).toBe(agent)
    }
  }
})

test("unknown id throws; missing id resolves to default", () => {
  expect(() => driverSettingsFor("grok", "nope")).toThrow(/unknown permission mode/)
  expect(resolvePermissionMode("grok", undefined)).toBe(defaultPermissionMode("grok"))
  expect(isPermissionMode("claude", "ask")).toBe(true)
  expect(isPermissionMode("claude", "never+full-access")).toBe(false)
})

test("claude ask leaves permissionMode undefined; bypass uses bypassPermissions", () => {
  const ask = driverSettingsFor("claude", "ask")
  if (ask.agent !== "claude") throw new Error("expected claude")
  expect(ask.permissionMode).toBeUndefined()
  expect(ask.permissionPrompts).toBe("host")
  const bypass = driverSettingsFor("claude", "bypass")
  if (bypass.agent !== "claude") throw new Error("expected claude")
  expect(bypass.permissionMode).toBe("bypassPermissions")
})
