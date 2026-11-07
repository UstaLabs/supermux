// One shared install of OpenCode's plugin dependencies (#102). OpenCode installs @opencode-ai/plugin (62 MB) into
// every config folder it reads, and supermux gives each session its own, so every new session paid that install,
// and one with a JS plugin waited for it before answering `initialize` (44–99 s, measured). OpenCode leaves a folder
// alone when it already holds package.json, package-lock.json and node_modules for its own version, so a new
// session's folder gets copies of the first two and a link to one install per OpenCode version. When the versions
// differ, OpenCode installs into the session's folder as before: npm replaces the link, it never writes through it.
import { copyFileSync, existsSync, lstatSync, mkdirSync, readdirSync, readFileSync, realpathSync, renameSync, rmSync, statSync, writeFileSync } from "fs"
import { randomBytes } from "crypto"
import { dirname, join } from "path"
import { files } from "@supermux/platform"
import { spawnCommand } from "../../process/launcher"

const PACKAGE = "@opencode-ai/plugin"

function readJson(path: string): any {
  try { return JSON.parse(readFileSync(path, "utf8")) } catch { return undefined }
}

function lstatSafe(path: string) {
  try { return lstatSync(path) } catch { return undefined }
}

/** Whether [dir] holds OpenCode's finished install of @opencode-ai/plugin [version]. */
export function isCompleteInstall(dir: string, version: string): boolean {
  return readJson(join(dir, "package.json"))?.dependencies?.[PACKAGE] === version
    && existsSync(join(dir, "package-lock.json"))
    // npm writes its hidden lockfile when an install has finished.
    && existsSync(join(dir, "node_modules", ".package-lock.json"))
    && readJson(join(dir, "node_modules", "@opencode-ai", "plugin", "package.json"))?.version === version
}

/** Where the shared installs live, how the launching OpenCode's version is known, and where to take one from. */
export type OpenCodePluginDeps = {
  /** One folder per OpenCode version. */
  store: string
  /** The version the next launch runs, when already known: never waits for the binary. */
  version: () => string | undefined
  /** Other sessions' OpenCode config folders: a finished install there is copied into the store once. */
  candidates: () => string[]
}

export type SeedResult = "present" | "seeded" | "unavailable"

const harvests = new Map<string, Promise<boolean>>()

/** Whether the store has a finished install of [version], copying one from a candidate first (once at a time). */
function storeInstall(store: string, version: string, candidates: () => string[]): Promise<boolean> {
  const dir = join(store, version)
  if (isCompleteInstall(dir, version)) return Promise.resolve(true)
  let running = harvests.get(dir)
  if (!running) {
    running = harvest(store, version, candidates).finally(() => harvests.delete(dir))
    harvests.set(dir, running)
  }
  return running
}

async function harvest(store: string, version: string, candidates: () => string[]): Promise<boolean> {
  const dir = join(store, version)
  // A seeded session's node_modules is a link into the store: only a session's own install is taken.
  const source = candidates().find((candidate) => lstatSafe(join(candidate, "node_modules"))?.isDirectory() && isCompleteInstall(candidate, version))
  if (!source) return false
  mkdirSync(store, { recursive: true })
  const staging = join(store, `.harvest-${version}-${randomBytes(4).toString("hex")}`)
  try {
    mkdirSync(staging)
    copyFileSync(join(source, "package.json"), join(staging, "package.json"))
    copyFileSync(join(source, "package-lock.json"), join(staging, "package-lock.json"))
    await files.copyTree(join(source, "node_modules"), join(staging, "node_modules"))
    if (!isCompleteInstall(staging, version)) return false
    // Another broker may have finished first: then its install is the one used.
    try { renameSync(staging, dir) } catch { /* kept below */ }
    return isCompleteInstall(dir, version)
  } finally {
    rmSync(staging, { recursive: true, force: true })
  }
}

/**
 * Gives a session's OpenCode config folder the store's install of [version] (taking one from another session first
 * when the store has none): `present` when the folder already has a node_modules (it is never touched),
 * `unavailable` when the version is unknown or no finished install of it exists yet.
 */
