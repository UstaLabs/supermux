// The Windows one-click git: MinGit, Git for Windows' official portable build, unpacked for THIS
// user under %LOCALAPPDATA%\Programs\Git — no installer, no UAC prompt (the Git for Windows
// installer asks for admin even with winget's --scope user). Pinned like every other download:
// exact URL + SHA-256, verified before anything is extracted.
//
// Steps (each injectable, so the tests run on any OS): download → verify → extract into a temp dir
// next to the target → check `cmd\git.exe` → swap into place by rename → add `<target>\cmd` to the
// user PATH in the registry (once). The broker's re-check (requirement.ts) already looks in the
// registry PATH and in `%LOCALAPPDATA%\Programs\Git\cmd`, so git is picked up without a restart.
import { win32 as winPath } from "path"
import {
  StageError, addToUserPath, downloadBytes, extractZip, localAppData, realUserInstallFs, realUserPathStore, sha256Hex,
  stageZipInto, type UserInstallFs,
} from "../windows/user-install"

export { pathWithDir } from "../windows/user-install"

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
  fs?: UserInstallFs
  now?: () => number
  pid?: number
}

/** Where MinGit goes, and the dir that belongs on PATH. */
export function minGitDirs(localAppData: string): { root: string; cmd: string } {
  const root = winPath.join(localAppData, "Programs", "Git")
  return { root, cmd: winPath.join(root, "cmd") }
}

/** Download, verify, extract, swap into place and put `cmd` on the user PATH. Throws [MinGitError]. */
export async function installMinGit(deps: MinGitDeps, env: Record<string, string | undefined> = process.env): Promise<{ cmdDir: string }> {
  const fs = deps.fs ?? realUserInstallFs
  const { root, cmd } = minGitDirs(deps.localAppData)
  let bytes: Uint8Array
  try {
    bytes = await deps.download(MINGIT.url)
  } catch (err) {
    throw new MinGitError("download", `couldn't download MinGit: ${String(err)}`)
  }
  const sum = deps.sha256(bytes)
  if (sum !== MINGIT.sha256) throw new MinGitError("sha_mismatch", `MinGit checksum mismatch (got ${sum})`)

  try {
    await stageZipInto({
      root,
      zipBytes: bytes,
      stagingPrefix: "Git",
      zipPrefix: "MinGit",
      extract: deps.extract,
      verify: (staging) => (fs.exists(winPath.join(staging, "cmd", "git.exe")) ? null : "no cmd\\git.exe in the archive"),
      fs,
      stamp: `${deps.pid ?? process.pid}-${deps.now?.() ?? Date.now()}`,
    })
  } catch (err) {
    throw new MinGitError("extract", `couldn't unpack MinGit: ${err instanceof StageError ? err.message : String(err)}`)
  }

  try {
    await addToUserPath(deps, cmd, env)
  } catch (err) {
    throw new MinGitError("path", `couldn't add git to the user PATH: ${String(err)}`)
  }
  return { cmdDir: cmd }
}

/** The production [MinGitDeps] for this Windows user. */
export function realMinGitDeps(env: Record<string, string | undefined> = process.env): MinGitDeps {
  return {
    localAppData: localAppData(env),
    download: (url) => downloadBytes(url),
    sha256: sha256Hex,
    extract: extractZip,
    ...realUserPathStore,
  }
}
