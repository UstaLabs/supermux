// Per-user installs on Windows without an installer program, so without a UAC prompt: download
// over HTTPS, verify, unpack into a staging dir beside the target, swap it into place by rename,
// and put a dir on the user PATH in the registry (once). MinGit (core/git/mingit.ts) and the agent
// CLIs that publish no PowerShell installer (core/agents/install-builtin.ts) share these pieces.
//
// Everything that touches the machine is a parameter, so the callers' tests run on any OS.
import { createHash } from "crypto"
import { execFile } from "child_process"
import { existsSync, mkdirSync, renameSync, rmSync, writeFileSync } from "fs"
import { win32 as winPath } from "path"

/** The file operations a per-user install needs. Paths are Windows paths. */
export interface UserInstallFs {
  exists: (p: string) => boolean
  mkdir: (p: string) => void
  write: (p: string, b: Uint8Array) => void
  rename: (from: string, to: string) => void
  remove: (p: string) => void
}

export const realUserInstallFs: UserInstallFs = {
  exists: (p) => existsSync(p),
  mkdir: (p) => { mkdirSync(p, { recursive: true }) },
  write: (p, b) => writeFileSync(p, b),
  rename: (from, to) => renameSync(from, to),
  remove: (p) => rmSync(p, { recursive: true, force: true }),
}

export function sha256Hex(bytes: Uint8Array): string {
  return createHash("sha256").update(bytes).digest("hex")
}

/**
 * Pure: [current] (a `;`-separated PATH, maybe null) with [dir] appended, or null when it is
 * already there (case-insensitive, trailing backslash ignored, %VARS% compared as written and
 * expanded with [env]).
 */
export function pathWithDir(current: string | null, dir: string, env: Record<string, string | undefined> = {}): string | null {
  const norm = (d: string) => {
    const expanded = d.replace(/%([^%]+)%/g, (whole, name: string) => {
      const key = Object.keys(env).find((k) => k.toLowerCase() === name.toLowerCase())
      return key ? env[key] ?? whole : whole
    })
    return expanded.trim().replace(/[\\/]+$/, "").toLowerCase()
  }
  const parts = (current ?? "").split(";").filter((p) => p.trim() !== "")
  if (parts.some((p) => norm(p) === norm(dir))) return null
  return [...parts, dir].join(";")
}

/** Reads and writes the user's raw (unexpanded) registry PATH. */
export interface UserPathStore {
  /** The user's raw registry PATH, null when unset. */
  readUserPath: () => Promise<string | null>
  /** Write the user's registry PATH (and tell running programs about it). */
  writeUserPath: (value: string) => Promise<void>
}

/** Add [dir] to the user's registry PATH unless it is already there. True when it wrote. */
export async function addToUserPath(store: UserPathStore, dir: string, env: Record<string, string | undefined> = process.env): Promise<boolean> {
  const next = pathWithDir(await store.readUserPath(), dir, env)
  if (next === null) return false
  await store.writeUserPath(next)
  return true
}

export class StageError extends Error {
  constructor(message: string) {
    super(message)
    this.name = "StageError"
  }
}

/**
 * Unpack [zipBytes] into a fresh staging dir beside [root], check it with [verify] (a message
 * means "refuse"), then swap it into place by rename: a crash leaves either the old tree or the
 * new one, never half of one. The zip and the staging dir never outlive the call. Throws
 * [StageError] when anything fails; [root] is then untouched.
 */
export async function stageZipInto(opts: {
  root: string
  zipBytes: Uint8Array
  /** Basenames beside [root]: `<stagingPrefix>.tmp-<stamp>`, `<stagingPrefix>.old-<stamp>`, `<zipPrefix>-<stamp>.zip`. */
  stagingPrefix: string
  zipPrefix: string
  extract: (zip: string, dest: string) => Promise<void>
  /** Inspect the unpacked staging dir: a message refuses the archive. */
  verify: (staging: string) => string | null
  fs: UserInstallFs
  stamp: string
}): Promise<void> {
  const { fs, root, stamp } = opts
  const parent = winPath.dirname(root)
  const staging = winPath.join(parent, `${opts.stagingPrefix}.tmp-${stamp}`)
  const zip = winPath.join(parent, `${opts.zipPrefix}-${stamp}.zip`)
  try {
    fs.mkdir(staging)
    fs.write(zip, opts.zipBytes)
    await opts.extract(zip, staging)
    const refusal = opts.verify(staging)
    if (refusal) throw new Error(refusal)
    if (fs.exists(root)) {
      const old = winPath.join(parent, `${opts.stagingPrefix}.old-${stamp}`)
      fs.rename(root, old)
      fs.rename(staging, root)
      try { fs.remove(old) } catch {}
    } else {
      fs.rename(staging, root)
    }
  } catch (err) {
    try { fs.remove(staging) } catch {}
    throw new StageError(err instanceof Error ? err.message : String(err))
  } finally {
    try { fs.remove(zip) } catch {}
  }
}

