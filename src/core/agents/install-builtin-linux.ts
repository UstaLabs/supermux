// Linux hosts without curl. Ubuntu Desktop ships wget but NOT curl, and two vendor scripts call
// curl themselves (cursor's for its tarball, OpenCode's for the release lookup and download), so
// `wget -O- … | bash` is not enough for them. The broker installs those two itself, into the same
// places their scripts use, with no sudo:
//
//   • Cursor: the version comes from the vendor script (`downloads.cursor.com/lab/<ver>/…`), the
//     tarball unpacks to ~/.local/share/cursor-agent/versions/<ver>, and ~/.local/bin/{cursor-agent,
//     agent} link to it — exactly what `cursor.com/install` does. Cursor publishes no checksum
//     (its own script checks none either): HTTPS only, and the log says so.
//   • OpenCode: the GitHub release's `opencode-linux-<arch>[-baseline][-musl].tar.gz` (the same
//     target choice as opencode.ai/install), verified against the asset's SHA-256 digest, to
//     ~/.opencode/bin/opencode.
//
// Both dirs are on the broker's PATH (bin-dirs.ts), so the new CLI is found without a restart.
import { execFile } from "child_process"
import { chmodSync, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, symlinkSync, writeFileSync } from "fs"
import { posix } from "path"
import { sha256Hex } from "../windows/user-install"
import { METADATA_TIMEOUT_MS, OPENCODE_RELEASE_API, assertOpenCodeAssetUrl, httpsBytes, httpsGet } from "./download"

export interface LinuxBuiltinDeps {
  home: string
  /** `process.arch`: "x64" | "arm64" | … */
  arch: string
  fetch: typeof fetch
  sha256: (bytes: Uint8Array) => string
  /** `tar -xzf <archive> -C <dest> [--strip-components=<n>]`. */
  untar: (archive: string, dest: string, stripComponents: number) => Promise<void>
  /** Does this CPU have AVX2 (the non-baseline OpenCode build needs it)? */
  hasAvx2: () => boolean
  /** Is the C library musl (Alpine)? */
  isMusl: () => boolean
  fs: {
    exists: (p: string) => boolean
    mkdir: (p: string) => void
    write: (p: string, b: Uint8Array) => void
    rename: (from: string, to: string) => void
    remove: (p: string) => void
    chmod: (p: string, mode: number) => void
    symlink: (target: string, path: string) => void
  }
  log: (line: string) => void
  now?: () => number
  pid?: number
}

export type LinuxBuiltinInstaller = (deps: LinuxBuiltinDeps) => Promise<void>

export const CURSOR_INSTALL_SCRIPT = "https://cursor.com/install"
export const OPENCODE_LATEST_RELEASE_API = OPENCODE_RELEASE_API

function stampOf(deps: LinuxBuiltinDeps): string {
  return `${deps.pid ?? process.pid}-${deps.now?.() ?? Date.now()}`
}

function get(deps: LinuxBuiltinDeps, url: string, accept?: string): Promise<Response> {
  return httpsGet(deps.fetch, url, { timeoutMs: METADATA_TIMEOUT_MS, accept })
}

/** Cursor's current version, read from its install script. */
export function cursorVersionFromScript(script: string): string | null {
  return /https:\/\/downloads\.cursor\.com\/lab\/([0-9A-Za-z][0-9A-Za-z.\-]*)\//.exec(script)?.[1] ?? null
}

export const installCursorLinux: LinuxBuiltinInstaller = async (deps) => {
  const arch = deps.arch === "arm64" ? "arm64" : "x64"
  deps.log(`curl isn't installed: installing Cursor without it. Reading the current version from ${CURSOR_INSTALL_SCRIPT}...`)
  const version = cursorVersionFromScript(await (await get(deps, CURSOR_INSTALL_SCRIPT)).text())
  if (!version) throw new Error(`couldn't find Cursor's version in ${CURSOR_INSTALL_SCRIPT}`)
  const url = `https://downloads.cursor.com/lab/${version}/linux/${arch}/agent-cli-package.tar.gz`
  deps.log(`Downloading Cursor ${version} (${url})...`)
  const bytes = await httpsBytes(deps.fetch, url)
  deps.log(`Cursor publishes no checksum (its own installer checks none): checked HTTPS only. SHA-256 ${deps.sha256(bytes)}.`)

  const versions = posix.join(deps.home, ".local", "share", "cursor-agent", "versions")
  const stamp = stampOf(deps)
  const tmp = posix.join(versions, `.tmp-${version}-${stamp}`)
  const archive = `${tmp}.tar.gz`
  const final = posix.join(versions, version)
  deps.fs.mkdir(tmp)
  try {
    deps.fs.write(archive, bytes)
    await deps.untar(archive, tmp, 1)
    if (!deps.fs.exists(posix.join(tmp, "cursor-agent"))) throw new Error("no cursor-agent in the Cursor package")
    deps.fs.remove(final)
    deps.fs.rename(tmp, final)
  } catch (err) {
    try { deps.fs.remove(tmp) } catch {}
    throw err
  } finally {
    try { deps.fs.remove(archive) } catch {}
  }
  const bin = posix.join(deps.home, ".local", "bin")
  deps.fs.mkdir(bin)
  for (const name of ["cursor-agent", "agent"]) {
    const link = posix.join(bin, name)
    deps.fs.remove(link)
    deps.fs.symlink(posix.join(final, "cursor-agent"), link)
  }
  deps.log(`Installed ${posix.join(bin, "cursor-agent")} -> ${posix.join(final, "cursor-agent")}.`)
}

