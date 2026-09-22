import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import {
  mkdtempSync, rmSync, readFileSync, writeFileSync, existsSync, statSync, mkdirSync,
  lstatSync, readlinkSync, symlinkSync,
} from "fs"
import { join, win32 } from "path"
import { tmpdir } from "os"
import {
  prepareCursorEnvironment,
  ensureSharedCursorRuntime,
  sharedCursorDir,
  cursorCredentialFreshness,
} from "../src/environment/index.js"
import type { CursorEnvironmentSpec, McpServerSpec } from "../src/environment/index.js"

function jwt(expSeconds: number, marker: string): string {
  return ["e30", Buffer.from(JSON.stringify({ exp: expSeconds, sub: marker })).toString("base64url"), "sig"].join(".")
}

function cursorAuth(expSeconds: number, marker: string): string {
  return JSON.stringify({ accessToken: jwt(expSeconds, marker), refreshToken: jwt(expSeconds, marker) })
}

const MUX_SHIM: McpServerSpec = {
  name: "mux-shim",
  command: "bun",
  args: ["run", "/path/to/shim/index.ts"],
  env: {
    MUX_SESSION_ID: "zoom",
    MUX_DISPLAY_NAME: "zoom",
    MUX_AGENT_KIND: "cursor",
    MUX_SOCKETS_DIR: "/sockets",
  },
}

/** Captured from the broker writeCursorMcpConfig for the same inputs before the move. */
const CURSOR_MCP = `{
  "mcpServers": {
    "mux-shim": {
      "command": "bun",
      "args": [
        "run",
        "/path/to/shim/index.ts"
      ],
      "env": {
        "MUX_SESSION_ID": "zoom",
        "MUX_DISPLAY_NAME": "zoom",
        "MUX_AGENT_KIND": "cursor",
        "MUX_SOCKETS_DIR": "/sockets"
      }
    }
  }
}`