/** GET [url] (redirects followed) as bytes; throws on a non-2xx answer. */
export async function downloadBytes(url: string, fetchFn: typeof fetch = fetch, init: RequestInit = {}): Promise<Uint8Array> {
  const res = await fetchFn(url, { redirect: "follow", ...init })
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`)
  return new Uint8Array(await res.arrayBuffer())
}

// ── the real Windows pieces ─────────────────────────────────────────────────────────────────

/** Windows PowerShell 5.1 by its fixed path (no PATH lookup, so nothing on PATH can stand in). */
export function windowsPowerShellPath(env: Record<string, string | undefined> = process.env): string {
  const key = Object.keys(env).find((k) => k.toLowerCase() === "systemroot")
  const root = (key && env[key]) || "C:\\Windows"
  return winPath.join(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
}

export function powershell(script: string, extraEnv: Record<string, string> = {}): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(
      windowsPowerShellPath(),
      ["-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script],
      { timeout: 5 * 60_000, windowsHide: true, env: { ...process.env, ...extraEnv }, maxBuffer: 1 << 20 },
      (err, stdout, stderr) => (err ? reject(new Error(String(stderr || err))) : resolve(String(stdout))),
    )
  })
}

/** Paths go through the environment, never into the script text: no quoting to get wrong. */
const EXTRACT = "Expand-Archive -LiteralPath $env:MUX_UNZIP_ZIP -DestinationPath $env:MUX_UNZIP_DEST -Force"

/** Unzip [zip] into [dest] with Expand-Archive. */
export async function extractZip(zip: string, dest: string): Promise<void> {
  await powershell(EXTRACT, { MUX_UNZIP_ZIP: zip, MUX_UNZIP_DEST: dest })
}

// The raw value (DoNotExpandEnvironmentNames) so %USERPROFILE%-style entries stay variables, and
// written back as REG_EXPAND_SZ. Environment.SetEnvironmentVariable would write REG_SZ and break
// them. WM_SETTINGCHANGE "Environment" makes Explorer (new terminals) pick the change up.
const READ_USER_PATH =
  "$k = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment'); " +
  "if ($k) { $v = $k.GetValue('Path', $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames); if ($v) { [Console]::Out.Write($v) } }"
const WRITE_USER_PATH =
  "$k = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Environment'); " +
  "$k.SetValue('Path', $env:MUX_NEW_USER_PATH, [Microsoft.Win32.RegistryValueKind]::ExpandString); $k.Close(); " +
  "Add-Type -Namespace MuxEnv -Name Native -MemberDefinition '[DllImport(\"user32.dll\", CharSet = CharSet.Unicode)] public static extern System.IntPtr SendMessageTimeout(System.IntPtr h, uint m, System.UIntPtr w, string l, uint f, uint t, out System.UIntPtr r);'; " +
  "$r = [System.UIntPtr]::Zero; [void][MuxEnv.Native]::SendMessageTimeout([System.IntPtr]0xffff, 0x1A, [System.UIntPtr]::Zero, 'Environment', 2, 5000, [ref]$r)"

/** The real user registry PATH for this Windows user. */
export const realUserPathStore: UserPathStore = {
  readUserPath: async () => (await powershell(READ_USER_PATH)) || null,
  writeUserPath: async (value) => { await powershell(WRITE_USER_PATH, { MUX_NEW_USER_PATH: value }) },
}

/** `%LOCALAPPDATA%`, or its default under the profile. */
export function localAppData(env: Record<string, string | undefined> = process.env): string {
  return env.LOCALAPPDATA ?? winPath.join(env.USERPROFILE ?? "C:\\", "AppData", "Local")
}
