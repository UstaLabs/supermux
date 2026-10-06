import { describe, expect, test } from "bun:test"
import { createHash } from "crypto"
import { MINGIT, MinGitError, installMinGit, minGitDirs, pathWithDir, type MinGitDeps } from "./mingit"
import { GitInstaller, WINGET_INSTALL_GIT, type GitInstallStatus, type GitRequirement } from "./requirement"

const LAD = "C:\\Users\\t\\AppData\\Local"

/** An in-memory Windows: files are keys, dirs are prefixes. */
function fakeWindows(opts: { bytes?: Uint8Array; downloadFails?: boolean; userPath?: string | null; archiveHasGit?: boolean } = {}) {
  const files = new Set<string>()
  const ops: string[] = []
  let userPath: string | null = opts.userPath === undefined ? "%USERPROFILE%\\bin;C:\\Tools" : opts.userPath
  const writes: string[] = []
  const has = (p: string) => [...files].some((f) => f === p || f.startsWith(p + "\\"))
  const deps: MinGitDeps = {
    localAppData: LAD,
    download: async () => {
      ops.push("download")
      if (opts.downloadFails) throw new Error("ECONNRESET")
      return opts.bytes ?? new Uint8Array([1, 2, 3])
    },
    sha256: (b) => (b === (opts.bytes ?? null) ? createHash("sha256").update(b).digest("hex") : MINGIT.sha256),
    extract: async (_zip, dest) => {
      ops.push("extract")
      if (opts.archiveHasGit !== false) files.add(`${dest}\\cmd\\git.exe`)
      files.add(`${dest}\\mingw64\\bin\\git.exe`)
    },
    readUserPath: async () => userPath,
    writeUserPath: async (v) => { writes.push(v); userPath = v },
    fs: {
      exists: has,
      mkdir: (p) => { ops.push(`mkdir ${p}`) },
      write: (p) => { files.add(p) },
      rename: (from, to) => {
        ops.push(`rename ${from} -> ${to}`)
        for (const f of [...files]) if (f === from || f.startsWith(from + "\\")) { files.delete(f); files.add(to + f.slice(from.length)) }
      },
      remove: (p) => { for (const f of [...files]) if (f === p || f.startsWith(p + "\\")) files.delete(f) },
    },
    now: () => 42,
    pid: 7,
  }
  return { deps, files, ops, writes, get userPath() { return userPath } }
}

describe("installMinGit", () => {
  test("happy path: verified, unpacked beside the target, swapped in, cmd on the user PATH", async () => {
    const w = fakeWindows()
    const r = await installMinGit(w.deps, { USERPROFILE: "C:\\Users\\t" })
    const { root, cmd } = minGitDirs(LAD)
    expect(r.cmdDir).toBe(cmd)
    expect(w.files.has(`${root}\\cmd\\git.exe`)).toBe(true)
    expect([...w.files].some((f) => f.includes("Git.tmp-") || f.endsWith(".zip"))).toBe(false)
    expect(w.ops).toContain(`rename ${LAD}\\Programs\\Git.tmp-7-42 -> ${root}`)
    expect(w.writes).toEqual([`%USERPROFILE%\\bin;C:\\Tools;${cmd}`])
  })

  test("a checksum mismatch refuses before anything is extracted", async () => {
    const w = fakeWindows({ bytes: new Uint8Array([9, 9]) })
    const err = await installMinGit(w.deps).catch((e) => e)
    expect(err).toBeInstanceOf(MinGitError)
    expect((err as MinGitError).kind).toBe("sha_mismatch")
    expect(w.ops).not.toContain("extract")
    expect(w.writes).toEqual([])
  })

  test("an archive without cmd\\git.exe is refused and the staging dir is removed", async () => {
    const w = fakeWindows({ archiveHasGit: false })
    const err = await installMinGit(w.deps).catch((e) => e)
    expect((err as MinGitError).kind).toBe("extract")
    expect(w.files.size).toBe(0)
  })

  test("an existing install is replaced by rename, and PATH isn't touched when cmd is already on it", async () => {
    const { root, cmd } = minGitDirs(LAD)
    const w = fakeWindows({ userPath: `C:\\Tools;${cmd.toUpperCase()}\\` })
    w.files.add(`${root}\\cmd\\git.exe.old-version`)
    await installMinGit(w.deps)
    expect(w.ops).toContain(`rename ${root} -> ${LAD}\\Programs\\Git.old-7-42`)
    expect(w.files.has(`${root}\\cmd\\git.exe.old-version`)).toBe(false)
    expect(w.writes).toEqual([])
  })
})

describe("pathWithDir", () => {
  test("appends once; duplicates are case-, slash- and %VAR%-insensitive", () => {
    expect(pathWithDir(null, "C:\\g\\cmd")).toBe("C:\\g\\cmd")
    expect(pathWithDir("A;;B;", "C:\\g\\cmd")).toBe("A;B;C:\\g\\cmd")
    expect(pathWithDir("A;c:\\G\\CMD\\", "C:\\g\\cmd")).toBeNull()
    expect(pathWithDir("%LOCALAPPDATA%\\Programs\\Git\\cmd", `${LAD}\\Programs\\Git\\cmd`, { LOCALAPPDATA: LAD })).toBeNull()
  })
})

describe("GitInstaller on Windows with MinGit", () => {
  function installer(minGit: () => Promise<unknown>, winget = true) {
    const spawned: string[][] = []
    const exits: Array<(code: number | null) => void> = []
    const statuses: GitInstallStatus[] = []
    let installed = 0
    const req: GitRequirement = { ok: false, install: "mingit", hint: "h" }
    const inst = new GitInstaller({
      platform: "win32",
      requirement: () => req,
      hasWinget: () => winget,
      spawn: (cmd) => { spawned.push(cmd); return { onExit: (cb) => { exits.push(cb) } } },
      onStatus: (s) => statuses.push(s),
      installMinGit: minGit,
      onInstalled: () => { installed++ },
    })
    return { inst, spawned, exits, statuses, installed: () => installed }
  }

  test("MinGit is the one-click install: no winget, re-check on success, one at a time", async () => {
    let finish!: () => void
    const t = installer(() => new Promise<void>((r) => { finish = r }))
    expect(t.inst.install().body).toEqual({ ok: true })
    expect(t.inst.install().body).toEqual({ ok: true, inProgress: true })
    finish()
    await Bun.sleep(0)
    expect(t.spawned).toEqual([])
    expect(t.statuses).toEqual([{ installing: true }, { installing: false }])
    expect(t.installed()).toBe(1)
  })

  test("a failed MinGit download falls back to winget and says so", async () => {
    const t = installer(() => Promise.reject(new MinGitError("download", "couldn't download MinGit: ECONNRESET")))
    t.inst.install()
    await Bun.sleep(0)
    expect(t.spawned).toEqual([WINGET_INSTALL_GIT])
    expect(t.statuses[1]!.installing).toBe(true)
    expect(t.statuses[1]!.installNote).toContain("winget")
    expect(t.inst.install().body).toEqual({ ok: true, inProgress: true })
    t.exits[0]!(0)
    expect(t.statuses[2]).toEqual({ installing: false })
    expect(t.installed()).toBe(1)
  })

  test("without winget the fallback is the download page, reported as an error", async () => {
    const t = installer(() => Promise.reject(new Error("sha")), false)
    t.inst.install()
    await Bun.sleep(0)
    expect(t.spawned[0]![0]).toBe("explorer.exe")
    expect(t.statuses[1]!.installError).toContain("download page")
  })
})