describe("prepareCursorEnvironment", () => {
  let dir: string
  beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "cursor-env-")) })
  afterEach(() => { rmSync(dir, { recursive: true, force: true }) })

  function spec(over: Partial<CursorEnvironmentSpec> = {}): CursorEnvironmentSpec {
    return {
      home: join(dir, "session"),
      workdir: join(dir, "wd"),
      mcpServers: [MUX_SHIM],
      skillsPaths: [],
      instructions: null,
      credentials: {
        apiKey: "key_xx",
        userCursorDir: join(dir, "user", ".cursor"),
        userConfigDir: join(dir, "user", ".config"),
      },
      sharedRuntime: null,
      platform: "linux",
      ...over,
    }
  }

  test("mcp.json matches the former broker writer byte-for-byte", async () => {
    const prepared = await prepareCursorEnvironment(spec())
    const path = join(dir, "session", ".cursor", "mcp.json")
    expect(prepared.files).toContain(path)
    expect(readFileSync(path, "utf8")).toBe(CURSOR_MCP)
    expect(statSync(path).mode & 0o777).toBe(0o600)
    expect(statSync(join(dir, "session", ".cursor")).mode & 0o777).toBe(0o700)
  })

  test("apiKey wins and does not copy auth.json", async () => {
    mkdirSync(join(dir, "user", ".config", "cursor"), { recursive: true })
    writeFileSync(join(dir, "user", ".config", "cursor", "auth.json"), cursorAuth(2000, "a"))
    const prepared = await prepareCursorEnvironment(spec())
    expect(prepared.credentials).toBe("api_key")
    expect(prepared.env.CURSOR_API_KEY).toBe("key_xx")
    expect(prepared.env.HOME).toBe(join(dir, "session"))
    expect(existsSync(join(dir, "session", ".config", "cursor", "auth.json"))).toBe(false)
  })

  test("isolates APPDATA and USERPROFILE for Windows API-key sessions", async () => {
    // The home stays under the temp dir: a literal Windows path would be
    // created relative to the cwd on POSIX and leak files into the repo.
    const session = join(dir, "session-win")
    const prepared = await prepareCursorEnvironment(spec({
      home: session,
      platform: "win32",
    }))
    expect(prepared.env).toMatchObject({
      CURSOR_API_KEY: "key_xx",
      APPDATA: win32.join(session, "AppData", "Roaming"),
      USERPROFILE: session,
      HOME: session,
    })
  })

  test("copies auth files into session HOME/.cursor + .config/cursor/", async () => {
    const userCursor = join(dir, "user", ".cursor")
    const userConfig = join(dir, "user", ".config")
    mkdirSync(userCursor, { recursive: true })
    mkdirSync(join(userConfig, "cursor"), { recursive: true })
    writeFileSync(join(userCursor, "cli-config.json"), '{"authInfo":{"email":"x"}}')
    writeFileSync(join(userCursor, "agent-cli-state.json"), '{"state":"y"}')
    writeFileSync(join(userConfig, "cursor", "auth.json"), '{"accessToken":"tok"}')
    const prepared = await prepareCursorEnvironment(spec({
      credentials: { apiKey: null, userCursorDir: userCursor, userConfigDir: userConfig },
    }))
    expect(prepared.credentials).toBe("copy")
    expect(readFileSync(join(dir, "session", ".cursor", "cli-config.json"), "utf8")).toBe('{"authInfo":{"email":"x"}}')
    expect(readFileSync(join(dir, "session", ".cursor", "agent-cli-state.json"), "utf8")).toBe('{"state":"y"}')
    expect(readFileSync(join(dir, "session", ".config", "cursor", "auth.json"), "utf8")).toBe('{"accessToken":"tok"}')
  })

  test("throws when neither apiKey nor auth.json/cli-config exists", async () => {
    await expect(prepareCursorEnvironment(spec({
      credentials: { apiKey: null, userCursorDir: join(dir, "user", ".cursor"), userConfigDir: join(dir, "user", ".config") },
    }))).rejects.toThrow(/cursor-agent login/)
  })

  test("cursorCredentialFreshness reads the access token expiry", () => {
    const path = join(dir, "probe.json")
    writeFileSync(path, cursorAuth(2_000_000, "a"))
    expect(cursorCredentialFreshness(path)).toBe(2_000_000_000)
    writeFileSync(path, '{"accessToken":"opaque"}')
    expect(cursorCredentialFreshness(path)).toBe(Number.NEGATIVE_INFINITY)
  })

  test("a session copy that refreshed its token is promoted before the re-copy", async () => {
    const userCursor = join(dir, "user", ".cursor")
    const userConfig = join(dir, "user", ".config")
    const canonical = join(userConfig, "cursor", "auth.json")
    const sessionAuth = join(dir, "session", ".config", "cursor", "auth.json")
    mkdirSync(join(userConfig, "cursor"), { recursive: true })
    mkdirSync(join(dir, "session", ".config", "cursor"), { recursive: true })
    writeFileSync(canonical, cursorAuth(1_000_000, "stale"))
    writeFileSync(sessionAuth, cursorAuth(2_000_000, "refreshed"))
    await prepareCursorEnvironment(spec({
      credentials: { apiKey: null, userCursorDir: userCursor, userConfigDir: userConfig },
    }))
    expect(readFileSync(canonical, "utf8")).toBe(cursorAuth(2_000_000, "refreshed"))
    expect(readFileSync(sessionAuth, "utf8")).toBe(cursorAuth(2_000_000, "refreshed"))
  })

  test("a stale session copy never overwrites a newer canonical credential", async () => {
    const userCursor = join(dir, "user", ".cursor")
    const userConfig = join(dir, "user", ".config")
    const canonical = join(userConfig, "cursor", "auth.json")
    const sessionAuth = join(dir, "session", ".config", "cursor", "auth.json")
    mkdirSync(join(userConfig, "cursor"), { recursive: true })
    mkdirSync(join(dir, "session", ".config", "cursor"), { recursive: true })
    writeFileSync(canonical, cursorAuth(2_000_000, "canonical"))
    writeFileSync(sessionAuth, cursorAuth(1_000_000, "stale"))
    await prepareCursorEnvironment(spec({
      credentials: { apiKey: null, userCursorDir: userCursor, userConfigDir: userConfig },
    }))
    expect(readFileSync(canonical, "utf8")).toBe(cursorAuth(2_000_000, "canonical"))
    expect(readFileSync(sessionAuth, "utf8")).toBe(cursorAuth(2_000_000, "canonical"))
  })

  test("a corrupt session copy never clobbers the canonical credential", async () => {
    const userCursor = join(dir, "user", ".cursor")
    const userConfig = join(dir, "user", ".config")
    const canonical = join(userConfig, "cursor", "auth.json")
    mkdirSync(join(userConfig, "cursor"), { recursive: true })
    mkdirSync(join(dir, "session", ".config", "cursor"), { recursive: true })
    writeFileSync(canonical, cursorAuth(1_000_000, "canonical"))
    writeFileSync(join(dir, "session", ".config", "cursor", "auth.json"), "{ truncated by a crash")
    await prepareCursorEnvironment(spec({
      credentials: { apiKey: null, userCursorDir: userCursor, userConfigDir: userConfig },
    }))
    expect(readFileSync(canonical, "utf8")).toBe(cursorAuth(1_000_000, "canonical"))
  })

  test("a logout on the host is not undone by a leftover session copy", async () => {
    const userCursor = join(dir, "user", ".cursor")
    const userConfig = join(dir, "user", ".config")
    mkdirSync(join(dir, "session", ".config", "cursor"), { recursive: true })
    writeFileSync(join(dir, "session", ".config", "cursor", "auth.json"), cursorAuth(2_000_000, "leftover"))
    await expect(prepareCursorEnvironment(spec({
      credentials: { apiKey: null, userCursorDir: userCursor, userConfigDir: userConfig },
    }))).rejects.toThrow(/cursor-agent login/)
    expect(existsSync(join(userConfig, "cursor", "auth.json"))).toBe(false)
  })

  test("identity and CLI state never travel back to the user's home", async () => {
    const userCursor = join(dir, "user", ".cursor")
    const userConfig = join(dir, "user", ".config")
    mkdirSync(userCursor, { recursive: true })
    mkdirSync(join(userConfig, "cursor"), { recursive: true })
    writeFileSync(join(userCursor, "cli-config.json"), '{"authInfo":{"email":"canonical"}}')
    writeFileSync(join(userConfig, "cursor", "auth.json"), cursorAuth(1_000_000, "canonical"))
    mkdirSync(join(dir, "session", ".cursor"), { recursive: true })
    writeFileSync(join(dir, "session", ".cursor", "cli-config.json"), '{"authInfo":{"email":"session"}}')
    await prepareCursorEnvironment(spec({
      credentials: { apiKey: null, userCursorDir: userCursor, userConfigDir: userConfig },
    }))
    expect(readFileSync(join(userCursor, "cli-config.json"), "utf8")).toBe('{"authInfo":{"email":"canonical"}}')
  })

  test("instructions write mux.mdc with former preamble front matter + body", async () => {
    mkdirSync(join(dir, "wd"), { recursive: true })
    const prepared = await prepareCursorEnvironment(spec({ instructions: "hello-cursor" }))
    const dest = join(dir, "wd", ".cursor", "rules", "mux.mdc")
    expect(prepared.files).toContain(dest)
    expect(readFileSync(dest, "utf8")).toBe("---\ndescription: supermux session rules\nalwaysApply: true\n---\n\nhello-cursor")
  })

  test("registers a local git exclude so the rule does not pollute the user's repo", async () => {
    mkdirSync(join(dir, "wd", ".git", "info"), { recursive: true })
    await prepareCursorEnvironment(spec({ instructions: "body" }))
    const exclude = readFileSync(join(dir, "wd", ".git", "info", "exclude"), "utf8")
    expect(exclude).toContain(".cursor/rules/mux.mdc")
  })

  test("refuses to write mux.mdc through a symlink; target is untouched", async () => {
    mkdirSync(join(dir, "wd", ".cursor", "rules"), { recursive: true })
    const victim = join(dir, "victim")
    writeFileSync(victim, "keep-me")
    symlinkSync(victim, join(dir, "wd", ".cursor", "rules", "mux.mdc"))
    await expect(prepareCursorEnvironment(spec({ instructions: "pwned" }))).rejects.toThrow(/refusing to write through symlink/)
    expect(readFileSync(victim, "utf8")).toBe("keep-me")
  })

  test("dest symlink to canonical auth is unlinked; canonical unchanged; session copy is a regular file", async () => {
    const userCursor = join(dir, "user", ".cursor")
    const userConfig = join(dir, "user", ".config")
    mkdirSync(join(userConfig, "cursor"), { recursive: true })
    mkdirSync(userCursor, { recursive: true })
    const canonical = join(userConfig, "cursor", "auth.json")
    writeFileSync(canonical, cursorAuth(1_000_000, "canonical"))
    writeFileSync(join(userCursor, "cli-config.json"), "{}")
    const sessionAuth = join(dir, "session", ".config", "cursor", "auth.json")
    mkdirSync(join(dir, "session", ".config", "cursor"), { recursive: true })
    symlinkSync(canonical, sessionAuth)
    await prepareCursorEnvironment(spec({
      credentials: { apiKey: null, userCursorDir: userCursor, userConfigDir: userConfig },
    }))
    expect(readFileSync(canonical, "utf8")).toBe(cursorAuth(1_000_000, "canonical"))
    expect(lstatSync(sessionAuth).isSymbolicLink()).toBe(false)
    expect(statSync(sessionAuth).isFile()).toBe(true)
  })

  test("injected MCP name throws TypeError and writes nothing", async () => {
    await expect(prepareCursorEnvironment(spec({
      mcpServers: [{ name: "foo.bar", command: "true", args: [], env: {} }],
    }))).rejects.toThrow(/mcpServers\[0\]\.name/)
    expect(existsSync(join(dir, "session", ".cursor", "mcp.json"))).toBe(false)
  })

  test("requireSpec TypeError names each missing field", async () => {
    const full: any = spec()
    for (const field of ["home", "workdir", "mcpServers", "skillsPaths", "instructions", "sharedRuntime", "platform", "credentials"]) {
      const s = { ...full }; delete s[field]
      await expect(prepareCursorEnvironment(s)).rejects.toThrow(new RegExp(`${field} is required`))
    }
    await expect(prepareCursorEnvironment({
      ...full,
      credentials: { userCursorDir: "x", userConfigDir: "y" },
    } as any)).rejects.toThrow(/credentials.apiKey is required/)
  })

  test("is a no-op for git exclude when the workspace is not a git repo", async () => {
    mkdirSync(join(dir, "wd"), { recursive: true })
    await prepareCursorEnvironment(spec({ instructions: "body" }))
    expect(existsSync(join(dir, "wd", ".cursor", "rules", "mux.mdc"))).toBe(true)
  })
})

