import { expect, test } from "bun:test"
import { AGENT_KINDS } from "../../shared/agents"
import { SESSION_INBOX_DIR } from "../../shared/paths"
import {
  askPermissionMode,
  defaultPermissionMode,
  driverSettingsFor,
  isPermissionMode,
  isUnattendedMode,
  modesFor,
  permissionCatalog,
  permissionsFor,
  resolvePermissionMode,
  unattendedModesFor,
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
      expect(settings.initial.kind === "claude" || settings.initial.kind === "codex" || settings.initial.kind === "acp" || settings.initial.kind === "antigravity").toBe(true)
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

test("antigravity modes map to agy flags; bypass is the default and there is no ask mode", () => {
  expect(defaultPermissionMode("antigravity")).toBe("bypass")
  expect(permissionsFor("antigravity", "bypass")).toEqual({ kind: "antigravity", mode: "bypass" })
  expect(permissionsFor("antigravity", "accept-edits")).toEqual({ kind: "antigravity", mode: "acceptEdits" })
  expect(permissionsFor("antigravity", "plan")).toEqual({ kind: "antigravity", mode: "plan" })
  expect(permissionsFor("antigravity", "default")).toEqual({ kind: "antigravity", mode: "default" })
  expect(driverSettingsFor("antigravity", "bypass").permissionPrompts).toBe("host")
  expect(() => driverSettingsFor("antigravity", "ask")).toThrow(/unknown permission mode/)
  expect(askPermissionMode("antigravity")).toBe("default")
})

test("ask-level ACP modes may read the session inbox without a prompt", () => {
  expect(permissionsFor("grok", "ask")).toMatchObject({ policy: "ask", readableRoots: [SESSION_INBOX_DIR] })
  expect(permissionsFor("opencode", "ask")).toMatchObject({ policy: "ask", readableRoots: [SESSION_INBOX_DIR] })
  expect(permissionsFor("opencode", "ask-bash")).toMatchObject({ policy: "ask", askKinds: ["execute"], readableRoots: [SESSION_INBOX_DIR] })
  for (const id of ["ask", "plan", "qa"]) {
    expect(permissionsFor("cursor", id)).toMatchObject({ policy: "ask", readableRoots: [SESSION_INBOX_DIR] })
  }
})

test("every ask-level spec owns its readableRoots array: changing one never reaches another", () => {
  type Roots = { readableRoots?: string[] }
  const a = permissionsFor("grok", "ask") as Roots
  const b = permissionsFor("grok", "ask") as Roots
  const c = permissionsFor("cursor", "ask") as Roots
  const d = permissionsFor("opencode", "ask-bash") as Roots
  expect(a.readableRoots).toEqual([SESSION_INBOX_DIR])
  expect(new Set([a.readableRoots, b.readableRoots, c.readableRoots, d.readableRoots]).size).toBe(4)
  a.readableRoots!.push("/elsewhere")
  for (const other of [b, c, d, permissionsFor("grok", "ask") as Roots]) expect(other.readableRoots).toEqual([SESSION_INBOX_DIR])
})

test("every catalog mode has the level from the spec's table (§2)", () => {
  const levels = Object.fromEntries(
    Object.entries(permissionCatalog()).map(([agent, modes]) => [agent, Object.fromEntries(modes.map((m) => [m.id, m.level]))]),
  )
  expect(levels).toEqual({
    claude: { bypass: "full", "accept-edits": "edit", ask: "ask", plan: "read-only", auto: "edit", "dont-ask": "read-only" },
    codex: { "full-access": "full", auto: "edit", ask: "ask", "read-only": "read-only" },
    grok: { "always-approve": "full", ask: "ask" },
    opencode: { allow: "full", ask: "ask", "ask-bash": "edit", "read-only": "read-only" },
    cursor: { force: "full", "auto-review": "full", ask: "ask", plan: "read-only", qa: "read-only" },
    antigravity: { bypass: "full", "accept-edits": "edit", plan: "read-only", default: "ask" },
  })
})

test("a delegated child or council member gets only the modes nobody needs to approve", () => {
  const ids = (agent: Parameters<typeof unattendedModesFor>[0]) => unattendedModesFor(agent).map((m) => m.id)
  expect(ids("claude")).toEqual(["bypass", "dont-ask"])
  expect(ids("codex")).toEqual(["full-access", "auto", "read-only"])
  expect(ids("cursor")).toEqual(["force", "auto-review"])
  expect(ids("opencode")).toEqual(["allow", "read-only"])
  expect(ids("grok")).toEqual(["always-approve"])
  expect(ids("antigravity")).toEqual(["bypass", "plan"])
  expect(isUnattendedMode("claude", "plan")).toBe(false)
  expect(isUnattendedMode("cursor", "qa")).toBe(false)
  expect(isUnattendedMode("claude", "dont-ask")).toBe(true)
  expect(isUnattendedMode("claude", "nope")).toBe(false)
})
