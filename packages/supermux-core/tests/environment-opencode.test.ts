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
 *  before the move. instructions path is library-owned (<home>/AGENTS.md). */
function expectedConfig(instructionsPath: string): string {
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
  "instructions": [
    ${JSON.stringify(instructionsPath)}
  ],
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
    sessionId: "sess-1",
    sessionName: "cool-session",
    mcpServers: [MUX],
    skillsPaths: ["/plugins/extra/skills"],
    pluginPaths: ["/plugins/superpowers"],
    provider: { "alibaba-token-plan": { models: { "qwen3.8-max-preview": { name: "Qwen3.8 Max Preview" } } } },
    instructions: "preamble body\n",
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
  const instructionsPath = join(sessionHome, "AGENTS.md")
  expect(prepared.files).toContain(configPath)
  expect(prepared.files).toContain(instructionsPath)
  expect(readFileSync(configPath, "utf8")).toBe(expectedConfig(instructionsPath))
  expect(statSync(configPath).mode & 0o777).toBe(0o600)
  expect(prepared.env).toEqual({ XDG_CONFIG_HOME: configHome })
  expect(prepared.credentials).toBe("none")
  expect(readFileSync(instructionsPath, "utf8")).toBe("preamble body\n")
})

test("omits instructions, plugin, skills, and provider when empty/null", async () => {
  const sessionHome = home()
  const configHome = join(sessionHome, "config")
  await prepareOpenCodeEnvironment(spec({
    home: sessionHome,
    workdir: join(sessionHome, "wd"),
    configHome,
    instructions: null,
    pluginPaths: [],
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
    instructions: null,
    pluginPaths: [],
    skillsPaths: [],
    provider: null,
  }))
  const cfg = JSON.parse(readFileSync(join(configHome, "opencode", "opencode.json"), "utf8"))
  expect(cfg.mcp.mux.type).toBe("local")
  expect(cfg.mcp.mux.command).toEqual(["bun", "run", "/opt/mux/shim.ts"])
  expect(cfg.mcp.mux.environment.MUX_SESSION_ID).toBe("sess-1")
  expect(cfg.mcp.mux.environment.MUX_AGENT_KIND).toBe("opencode")
})
