import { test, expect } from "bun:test"
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync, existsSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { writeGrokConfig } from "./config-writer"
import { resolveGrokAuth } from "./auth"

function home(): string { return mkdtempSync(join(tmpdir(), "mux-grok-home-")) }

test("writeGrokConfig declares mux-shim with the session's env and disables claude import", () => {
  const sessionHome = home()
  const path = writeGrokConfig({
    sessionHome,
    shimCommand: "bun",
    shimArgs: ["run", "/opt/mux/shim.ts"],
    sessionName: "cool-session",
    sessionId: "sess-1",
    socketsDir: "/run/mux/sockets",
  })
  expect(path).toBe(join(sessionHome, ".grok", "config.toml"))
  const toml = readFileSync(path, "utf8")
  expect(toml).toContain("[mcp_servers.mux-shim]")
  expect(toml).toContain('command = "bun"')
  expect(toml).toContain('args = ["run", "/opt/mux/shim.ts"]')
  expect(toml).toContain("enabled = true")
  expect(toml).toContain("[mcp_servers.mux-shim.env]")
  expect(toml).toContain('MUX_SESSION_ID = "sess-1"')
  expect(toml).toContain('MUX_DISPLAY_NAME = "cool-session"')
  expect(toml).toContain('MUX_AGENT_KIND = "grok"')
  expect(toml).toContain('MUX_SOCKETS_DIR = "/run/mux/sockets"')
  expect(toml).toContain("[claude_compat]\nimported = true")
  // The broker spawns a grok child per session; an update check on launch could
  // swap the binary mid-conversation or drift versions between live sessions.
  expect(toml).toContain("auto_update = false")
})

test("writeGrokConfig escapes paths that would break the TOML", () => {
  const sessionHome = home()
  const toml = readFileSync(writeGrokConfig({
    sessionHome,
    shimCommand: "bun",
    shimArgs: ["run", '/weird/pa"th\\x.ts'],
    sessionName: 'quote"name',
    sessionId: "s",
    socketsDir: "/s",
  }), "utf8")
  expect(toml).toContain('"/weird/pa\\"th\\\\x.ts"')
  expect(toml).toContain('MUX_DISPLAY_NAME = "quote\\"name"')
})

test("writeGrokConfig is idempotent (resume rewrites cleanly)", () => {
  const sessionHome = home()
  const args = { sessionHome, shimCommand: "bun", shimArgs: ["run", "/s.ts"], sessionName: "n", sessionId: "i", socketsDir: "/d" }
  const a = readFileSync(writeGrokConfig(args), "utf8")
  const b = readFileSync(writeGrokConfig(args), "utf8")
  expect(b).toBe(a)
})

test("resolveGrokAuth copies the credential into the private home and redirects HOME", () => {
  const userGrokDir = home()
  const sessionHome = home()
  writeFileSync(join(userGrokDir, "auth.json"), '{"token":"abc"}')

  const res = resolveGrokAuth({ userGrokDir, sessionHome })
  expect(res.mode).toBe("cached_token")
  expect(res.env.HOME).toBe(sessionHome)
  // Copied, not symlinked: grok rewrites auth.json on refresh and must not write
  // back into the user's real ~/.grok.
  expect(readFileSync(join(sessionHome, ".grok", "auth.json"), "utf8")).toBe('{"token":"abc"}')
  writeFileSync(join(sessionHome, ".grok", "auth.json"), '{"token":"refreshed"}')
  expect(readFileSync(join(userGrokDir, "auth.json"), "utf8")).toBe('{"token":"abc"}')
})

test("resolveGrokAuth does not fail-closed when the user has never logged in", () => {
  const sessionHome = home()
  const res = resolveGrokAuth({ userGrokDir: join(tmpdir(), "definitely-missing-grok-dir"), sessionHome })
  expect(res.mode).toBe("none")
  expect(res.env.HOME).toBe(sessionHome)
  expect(existsSync(join(sessionHome, ".grok"))).toBe(true)
})
