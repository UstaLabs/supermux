// The Windows one-click git: MinGit, Git for Windows' official portable build, unpacked for THIS
// user under %LOCALAPPDATA%\Programs\Git — no installer, no UAC prompt (the Git for Windows
// installer asks for admin even with winget's --scope user). Pinned like every other download:
// exact URL + SHA-256, verified before anything is extracted.
//
// Steps (each injectable, so the tests run on any OS): download → verify → extract into a temp dir
// next to the target → check `cmd\git.exe` → swap into place by rename → add `<target>\cmd` to the
// user PATH in the registry (once). The broker's re-check (requirement.ts) already looks in the
// registry PATH and in `%LOCALAPPDATA%\Programs\Git\cmd`, so git is picked up without a restart.
import { createHash } from "crypto"
import { execFile } from "child_process"
import { existsSync, mkdirSync, renameSync, rmSync, writeFileSync } from "fs"
import { win32 as winPath } from "path"

/** The pinned MinGit (git-for-windows v2.56.0.windows.1, `MinGit-2.56.0-64-bit.zip`). */
export const MINGIT = {
  version: "2.56.0",
  url: "https://github.com/git-for-windows/git/releases/download/v2.56.0.windows.1/MinGit-2.56.0-64-bit.zip",
  sha256: "064b440ff870ed5198527e8f3a92cdf5bd2fd0fedf5e718af95e3fdaddeff718",
} as const

export class MinGitError extends Error {
  constructor(readonly kind: "download" | "sha_mismatch" | "extract" | "path", message: string) {
    super(message)
    this.name = "MinGitError"
  }
}

export interface MinGitDeps {
  /** `%LOCALAPPDATA%`. */
  localAppData: string
  download: (url: string) => Promise<Uint8Array>
  sha256: (bytes: Uint8Array) => string
  /** Unzip [zip] into the (new, empty) directory [dest]. */
  extract: (zip: string, dest: string) => Promise<void>
  /** The user's raw (unexpanded) registry PATH, null when unset. */
  readUserPath: () => Promise<string | null>
  /** Write the user's registry PATH (and tell running programs about it). */
  writeUserPath: (value: string) => Promise<void>
  fs?: {
    exists: (p: string) => boolean
    mkdir: (p: string) => void
    write: (p: string, b: Uint8Array) => void
    rename: (from: string, to: string) => void
    remove: (p: string) => void
  }
  now?: () => number
  pid?: number
}

const realFs = {
  exists: (p: string) => existsSync(p),
  mkdir: (p: string) => { mkdirSync(p, { recursive: true }) },
  write: (p: string, b: Uint8Array) => writeFileSync(p, b),
  rename: (from: string, to: string) => renameSync(from, to),
  remove: (p: string) => rmSync(p, { recursive: true, force: true }),
}

/** Where MinGit goes, and the dir that belongs on PATH. */
export function minGitDirs(localAppData: string): { root: string; cmd: string } {
  const root = winPath.join(localAppData, "Programs", "Git")
  return { root, cmd: winPath.join(root, "cmd") }
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

/** Download, verify, extract, swap into place and put `cmd` on the user PATH. Throws [MinGitError]. */
export async function installMinGit(deps: MinGitDeps, env: Record<string, string | undefined> = process.env): Promise<{ cmdDir: string }> {
  const fs = deps.fs ?? realFs
  const { root, cmd } = minGitDirs(deps.localAppData)
  let bytes: Uint8Array
  try {
    bytes = await deps.download(MINGIT.url)
  } catch (err) {
    throw new MinGitError("download", `couldn't download MinGit: ${String(err)}`)
  }
  const sum = deps.sha256(bytes)
  if (sum !== MINGIT.sha256) throw new MinGitError("sha_mismatch", `MinGit checksum mismatch (got ${sum})`)

  const stamp = `${deps.pid ?? process.pid}-${deps.now?.() ?? Date.now()}`
  const parent = winPath.dirname(root)
  const staging = winPath.join(parent, `Git.tmp-${stamp}`)
  const zip = winPath.join(parent, `MinGit-${stamp}.zip`)
  try {
    fs.mkdir(staging)
    fs.write(zip, bytes)
    await deps.extract(zip, staging)
    if (!fs.exists(winPath.join(staging, "cmd", "git.exe"))) throw new Error("no cmd\\git.exe in the archive")
    // Swap by rename: a crash leaves either the old tree or the new one, never half of one.
    if (fs.exists(root)) {
      const old = winPath.join(parent, `Git.old-${stamp}`)
      fs.rename(root, old)
      fs.rename(staging, root)
      try { fs.remove(old) } catch {}
    } else {
      fs.rename(staging, root)
    }
  } catch (err) {
    try { fs.remove(staging) } catch {}
    throw new MinGitError("extract", `couldn't unpack MinGit: ${String(err)}`)
  } finally {
    try { fs.remove(zip) } catch {}
  }

  try {
    const next = pathWithDir(await deps.readUserPath(), cmd, env)
    if (next !== null) await deps.writeUserPath(next)
  } catch (err) {
    throw new MinGitError("path", `couldn't add git to the user PATH: ${String(err)}`)
  }
  return { cmdDir: cmd }
}

// ── the real Windows pieces ─────────────────────────────────────────────────────────────────

function powershell(script: string, extraEnv: Record<string, string> = {}): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(
      "powershell.exe",
      ["-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script],
      { timeout: 5 * 60_000, windowsHide: true, env: { ...process.env, ...extraEnv }, maxBuffer: 1 << 20 },
      (err, stdout, stderr) => (err ? reject(new Error(String(stderr || err))) : resolve(String(stdout))),
    )
  })
}

/** Paths go through the environment, never into the script text: no quoting to get wrong. */
const EXTRACT = "Expand-Archive -LiteralPath $env:MUX_MINGIT_ZIP -DestinationPath $env:MUX_MINGIT_DEST -Force"

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

/** The production [MinGitDeps] for this Windows user. */
export function realMinGitDeps(env: Record<string, string | undefined> = process.env): MinGitDeps {
  const localAppData = env.LOCALAPPDATA ?? winPath.join(env.USERPROFILE ?? "C:\\", "AppData", "Local")
  return {
    localAppData,
    download: async (url) => {
      const res = await fetch(url, { redirect: "follow" })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      return new Uint8Array(await res.arrayBuffer())
    },
    sha256: (b) => createHash("sha256").update(b).digest("hex"),
    extract: async (zip, dest) => { await powershell(EXTRACT, { MUX_MINGIT_ZIP: zip, MUX_MINGIT_DEST: dest }) },
    readUserPath: async () => (await powershell(READ_USER_PATH)) || null,
    writeUserPath: async (value) => { await powershell(WRITE_USER_PATH, { MUX_NEW_USER_PATH: value }) },
  }
}
