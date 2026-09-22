import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, readFileSync, existsSync, statSync, mkdirSync, symlinkSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { prepareClaudeEnvironment } from "../src/environment/index.js"
import type { ClaudeEnvironmentSpec, McpServerSpec } from "../src/environment/index.js"

const MUX_SHIM: McpServerSpec = {
  name: "mux-shim",
  command: "bun",
  args: ["run", "/path/to/shim/index.ts"],
  env: {
    MUX_SESSION_ID: "zoom",
    MUX_DISPLAY_NAME: "zoom",
    MUX_AGENT_KIND: "claude",
    MUX_SOCKETS_DIR: "/sockets",
  },
}

const CLAUDE_MCP_JSON = JSON.stringify({
  mcpServers: {
    "mux-shim": {
      command: "bun",
      args: ["run", "/path/to/shim/index.ts"],
      env: {
        MUX_SESSION_ID: "zoom",
        MUX_DISPLAY_NAME: "zoom",
        MUX_AGENT_KIND: "claude",
        MUX_SOCKETS_DIR: "/sockets",
      },
    },
  },
})

describe("prepareClaudeEnvironment", () => {
  let dir: string
  beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "claude-env-")) })
  afterEach(() => { rmSync(dir, { recursive: true, force: true }) })

  function spec(over: Partial<ClaudeEnvironmentSpec> = {}): ClaudeEnvironmentSpec {
    return {
      home: join(dir, "session"),
      workdir: join(dir, "wd"),
      mcpServers: [MUX_SHIM],
      skillsPaths: [],
      instructions: "worker-instructions",
      pluginDirs: [join(dir, "plugin-a"), join(dir, "plugin-b")],
      addDirs: [join(dir, "prompts")],
      systemPromptFiles: [join(dir, "environment.md"), join(dir, "memory.md")],
      strictMcp: true,
      nativeMemory: false,
      coreReplyContract: true,
      ...over,
    }
  }

  test("mcp.json matches the fixture byte-for-byte", async () => {
    const prepared = await prepareClaudeEnvironment(spec())
    const path = join(dir, "session", "mcp.json")
    expect(prepared.files).toContain(path)
    expect(readFileSync(path, "utf8")).toBe(CLAUDE_MCP_JSON)
    expect(statSync(path).mode & 0o777).toBe(0o600)
  })

  test("full spec args order: instructions, systemPromptFiles, pluginDirs, addDirs, strict mcp", async () => {
    const home = join(dir, "session")
    const prepared = await prepareClaudeEnvironment(spec())
    expect(prepared.args).toEqual([
      "--append-system-prompt-file", join(home, "instructions.md"),
      "--append-system-prompt-file", join(dir, "environment.md"),
      "--append-system-prompt-file", join(dir, "memory.md"),
      "--plugin-dir", join(dir, "plugin-a"),
      "--plugin-dir", join(dir, "plugin-b"),
      "--add-dir", join(dir, "prompts"),
      "--strict-mcp-config",
      "--mcp-config", join(home, "mcp.json"),
    ])
    expect(readFileSync(join(home, "instructions.md"), "utf8")).toBe("worker-instructions")
    expect(statSync(join(home, "instructions.md")).mode & 0o777).toBe(0o600)
  })

  test("empty spec writes no files and empty args", async () => {
    const prepared = await prepareClaudeEnvironment(spec({
      mcpServers: [],
      instructions: null,
      pluginDirs: [],
      addDirs: [],
      systemPromptFiles: [],
      strictMcp: false,
    }))
    expect(prepared.args).toEqual([])
    expect(prepared.files).toEqual([])
    expect(existsSync(join(dir, "session", "mcp.json"))).toBe(false)
    expect(existsSync(join(dir, "session", "instructions.md"))).toBe(false)
    expect(statSync(join(dir, "session")).mode & 0o777).toBe(0o700)
  })

  test("env CLAUDE_CODE_DISABLE_AUTO_MEMORY when nativeMemory is false", async () => {
    const prepared = await prepareClaudeEnvironment(spec({ nativeMemory: false }))
    expect(prepared.env).toEqual({ CLAUDE_CODE_DISABLE_AUTO_MEMORY: "1", MUX_CORE: "1" })
    expect(prepared.credentials).toBe("none")
  })

  test("env is empty when nativeMemory is true", async () => {
    const prepared = await prepareClaudeEnvironment(spec({ nativeMemory: true }))
    expect(prepared.env).toEqual({ MUX_CORE: "1" })
  })

  test("coreReplyContract false omits MUX_CORE", async () => {
    const prepared = await prepareClaudeEnvironment(spec({ coreReplyContract: false }))
    expect(prepared.env.MUX_CORE).toBeUndefined()
  })

  test("omits mcp.json and mcp flags when mcpServers is empty", async () => {
    const prepared = await prepareClaudeEnvironment(spec({ mcpServers: [], strictMcp: true }))
    expect(existsSync(join(dir, "session", "mcp.json"))).toBe(false)
    expect(prepared.args.some((a) => a === "--mcp-config" || a === "--strict-mcp-config")).toBe(false)
  })

  test("strictMcp false still writes mcp-config without --strict-mcp-config", async () => {
    const home = join(dir, "session")
    const prepared = await prepareClaudeEnvironment(spec({ strictMcp: false }))
    expect(prepared.args.slice(-2)).toEqual(["--mcp-config", join(home, "mcp.json")])
    expect(prepared.args).not.toContain("--strict-mcp-config")
  })

  test("refuses to write mcp.json through a symlink", async () => {
    const home = join(dir, "session")
    mkdirSync(home, { recursive: true, mode: 0o700 })
    symlinkSync(join(dir, "elsewhere"), join(home, "mcp.json"))
    await expect(prepareClaudeEnvironment(spec({ home, instructions: null }))).rejects.toThrow(/symlink/)
  })

  test("refuses to write instructions.md through a symlink", async () => {
    const home = join(dir, "session")
    mkdirSync(home, { recursive: true, mode: 0o700 })
    symlinkSync(join(dir, "elsewhere"), join(home, "instructions.md"))
    await expect(prepareClaudeEnvironment(spec({ home, mcpServers: [] }))).rejects.toThrow(/symlink/)
  })

  test("invalid MCP name throws", async () => {
    await expect(prepareClaudeEnvironment(spec({
      mcpServers: [{ name: "bad name", command: "x", args: [], env: {} }],
    }))).rejects.toThrow(TypeError)
  })

  for (const field of [
    "home", "workdir", "mcpServers", "skillsPaths", "instructions",
    "pluginDirs", "addDirs", "systemPromptFiles", "strictMcp", "nativeMemory", "coreReplyContract",
  ]) {
    test(`missing ${field} throws TypeError naming it`, async () => {
      const s = spec() as unknown as Record<string, unknown>
      delete s[field]
      await expect(prepareClaudeEnvironment(s as never)).rejects.toThrow(TypeError)
      await expect(prepareClaudeEnvironment(s as never)).rejects.toThrow(new RegExp(`${field} is required`))
    })
  }
})
