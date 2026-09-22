import {
  appendFileSync,
  chmodSync,
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from "node:fs"
import { join, posix, win32 } from "node:path"
import { cursorCredentialFreshness, promoteIfNewer } from "./credentials.js"
import { ensureSharedCursorRuntime } from "./cursor-runtime.js"
import { ENVIRONMENT_FIELDS, requireSpec, validateMcpServerNames } from "./spec.js"
import type { CursorEnvironmentSpec, McpServerSpec, PreparedEnvironment } from "./types.js"
import { copyFileReplace, writeFileNoFollow } from "./write.js"

const CURSOR_DIR_FILES = ["cli-config.json", "agent-cli-state.json"]
const RULE_REL_POSIX = ".cursor/rules/mux.mdc"
const FRONTMATTER = "---\ndescription: supermux session rules\nalwaysApply: true\n---\n\n"

function pathJoin(platform: NodeJS.Platform): (...parts: string[]) => string {
  return platform === "win32" ? win32.join : posix.join
}

function ensureHome(home: string): void {
  mkdirSync(home, { recursive: true, mode: 0o700 })
}

function renderCursorMcp(servers: McpServerSpec[]): string {
  const mcpServers: Record<string, { command: string; args: string[]; env: Record<string, string> }> = {}
  for (const server of servers) {
    mcpServers[server.name] = { command: server.command, args: server.args, env: server.env }
  }
  return JSON.stringify({ mcpServers }, null, 2)
}

function excludeFromGit(workdir: string, rel: string): void {
  const infoDir = join(workdir, ".git", "info")
  if (!existsSync(infoDir)) return
  const excludePath = join(infoDir, "exclude")
  const current = existsSync(excludePath) ? readFileSync(excludePath, "utf8") : ""
  if (current.split("\n").includes(rel)) return
  appendFileSync(excludePath, (current.endsWith("\n") || current === "" ? "" : "\n") + rel + "\n", "utf8")
}

function writeCursorInstructions(workdir: string, body: string): string {
  const rulesDir = join(workdir, ".cursor", "rules")
  mkdirSync(rulesDir, { recursive: true })
  const dest = join(workdir, ".cursor", "rules", "mux.mdc")
  writeFileNoFollow(dest, FRONTMATTER + body, 0o644)
  excludeFromGit(workdir, RULE_REL_POSIX)
  return dest
}

function isolatedEnv(home: string, platform: NodeJS.Platform): Record<string, string> {
  const j = pathJoin(platform)
  if (platform === "win32") {
    return { HOME: home, USERPROFILE: home, APPDATA: j(home, "AppData", "Roaming") }
  }
  return { HOME: home }
}

export async function prepareCursorEnvironment(spec: CursorEnvironmentSpec): Promise<PreparedEnvironment> {
  requireSpec(spec, [
    ...ENVIRONMENT_FIELDS,
    "credentials",
    "credentials.apiKey",
    "credentials.userCursorDir",
    "credentials.userConfigDir",
    "sharedRuntime",
    "platform",
  ])
  validateMcpServerNames(spec.mcpServers)
  ensureHome(spec.home)
  const files: string[] = []
  // Filesystem paths follow the HOST (this process writes them); only the env
  // values in isolatedEnv follow spec.platform, so a win32 spec exercised on a
  // POSIX test host never writes a literal "C:\..." path into the cwd.
  const j = join
  const env: Record<string, string> = isolatedEnv(spec.home, spec.platform)

  if (spec.sharedRuntime !== null && spec.platform !== "win32") {
    const userHomeDir = posix.dirname(spec.credentials.userCursorDir)
    const userRuntime = j(userHomeDir, ".local", "share", "cursor-agent")
    ensureSharedCursorRuntime(spec.home, {
      sharedDir: spec.sharedRuntime.source,
      userRuntime,
    })
  }

  let credentials: PreparedEnvironment["credentials"]
  if (spec.credentials.apiKey) {
    credentials = "api_key"
    env.CURSOR_API_KEY = spec.credentials.apiKey
  } else {
    const xdgAuthSrc = j(spec.credentials.userConfigDir, "cursor", "auth.json")
    const configSrc = j(spec.credentials.userCursorDir, "cli-config.json")
    const sessionConfigBase = spec.platform === "win32"
      ? j(spec.home, "AppData", "Roaming")
      : j(spec.home, ".config")
    const sessionAuth = j(sessionConfigBase, "cursor", "auth.json")

    promoteIfNewer({
      sessionCopy: sessionAuth,
      canonical: xdgAuthSrc,
      freshness: cursorCredentialFreshness,
    })

    if (!existsSync(xdgAuthSrc) && !existsSync(configSrc)) {
      throw new Error(
        `Cursor auth not found at ${xdgAuthSrc} and CURSOR_API_KEY is unset. ` +
        `Run \`cursor-agent login\` first.`,
      )
    }

    const destCursorDir = j(spec.home, ".cursor")
    mkdirSync(destCursorDir, { recursive: true, mode: 0o700 })
    chmodSync(destCursorDir, 0o700)
    for (const f of CURSOR_DIR_FILES) {
      const src = j(spec.credentials.userCursorDir, f)
      if (existsSync(src)) {
        const dst = j(destCursorDir, f)
        copyFileReplace(src, dst)
        files.push(dst)
      }
    }

    if (existsSync(xdgAuthSrc)) {
      const destAuthDir = j(sessionConfigBase, "cursor")
      mkdirSync(destAuthDir, { recursive: true, mode: 0o700 })
      copyFileReplace(xdgAuthSrc, sessionAuth)
      files.push(sessionAuth)
    }
    credentials = "copy"
  }

  const cursorDir = j(spec.home, ".cursor")
  mkdirSync(cursorDir, { recursive: true, mode: 0o700 })
  chmodSync(cursorDir, 0o700)
  const mcpPath = j(cursorDir, "mcp.json")
  writeFileSync(mcpPath, renderCursorMcp(spec.mcpServers), { encoding: "utf8", mode: 0o600 })
  chmodSync(mcpPath, 0o600)
  files.push(mcpPath)

  if (spec.instructions !== null) {
    files.push(writeCursorInstructions(spec.workdir, spec.instructions))
  }

  return { env, files, credentials }
}
