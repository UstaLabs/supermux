import { test, expect, afterEach } from "bun:test"
import { mkdtempSync, rmSync, readFileSync, existsSync, statSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { prepareOpenCodeEnvironment } from "../src/environment/index.js"
import type { OpenCodeEnvironmentSpec, McpServerSpec } from "../src/environment/index.js"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs) rmSync(d, { recursive: true, force: true })
  dirs.length = 0
})

function home(): string {
  const d = mkdtempSync(join(tmpdir(), "mux-oc-home-"))
  dirs.push(d)
  return d
}

const MUX: McpServerSpec = {
  name: "mux",
  command: "bun",
  args: ["run", "/opt/mux/shim.ts"],
  env: {
    MUX_SESSION_ID: "sess-1",
    MUX_DISPLAY_NAME: "cool-session",
    MUX_AGENT_KIND: "opencode",
    MUX_SOCKETS_DIR: "/run/mux/sockets",
  },
}

/** Captured from broker writeOpenCodeConfig for the same MCP/provider/plugin/skills inputs
 *  before the move (no `instructions`: they are delivered only through the session context). */
function expectedConfig(): string {
  return `{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "mux": {
      "type": "local",
      "command": [
        "bun",
        "run",
        "/opt/mux/shim.ts"
      ],
      "enabled": true,
      "environment": {
        "MUX_SESSION_ID": "sess-1",
        "MUX_DISPLAY_NAME": "cool-session",
        "MUX_AGENT_KIND": "opencode",
        "MUX_SOCKETS_DIR": "/run/mux/sockets"
      }
    }
  },
  "provider": {
    "alibaba-token-plan": {
      "models": {
        "qwen3.8-max-preview": {
          "name": "Qwen3.8 Max Preview"
        }
      }
    }
  },
  "plugin": [
    "/plugins/superpowers"
  ],
  "skills": {
    "paths": [
      "/plugins/extra/skills"
    ]
  }
}
`
}

function spec(over: Partial<OpenCodeEnvironmentSpec> & Pick<OpenCodeEnvironmentSpec, "home" | "workdir" | "configHome">): OpenCodeEnvironmentSpec {
  return {
    mcpServers: [MUX],
    skillsPaths: ["/plugins/extra/skills"],
    pluginPaths: ["/plugins/superpowers"],
    permissions: { edit: "allow", bash: "allow", webfetch: "allow" },
    provider: { "alibaba-token-plan": { models: { "qwen3.8-max-preview": { name: "Qwen3.8 Max Preview" } } } },
    ...over,
  }
}

test("prepareOpenCodeEnvironment opencode.json matches the former broker writer byte-for-byte", async () => {
  const sessionHome = home()
  const configHome = join(sessionHome, "config")
  const prepared = await prepareOpenCodeEnvironment(spec({
    home: sessionHome,
    workdir: join(sessionHome, "wd"),
    configHome,
  }))
  const configPath = join(configHome, "opencode", "opencode.json")
  expect(prepared.files).toEqual([configPath])
  expect(readFileSync(configPath, "utf8")).toBe(expectedConfig())
  expect(statSync(configPath).mode & 0o777).toBe(0o600)
  expect(prepared.env).toEqual({ XDG_CONFIG_HOME: configHome })
  expect(prepared.credentials).toBe("none")
  expect(existsSync(join(sessionHome, "AGENTS.md"))).toBe(false)
})

test("omits plugin, skills, and provider when empty/null", async () => {
  const sessionHome = home()
  const configHome = join(sessionHome, "config")
  await prepareOpenCodeEnvironment(spec({
    home: sessionHome,
    workdir: join(sessionHome, "wd"),
    configHome,
    pluginPaths: [],
    permissions: { edit: "allow", bash: "allow", webfetch: "allow" },
    skillsPaths: [],
    provider: null,
  }))
  const cfg = JSON.parse(readFileSync(join(configHome, "opencode", "opencode.json"), "utf8"))
  expect(cfg.instructions).toBeUndefined()
  expect(cfg.plugin).toBeUndefined()
  expect(cfg.skills).toBeUndefined()
  expect(cfg.provider).toBeUndefined()
  expect(existsSync(join(sessionHome, "AGENTS.md"))).toBe(false)
})

