// Agent CLIs whose vendor publishes no Windows installer script: the broker installs them itself,
// per user, with no admin rights (so no UAC prompt), no node and no git.
//
//   • OpenCode: the GitHub release's `opencode-windows-<arch>.zip` (one `opencode.exe`), verified
//     against the SHA-256 digest GitHub publishes for the asset, unpacked to
//     %LOCALAPPDATA%\Programs\opencode.
//   • Grok: `https://x.ai/cli/grok-<ver>-windows-<arch>.exe`, version from `x.ai/cli/stable`, to
//     %USERPROFILE%\.grok\bin\grok.exe. x.ai publishes NO checksum for it, so the only checks are
//     HTTPS, a plausible size and the PE "MZ" header — and the install log says so.
//
// Both put their dir on the user PATH in the registry (de-duplicated), the way MinGit does; the
// broker's own PATH already carries both dirs (bin-dirs.ts), so detection needs no restart.
import { win32 as winPath } from "path"
import {
  addToUserPath, extractZip, localAppData, realUserInstallFs, realUserPathStore, sha256Hex, stageZipInto,
  type UserInstallFs, type UserPathStore,
} from "../windows/user-install"

export interface BuiltinInstallDeps extends UserPathStore {
  /** The broker's environment (LOCALAPPDATA, USERPROFILE). */
  env: Record<string, string | undefined>
  /** `process.arch`: "x64" | "arm64" | … */
  arch: string
  fetch: typeof fetch
  sha256: (bytes: Uint8Array) => string
  /** Unzip [zip] into the (new, empty) directory [dest]. */
  extract: (zip: string, dest: string) => Promise<void>
  fs: UserInstallFs
  /** One line into the install job's log. */
  log: (line: string) => void
  now?: () => number
  pid?: number
}

export type BuiltinInstaller = (deps: BuiltinInstallDeps) => Promise<void>

export const OPENCODE_LATEST_RELEASE = "https://api.github.com/repos/anomalyco/opencode/releases/latest"
export const GROK_STABLE_URL = "https://x.ai/cli/stable"
/** A real grok.exe is tens of MB; an error page or a truncated download is far below this. */
export const GROK_MIN_BYTES = 1024 * 1024

const USER_AGENT = "supermux-agent-installer"

function profile(env: Record<string, string | undefined>): string {
  return env.USERPROFILE ?? winPath.join("C:\\Users", env.USERNAME ?? "Default")
}

export function openCodeWindowsDir(env: Record<string, string | undefined>): string {
  return winPath.join(localAppData(env), "Programs", "opencode")
}

export function grokWindowsDir(env: Record<string, string | undefined>): string {
  return winPath.join(profile(env), ".grok", "bin")
}

function stampOf(deps: BuiltinInstallDeps): string {
  return `${deps.pid ?? process.pid}-${deps.now?.() ?? Date.now()}`
}

async function getBytes(deps: BuiltinInstallDeps, url: string): Promise<Uint8Array> {
  const res = await deps.fetch(url, { redirect: "follow", headers: { "User-Agent": USER_AGENT } })
  if (!res.ok) throw new Error(`download failed: HTTP ${res.status} for ${url}`)
  return new Uint8Array(await res.arrayBuffer())
}

async function addDirToUserPath(deps: BuiltinInstallDeps, dir: string): Promise<void> {
  const wrote = await addToUserPath(deps, dir, deps.env)
  deps.log(wrote ? `Added ${dir} to your PATH.` : `${dir} is already on your PATH.`)
}

interface ReleaseAsset { name?: string; browser_download_url?: string; digest?: string | null }