interface ReleaseAsset { name?: string; browser_download_url?: string; digest?: string | null }

/** The release asset opencode.ai/install would pick on this Linux machine. */
export function openCodeLinuxAsset(arch: string, hasAvx2: boolean, musl: boolean): string {
  const a = arch === "arm64" ? "arm64" : "x64"
  const baseline = a === "x64" && !hasAvx2 ? "-baseline" : ""
  return `opencode-linux-${a}${baseline}${musl ? "-musl" : ""}.tar.gz`
}

export const installOpenCodeLinux: LinuxBuiltinInstaller = async (deps) => {
  const assetName = openCodeLinuxAsset(deps.arch, deps.hasAvx2(), deps.isMusl())
  deps.log(`curl isn't installed: installing OpenCode without it. Looking up the latest release (${OPENCODE_LATEST_RELEASE_API})...`)
  const release = (await (await get(deps, OPENCODE_LATEST_RELEASE_API, "application/vnd.github+json")).json()) as { tag_name?: string; assets?: ReleaseAsset[] }
  const asset = (release.assets ?? []).find((x) => x.name === assetName)
  if (!asset?.browser_download_url) throw new Error(`the OpenCode release ${release.tag_name ?? "?"} has no ${assetName}`)
  assertOpenCodeAssetUrl(asset.browser_download_url)
  const expected = /^sha256:([0-9a-f]{64})$/i.exec(asset.digest ?? "")?.[1]?.toLowerCase()
  if (!expected) throw new Error(`GitHub lists no SHA-256 digest for ${assetName}; refusing to install an unverified download`)

  deps.log(`Downloading ${assetName} (${release.tag_name ?? "latest"})...`)
  const bytes = await httpsBytes(deps.fetch, asset.browser_download_url)
  const actual = deps.sha256(bytes).toLowerCase()
  if (actual !== expected) throw new Error(`checksum mismatch for ${assetName}: expected ${expected}, got ${actual}; nothing was installed`)
  deps.log(`SHA-256 verified (${actual}).`)

  const dir = posix.join(deps.home, ".opencode", "bin")
  const stamp = stampOf(deps)
  const staging = posix.join(deps.home, ".opencode", `.tmp-${stamp}`)
  const archive = `${staging}.tar.gz`
  deps.fs.mkdir(staging)
  try {
    deps.fs.write(archive, bytes)
    await deps.untar(archive, staging, 0)
    const exe = posix.join(staging, "opencode")
    if (!deps.fs.exists(exe)) throw new Error(`no opencode in ${assetName}`)
    deps.fs.chmod(exe, 0o755)
    deps.fs.mkdir(dir)
    deps.fs.rename(exe, posix.join(dir, "opencode"))
  } finally {
    try { deps.fs.remove(staging) } catch {}
    try { deps.fs.remove(archive) } catch {}
  }
  deps.log(`Installed ${posix.join(dir, "opencode")}.`)
}

export const LINUX_BUILTIN_INSTALLERS: Record<string, LinuxBuiltinInstaller> = {
  "cursor-linux": installCursorLinux,
  "opencode-linux": installOpenCodeLinux,
}

function untar(archive: string, dest: string, stripComponents: number): Promise<void> {
  const args = ["-xzf", archive, "-C", dest, ...(stripComponents > 0 ? [`--strip-components=${stripComponents}`] : [])]
  return new Promise((resolve, reject) => {
    execFile("tar", args, { timeout: 5 * 60_000 }, (err, _out, stderr) => (err ? reject(new Error(`tar failed: ${String(stderr || err)}`)) : resolve()))
  })
}

/** The production deps (everything but the log line sink). */
export function realLinuxBuiltinDeps(home: string): Omit<LinuxBuiltinDeps, "log"> {
  return {
    home,
    arch: process.arch,
    fetch,
    sha256: sha256Hex,
    untar,
    hasAvx2: () => {
      try { return /\bavx2\b/i.test(readFileSync("/proc/cpuinfo", "utf8")) } catch { return true }
    },
    isMusl: () => {
      if (existsSync("/etc/alpine-release")) return true
      try { return readdirSync("/lib").some((n) => n.startsWith("ld-musl-")) } catch { return false }
    },
    fs: {
      exists: (p) => existsSync(p),
      mkdir: (p) => { mkdirSync(p, { recursive: true }) },
      write: (p, b) => writeFileSync(p, b),
      rename: (from, to) => renameSync(from, to),
      remove: (p) => rmSync(p, { recursive: true, force: true }),
      chmod: (p, mode) => chmodSync(p, mode),
      symlink: (target, path) => symlinkSync(target, path),
    },
  }
}