describe("ensureSharedCursorRuntime", () => {
  let root: string
  let stateDir: string
  let shared: string
  const RUNTIME_REL = ".local/share/cursor-agent"

  beforeEach(() => {
    root = mkdtempSync(join(tmpdir(), "amux-shared-"))
    stateDir = join(root, "state")
    shared = sharedCursorDir(stateDir)
  })
  afterEach(() => { rmSync(root, { recursive: true, force: true }) })

  function home(name: string): string {
    const h = join(stateDir, "agents", "cursor", name)
    mkdirSync(h, { recursive: true })
    return h
  }

  function seedRealRuntime(payload = "BUILD-A"): string {
    const ur = join(root, "user-runtime")
    mkdirSync(join(ur, "versions"), { recursive: true })
    writeFileSync(join(ur, "versions", "build"), payload)
    return ur
  }

  test("fresh home: seeds shared from user runtime and creates a symlink", () => {
    const userRuntime = seedRealRuntime("BUILD-A")
    const h = home("s1")
    ensureSharedCursorRuntime(h, { sharedDir: shared, userRuntime })
    const link = join(h, RUNTIME_REL)
    expect(lstatSync(link).isSymbolicLink()).toBe(true)
    expect(readlinkSync(link)).toBe(shared)
    expect(readFileSync(join(shared, "versions", "build"), "utf8")).toBe("BUILD-A")
  })

  test("existing real runtime dir is migrated into the (empty) shared copy", () => {
    const h = home("s1")
    const link = join(h, RUNTIME_REL)
    mkdirSync(join(link, "versions"), { recursive: true })
    writeFileSync(join(link, "versions", "build"), "FROM-HOME")
    ensureSharedCursorRuntime(h, { sharedDir: shared, userRuntime: join(root, "nope") })
    expect(lstatSync(link).isSymbolicLink()).toBe(true)
    expect(readFileSync(join(shared, "versions", "build"), "utf8")).toBe("FROM-HOME")
  })

  test("second home collapses to a symlink without re-copying", () => {
    const userRuntime = seedRealRuntime("BUILD-A")
    const h1 = home("s1")
    ensureSharedCursorRuntime(h1, { sharedDir: shared, userRuntime })
    const h2 = home("s2")
    const link2 = join(h2, RUNTIME_REL)
    mkdirSync(join(link2, "versions"), { recursive: true })
    writeFileSync(join(link2, "versions", "build"), "STALE-COPY")
    ensureSharedCursorRuntime(h2, { sharedDir: shared, userRuntime })
    expect(lstatSync(link2).isSymbolicLink()).toBe(true)
    expect(readlinkSync(link2)).toBe(shared)
    expect(readFileSync(join(shared, "versions", "build"), "utf8")).toBe("BUILD-A")
  })

  test("already-correct symlink is a no-op", () => {
    const userRuntime = seedRealRuntime()
    const h = home("s1")
    ensureSharedCursorRuntime(h, { sharedDir: shared, userRuntime })
    const link = join(h, RUNTIME_REL)
    const before = readlinkSync(link)
    ensureSharedCursorRuntime(h, { sharedDir: shared, userRuntime })
    expect(readlinkSync(link)).toBe(before)
  })

  test("no shared, no user runtime: leaves an empty shared dir + symlink", () => {
    const h = home("s1")
    ensureSharedCursorRuntime(h, { sharedDir: shared, userRuntime: join(root, "nope") })
    const link = join(h, RUNTIME_REL)
    expect(lstatSync(link).isSymbolicLink()).toBe(true)
    expect(existsSync(shared)).toBe(true)
  })
})