export async function seedPluginDeps(o: { configDir: string; version: string | undefined; store: string; candidates: () => string[] }): Promise<SeedResult> {
  if (lstatSafe(join(o.configDir, "node_modules"))) return "present"
  if (o.version === undefined) return "unavailable"
  if (!(await storeInstall(o.store, o.version, o.candidates))) return "unavailable"
  const dir = join(o.store, o.version)
  copyFileSync(join(dir, "package.json"), join(o.configDir, "package.json"))
  copyFileSync(join(dir, "package-lock.json"), join(o.configDir, "package-lock.json"))
  files.linkDirectory(join(dir, "node_modules"), join(o.configDir, "node_modules"))
  return "seeded"
}

type Identity = { binary: string; size: number; mtimeMs: number }
type Known = Identity & { version: string }

/**
 * OpenCode's version, asked of the binary (`--version`, 5–8 s) once per binary: its real path, size and mtime.
 * Remembered in [cacheFile] across restarts.
 */
export class OpenCodeVersions {
  private cached: Known | undefined
  private running: Promise<string | undefined> | undefined

  constructor(private readonly resolveBinary: () => string | null, private readonly cacheFile?: string) {
    const stored = cacheFile ? readJson(cacheFile) : undefined
    if (stored && typeof stored.binary === "string" && typeof stored.size === "number" && typeof stored.mtimeMs === "number" && typeof stored.version === "string") {
      this.cached = stored
    }
  }

  private identity(): (Identity & { command: string }) | undefined {
    const command = this.resolveBinary()
    if (!command) return undefined
    try {
      const binary = realpathSync(command)
      const st = statSync(binary)
      return { command, binary, size: st.size, mtimeMs: st.mtimeMs }
    } catch {
      return undefined
    }
  }

  /** The version of the binary as it is now, when already known. Runs nothing. */
  known(): string | undefined {
    const id = this.identity()
    const c = this.cached
    return id && c && c.binary === id.binary && c.size === id.size && c.mtimeMs === id.mtimeMs ? c.version : undefined
  }

  /** The version, asking the binary when it is not known; undefined when it cannot be told. */
  probe(): Promise<string | undefined> {
    const known = this.known()
    if (known !== undefined) return Promise.resolve(known)
    if (!this.running) this.running = this.ask().finally(() => { this.running = undefined })
    return this.running
  }

  private async ask(): Promise<string | undefined> {
    const id = this.identity()
    if (!id) return undefined
    const version = await versionOf(id.command)
    if (version === undefined) return undefined
    this.cached = { binary: id.binary, size: id.size, mtimeMs: id.mtimeMs, version }
    if (this.cacheFile) {
      try {
        mkdirSync(dirname(this.cacheFile), { recursive: true })
        writeFileSync(this.cacheFile, JSON.stringify(this.cached) + "\n")
      } catch { /* known for this process only */ }
    }
    return version
  }
}

function versionOf(command: string): Promise<string | undefined> {
  return new Promise((resolve) => {
    let out = ""
    let child: ReturnType<typeof spawnCommand>
    try {
      child = spawnCommand(command, ["--version"], { stdio: ["ignore", "pipe", "ignore"] })
    } catch {
      resolve(undefined)
      return
    }
    const timer = setTimeout(() => { child.kill("SIGKILL"); resolve(undefined) }, 30_000)
    child.stdout?.on("data", (chunk: Buffer) => { out += chunk.toString("utf8") })
    child.on("error", () => { clearTimeout(timer); resolve(undefined) })
    child.on("close", (code) => {
      clearTimeout(timer)
      const line = out.trim().split("\n")[0]?.trim() ?? ""
      resolve(code === 0 && /^\d+\.\d+\.\d+\S*$/.test(line) ? line : undefined)
    })
  })
}

/** The broker's shared installs: `<state>/opencode-plugin-deps/<version>`, taken from its sessions' config folders. */
export function brokerPluginDeps(stateDir: string, resolveBinary: () => string | null): OpenCodePluginDeps {
  const store = join(stateDir, "opencode-plugin-deps")
  const versions = new OpenCodeVersions(resolveBinary, join(store, "versions.json"))
  void versions.probe()
  const sessions = join(stateDir, "agents", "opencode")
  return {
    store,
    version: () => {
      const known = versions.known()
      if (known === undefined) void versions.probe()
      return known
    },
    candidates: () => {
      try {
        return readdirSync(sessions).map((name) => join(sessions, name, "config", "opencode")).filter((dir) => existsSync(dir))
      } catch {
        return []
      }
    },
  }
}