/** OpenCode for Windows from its latest GitHub release, verified against the asset's digest. */
export const installOpenCodeWindows: BuiltinInstaller = async (deps) => {
  const assetName = `opencode-windows-${deps.arch === "arm64" ? "arm64" : "x64"}.zip`
  deps.log(`Looking up the latest OpenCode release (${OPENCODE_LATEST_RELEASE})…`)
  const res = await deps.fetch(OPENCODE_LATEST_RELEASE, {
    redirect: "follow",
    headers: { Accept: "application/vnd.github+json", "User-Agent": USER_AGENT },
  })
  if (!res.ok) throw new Error(`couldn't read the latest OpenCode release: HTTP ${res.status}`)
  const release = (await res.json()) as { tag_name?: string; assets?: ReleaseAsset[] }
  const asset = (release.assets ?? []).find((a) => a.name === assetName)
  if (!asset?.browser_download_url) throw new Error(`the OpenCode release ${release.tag_name ?? "?"} has no ${assetName}`)
  const expected = /^sha256:([0-9a-f]{64})$/i.exec(asset.digest ?? "")?.[1]?.toLowerCase()
  if (!expected) throw new Error(`GitHub lists no SHA-256 digest for ${assetName}; refusing to install an unverified download`)

  deps.log(`Downloading ${assetName} (${release.tag_name ?? "latest"})…`)
  const bytes = await getBytes(deps, asset.browser_download_url)
  const actual = deps.sha256(bytes).toLowerCase()
  if (actual !== expected) throw new Error(`checksum mismatch for ${assetName}: expected ${expected}, got ${actual}; nothing was installed`)
  deps.log(`SHA-256 verified (${actual}).`)

  const root = openCodeWindowsDir(deps.env)
  await stageZipInto({
    root,
    zipBytes: bytes,
    stagingPrefix: "opencode",
    zipPrefix: "opencode",
    extract: deps.extract,
    verify: (staging) => (deps.fs.exists(winPath.join(staging, "opencode.exe")) ? null : `no opencode.exe in ${assetName}`),
    fs: deps.fs,
    stamp: stampOf(deps),
  })
  deps.log(`Installed ${winPath.join(root, "opencode.exe")}.`)
  await addDirToUserPath(deps, root)
}

/** Grok for Windows from x.ai's stable channel. No checksum exists upstream (see the header). */
export const installGrokWindows: BuiltinInstaller = async (deps) => {
  const res = await deps.fetch(GROK_STABLE_URL, { redirect: "follow", headers: { "User-Agent": USER_AGENT } })
  if (!res.ok) throw new Error(`couldn't read the Grok version: HTTP ${res.status}`)
  const version = (await res.text()).trim()
  if (!/^\d+\.\d+\.\d+[0-9A-Za-z.+-]*$/.test(version)) throw new Error(`unexpected Grok version from ${GROK_STABLE_URL}: ${JSON.stringify(version.slice(0, 40))}`)
  const url = `https://x.ai/cli/grok-${version}-windows-${deps.arch === "arm64" ? "aarch64" : "x86_64"}.exe`

  deps.log(`Downloading Grok ${version} (${url})…`)
  const bytes = await getBytes(deps, url)
  if (bytes.length < GROK_MIN_BYTES) throw new Error(`the Grok download is only ${bytes.length} bytes; refusing it`)
  if (bytes[0] !== 0x4d || bytes[1] !== 0x5a) throw new Error("the Grok download is not a Windows program (no MZ header); refusing it")
  deps.log(`x.ai publishes no checksum for the Windows build: checked only HTTPS, the size (${bytes.length} bytes) and the MZ header. SHA-256 ${deps.sha256(bytes)}.`)

  const dir = grokWindowsDir(deps.env)
  const exe = winPath.join(dir, "grok.exe")
  const tmp = `${exe}.tmp-${stampOf(deps)}`
  deps.fs.mkdir(dir)
  try {
    deps.fs.write(tmp, bytes)
    deps.fs.rename(tmp, exe)
  } catch (err) {
    try { deps.fs.remove(tmp) } catch {}
    throw new Error(`couldn't write ${exe}: ${err instanceof Error ? err.message : String(err)}`)
  }
  deps.log(`Installed ${exe}.`)
  await addDirToUserPath(deps, dir)
}

/** Builtin installers by recipe script name. */
export const BUILTIN_INSTALLERS: Record<string, BuiltinInstaller> = {
  "opencode-windows": installOpenCodeWindows,
  "grok-windows": installGrokWindows,
}

/** The production deps (everything but the log line sink). */
export function realBuiltinDeps(env: Record<string, string | undefined> = process.env): Omit<BuiltinInstallDeps, "log"> {
  return {
    env,
    arch: process.arch,
    fetch,
    sha256: sha256Hex,
    extract: extractZip,
    fs: realUserInstallFs,
    ...realUserPathStore,
  }
}