test("registers mux MCP with session id as MUX_SESSION_ID", async () => {
  const sessionHome = home()
  const configHome = join(sessionHome, "config")
  await prepareOpenCodeEnvironment(spec({
    home: sessionHome,
    workdir: join(sessionHome, "wd"),
    configHome,
    pluginPaths: [],
    permissions: { edit: "allow", bash: "allow", webfetch: "allow" },
    skillsPaths: [],
    provider: null,
  }))
  const cfg = JSON.parse(readFileSync(join(configHome, "opencode", "opencode.json"), "utf8"))
  expect(cfg.mcp.mux.type).toBe("local")
  expect(cfg.mcp.mux.command).toEqual(["bun", "run", "/opt/mux/shim.ts"])
  expect(cfg.mcp.mux.environment.MUX_SESSION_ID).toBe("sess-1")
  expect(cfg.mcp.mux.environment.MUX_AGENT_KIND).toBe("opencode")
})

test("injected MCP name throws TypeError and writes nothing", async () => {
  const sessionHome = home()
  const configHome = join(sessionHome, "config")
  await expect(prepareOpenCodeEnvironment(spec({
    home: sessionHome,
    workdir: join(sessionHome, "wd"),
    configHome,
    mcpServers: [{ name: "foo.bar", command: "true", args: [], env: {} }],
  }))).rejects.toThrow(/mcpServers\[0\]\.name/)
  expect(existsSync(join(configHome, "opencode", "opencode.json"))).toBe(false)
})

test("requireSpec TypeError names each missing field including provider", async () => {
  const sessionHome = home()
  const full: any = spec({ home: sessionHome, workdir: join(sessionHome, "wd"), configHome: join(sessionHome, "config") })
  for (const field of ["home", "workdir", "mcpServers", "skillsPaths", "configHome", "provider", "pluginPaths"]) {
    const s = { ...full }; delete s[field]
    await expect(prepareOpenCodeEnvironment(s)).rejects.toThrow(new RegExp(`${field} is required`))
  }
})

test("permissions object writes OpenCode policy only when a tool is not allow", async () => {
  const { mkdtempSync, readFileSync, rmSync } = await import("fs")
  const { tmpdir } = await import("os")
  const { join } = await import("path")
  const dir = mkdtempSync(join(tmpdir(), "oc-perm-"))
  try {
    const base = { home: join(dir, "home"), workdir: join(dir, "wd"), mcpServers: [], skillsPaths: [], configHome: join(dir, "cfg"), provider: null, pluginPaths: [] }
    await prepareOpenCodeEnvironment({ ...base, permissions: { edit: "ask", bash: "ask", webfetch: "ask" } })
    const asked = JSON.parse(readFileSync(join(dir, "cfg", "opencode", "opencode.json"), "utf8"))
    expect(asked.permission).toEqual({ edit: "ask", bash: "ask", webfetch: "ask" })
    await prepareOpenCodeEnvironment({ ...base, permissions: { edit: "allow", bash: "allow", webfetch: "allow" } })
    const allowed = JSON.parse(readFileSync(join(dir, "cfg", "opencode", "opencode.json"), "utf8"))
    expect(allowed.permission).toBeUndefined()
    await prepareOpenCodeEnvironment({ ...base, permissions: { edit: "deny", bash: "deny", webfetch: "allow" } })
    const denied = JSON.parse(readFileSync(join(dir, "cfg", "opencode", "opencode.json"), "utf8"))
    expect(denied.permission).toEqual({ edit: "deny", bash: "deny", webfetch: "allow" })
  } finally { rmSync(dir, { recursive: true, force: true }) }
})
