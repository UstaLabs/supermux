import { test, expect } from "bun:test"
import {
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readlinkSync,
  writeFileSync,
} from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { writeGrokConfig } from "./config-writer"
import { resolveGrokAuth } from "./auth"

function home(): string { return mkdtempSync(join(tmpdir(), "mux-grok-home-")) }

function auth(expiresAt: string, token: string): string {
  return JSON.stringify({
    "https://auth.x.ai::account": {
      expires_at: expiresAt,
      key: token,
      refresh_token: `refresh-${token}`,
    },
  })
}

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

test("resolveGrokAuth symlinks the canonical credential into the private home", () => {
  const userGrokDir = home()
  const sessionHome = home()
  writeFileSync(join(userGrokDir, "auth.json"), '{"token":"abc"}')

  const res = resolveGrokAuth({ userGrokDir, sessionHome })
  expect(res.mode).toBe("cached_token")
  expect(res.env.HOME).toBe(sessionHome)
  const link = join(sessionHome, ".grok", "auth.json")
  expect(lstatSync(link).isSymbolicLink()).toBe(true)
  expect(readlinkSync(link)).toBe(join(userGrokDir, "auth.json"))

  // Grok refreshes through the session path; every session must see that refresh.
  writeFileSync(link, '{"token":"refreshed"}')
  expect(readFileSync(join(userGrokDir, "auth.json"), "utf8")).toBe('{"token":"refreshed"}')
})

test("a refresh through one Grok session is visible to sibling sessions", () => {
  const userGrokDir = home()
  const firstHome = home()
  const secondHome = home()
  writeFileSync(join(userGrokDir, "auth.json"), '{"token":"initial"}')
  resolveGrokAuth({ userGrokDir, sessionHome: firstHome })
  resolveGrokAuth({ userGrokDir, sessionHome: secondHome })

  writeFileSync(join(firstHome, ".grok", "auth.json"), '{"token":"rotated"}')

  expect(readFileSync(join(secondHome, ".grok", "auth.json"), "utf8")).toBe('{"token":"rotated"}')
})

test("resolveGrokAuth promotes a newer private refresh before replacing the old copy", () => {
  const userGrokDir = home()
  const sessionHome = home()
  const sessionGrokDir = join(sessionHome, ".grok")
  mkdirSync(sessionGrokDir)
  writeFileSync(join(userGrokDir, "auth.json"), auth("2026-07-26T15:41:47Z", "stale"))
  writeFileSync(join(sessionGrokDir, "auth.json"), auth("2026-07-26T18:33:13Z", "refreshed"))

  const res = resolveGrokAuth({ userGrokDir, sessionHome })

  expect(res.mode).toBe("cached_token")
  expect(readFileSync(join(userGrokDir, "auth.json"), "utf8")).toBe(
    auth("2026-07-26T18:33:13Z", "refreshed"),
  )
  expect(lstatSync(join(sessionGrokDir, "auth.json")).isSymbolicLink()).toBe(true)
})

test("resolveGrokAuth never lets a stale private copy overwrite newer canonical auth", () => {
  const userGrokDir = home()
  const sessionHome = home()
  const sessionGrokDir = join(sessionHome, ".grok")
  mkdirSync(sessionGrokDir)
  writeFileSync(join(userGrokDir, "auth.json"), auth("2026-07-26T18:33:13Z", "canonical"))
  writeFileSync(join(sessionGrokDir, "auth.json"), auth("2026-07-26T15:41:47Z", "stale"))

  resolveGrokAuth({ userGrokDir, sessionHome })

  expect(readFileSync(join(userGrokDir, "auth.json"), "utf8")).toBe(
    auth("2026-07-26T18:33:13Z", "canonical"),
  )
  expect(lstatSync(join(sessionGrokDir, "auth.json")).isSymbolicLink()).toBe(true)
})

test("resolveGrokAuth recovers a private credential when the canonical file is missing", () => {
  const userGrokDir = home()
  const sessionHome = home()
  const sessionGrokDir = join(sessionHome, ".grok")
  mkdirSync(sessionGrokDir)
  const credential = auth("2026-07-26T18:33:13Z", "only-copy")
  writeFileSync(join(sessionGrokDir, "auth.json"), credential)

  const res = resolveGrokAuth({ userGrokDir, sessionHome })

  expect(res.mode).toBe("cached_token")
  expect(readFileSync(join(userGrokDir, "auth.json"), "utf8")).toBe(credential)
  expect(lstatSync(join(sessionGrokDir, "auth.json")).isSymbolicLink()).toBe(true)
})

test("resolveGrokAuth does not fail-closed when the user has never logged in", () => {
  const sessionHome = home()
  const res = resolveGrokAuth({ userGrokDir: join(tmpdir(), "definitely-missing-grok-dir"), sessionHome })
  expect(res.mode).toBe("none")
  expect(res.env.HOME).toBe(sessionHome)
  expect(existsSync(join(sessionHome, ".grok"))).toBe(true)
})
