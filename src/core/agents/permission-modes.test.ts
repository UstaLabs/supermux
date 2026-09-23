import { expect, test } from "bun:test"
import { AGENT_KINDS } from "../../shared/agents"
import {
  defaultPermissionMode,
  driverSettingsFor,
  isPermissionMode,
  modesFor,
  permissionCatalog,
  permissionsFor,
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
      expect(settings.initial.kind === "claude" || settings.initial.kind === "codex" || settings.initial.kind === "acp").toBe(true)
    }
  }
})

test("unknown id throws; missing id resolves to default", () => {
  expect(() => driverSettingsFor("grok", "nope")).toThrow(/unknown permission mode/)
  expect(resolvePermissionMode("grok", undefined)).toBe(defaultPermissionMode("grok"))
  expect(isPermissionMode("claude", "ask")).toBe(true)
  expect(isPermissionMode("claude", "full-access")).toBe(false)
})

test("claude ask uses default; bypass uses bypassPermissions", () => {
  const ask = driverSettingsFor("claude", "ask")
  expect(ask.initial).toEqual({ kind: "claude", permissionMode: "default" })
  expect(ask.permissionPrompts).toBe("host")
  const bypass = driverSettingsFor("claude", "bypass")
  expect(bypass.initial).toEqual({ kind: "claude", permissionMode: "bypassPermissions" })
})

test("permissionsFor maps every catalog id including grok/opencode/cursor ACP policies", () => {
  expect(permissionsFor("grok", "always-approve")).toEqual({ kind: "acp", policy: "auto-approve", nativeMode: null })
  expect(permissionsFor("opencode", "ask-bash")).toMatchObject({ kind: "acp", policy: "ask", askKinds: ["execute"] })
  expect(permissionsFor("opencode", "read-only")).toEqual({ kind: "acp", policy: "read-only", nativeMode: "plan" })
  expect(permissionsFor("cursor", "force")).toEqual({ kind: "acp", policy: "auto-approve", nativeMode: "agent" })
  expect(permissionsFor("cursor", "auto-review")).toEqual({ kind: "acp", policy: "auto-approve", nativeMode: null })
  expect(permissionsFor("codex", "full-access")).toEqual({ kind: "codex", approvalPolicy: "never", sandbox: "danger-full-access" })
})
