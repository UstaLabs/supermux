import { expect, test } from "bun:test"
import { lstatSync, mkdirSync, mkdtempSync, readFileSync, readlinkSync, rmSync, symlinkSync, writeFileSync, type Stats } from "node:fs"
import { tmpdir } from "node:os"
import { isAbsolute, join } from "node:path"
import { Unsupported, files } from "@supermux/platform"

const scratch = () => mkdtempSync(join(tmpdir(), "mux-files-"))
const eperm = () => { throw Object.assign(new Error("EPERM: operation not permitted, symlink"), { code: "EPERM" }) }

test("a folder link is a junction on Windows and a symlink elsewhere; it needs no privilege", () => {
  const dir = scratch()
  mkdirSync(join(dir, "target"))
  writeFileSync(join(dir, "target", "a.txt"), "a")
  files.linkDirectory(join(dir, "target"), join(dir, "link"))
  expect(lstatSync(join(dir, "link")).isSymbolicLink()).toBe(true)
  expect(readFileSync(join(dir, "link", "a.txt"), "utf8")).toBe("a")
  const types: string[] = []
  files.linkDirectory("t", "p", { symlink: (_t, _p, type) => { types.push(type) } })
  expect(types).toEqual(["junction"])
})

test("a file link works where the OS allows it", () => {
  const dir = scratch()
  writeFileSync(join(dir, "target.txt"), "hello")
  files.linkFile(join(dir, "target.txt"), join(dir, "link.txt"))
  expect(lstatSync(join(dir, "link.txt")).isSymbolicLink()).toBe(true)
  expect(readFileSync(join(dir, "link.txt"), "utf8")).toBe("hello")
})

// #37: on Windows a file symlink needs Developer Mode or elevation; a standard user gets EPERM. Typed, so the caller
// falls back on purpose (Q5: copy a small file, or make the account isolated) instead of failing after sign-in.
test("a file link on Windows without the privilege is Unsupported; elsewhere EPERM stays an error", () => {
  let thrown: unknown
  try { files.linkFile("t", "p", { platform: "win32", symlink: eperm }) } catch (e) { thrown = e }
  expect(thrown).toBeInstanceOf(Unsupported)
  expect((thrown as Unsupported).code).toBe("unsupported")
  expect(() => files.linkFile("t", "p", { platform: "linux", symlink: eperm })).toThrow("EPERM")
})

test("whether this process can link files is probed once, in a scratch folder of its own", () => {
  expect(files.canLinkFiles({ scratch: scratch() })).toBe(true)
  let probes = 0
  const failing = { platform: "win32" as const, scratch: scratch(), symlink: () => { probes++; eperm() }, cache: new Map<string, boolean>() }
  expect(files.canLinkFiles(failing)).toBe(false)
  expect(files.canLinkFiles(failing)).toBe(false)
  expect(probes).toBe(1)
})

// The runtime's copy gets links wrong one way or the other: by default every relative link is pointed back into the
// source, and with verbatimSymlinks a relative link out of the source points nowhere. A copy has to stand on its own.
const copies = [
  ["copyTree", (src: string, dest: string, options?: files.LinkOptions) => files.copyTree(src, dest, options)],
  ["copyTreeSync", async (src: string, dest: string, options?: files.LinkOptions) => files.copyTreeSync(src, dest, options)],
] as const

for (const [name, copy] of copies) {
  test(`${name}: a link inside the copied folder points into the copy, kept as written`, async () => {
    const dir = scratch()
    mkdirSync(join(dir, "src", "real"), { recursive: true })
    writeFileSync(join(dir, "src", "real", "a.txt"), "a")
    symlinkSync("real", join(dir, "src", "folder-link"), "dir")
    symlinkSync(join("real", "a.txt"), join(dir, "src", "file-link"), "file")
    await copy(join(dir, "src"), join(dir, "dest"))
    rmSync(join(dir, "src"), { recursive: true, force: true })
    expect(readFileSync(join(dir, "dest", "folder-link", "a.txt"), "utf8")).toBe("a")
    expect(readFileSync(join(dir, "dest", "file-link"), "utf8")).toBe("a")
    // A junction (Windows) has an absolute target; a symlink keeps the relative one.
    expect(readlinkSync(join(dir, "dest", "folder-link"))).toBe(process.platform === "win32" ? join(dir, "dest", "real") : "real")
  })

  test(`${name}: an absolute link into the copied folder is moved over to the copy`, async () => {
    const dir = scratch()
    mkdirSync(join(dir, "src", "real"), { recursive: true })
    writeFileSync(join(dir, "src", "real", "a.txt"), "a")
    symlinkSync(join(dir, "src", "real"), join(dir, "src", "abs"), "dir")
    await copy(join(dir, "src"), join(dir, "dest"))
    expect(readlinkSync(join(dir, "dest", "abs"))).toBe(join(dir, "dest", "real"))
  })

  test(`${name}: a link out of the copied folder still reaches what it pointed at`, async () => {
    const dir = scratch()
    mkdirSync(join(dir, "secrets"))
    writeFileSync(join(dir, "secrets", ".env"), "KEY=1")
    mkdirSync(join(dir, "repo", "src"), { recursive: true })
    symlinkSync(join("..", "..", "secrets", ".env"), join(dir, "repo", "src", ".env"), "file")
    // The copy lands at another depth, where the same relative link would point nowhere.
    await copy(join(dir, "repo", "src"), join(dir, "copies", "deep", "src"))
    expect(readFileSync(join(dir, "copies", "deep", "src", ".env"), "utf8")).toBe("KEY=1")
  })

  test(`${name}: a link given as the thing to copy is copied as a link to where it points`, async () => {
    const dir = scratch()
    mkdirSync(join(dir, "repo"))
    writeFileSync(join(dir, "shared.env"), "KEY=2")
    symlinkSync(join("..", "shared.env"), join(dir, "repo", ".env"), "file")
    await copy(join(dir, "repo", ".env"), join(dir, "worktrees", "w1", ".env"))
    expect(lstatSync(join(dir, "worktrees", "w1", ".env")).isSymbolicLink()).toBe(true)
    expect(readFileSync(join(dir, "worktrees", "w1", ".env"), "utf8")).toBe("KEY=2")
  })

  test(`${name}: copying again over an earlier copy replaces its links, the way cp replaces files`, async () => {
    const dir = scratch()
    mkdirSync(join(dir, "src", "real"), { recursive: true })
    symlinkSync("real", join(dir, "src", "link"), "dir")
    await copy(join(dir, "src"), join(dir, "dest"))
    await copy(join(dir, "src"), join(dir, "dest"))
    expect(readlinkSync(join(dir, "dest", "link"))).toBe(process.platform === "win32" ? join(dir, "dest", "real") : "real")
  })

  // #37: a standard Windows user can make junctions but not file links. Junction targets are absolute.
  test(`${name}: on Windows a folder link is copied as a junction, and a file link the OS refuses as the file`, async () => {
    const dir = scratch()
    mkdirSync(join(dir, "src", "real"), { recursive: true })
    writeFileSync(join(dir, "src", "real", "a.txt"), "a")
    symlinkSync("real", join(dir, "src", "folder-link"), "dir")
    symlinkSync(join("real", "a.txt"), join(dir, "src", "file-link"), "file")
    const made: string[] = []
    const symlink = (target: string, path: string, type: "file" | "dir" | "junction") => {
      if (type !== "junction") eperm()
      made.push(`${type} ${isAbsolute(target) ? "absolute" : "relative"}`)
      symlinkSync(target, path, type)
    }
    await copy(join(dir, "src"), join(dir, "dest"), { platform: "win32", symlink })
    expect(made).toEqual(["junction absolute"])
    expect(readFileSync(join(dir, "dest", "folder-link", "a.txt"), "utf8")).toBe("a")
    expect(lstatSync(join(dir, "dest", "file-link")).isSymbolicLink()).toBe(false)
    expect(readFileSync(join(dir, "dest", "file-link"), "utf8")).toBe("a")
  })
}

test("lstatEntry: what is at a path, never through a link; nothing there is undefined", () => {
  const dir = scratch()
  writeFileSync(join(dir, "file"), "x")
  symlinkSync(join(dir, "file"), join(dir, "link"), "file")
  expect(files.lstatEntry(join(dir, "missing"))).toBeUndefined()
  expect((files.lstatEntry(join(dir, "file")) as Stats).isFile()).toBe(true)
  expect((files.lstatEntry(join(dir, "link")) as Stats).isSymbolicLink()).toBe(true)
})

// §2.2 #7: Bun cannot lstat an AF_UNIX socket file on Windows (EACCES), while a link or a junction it can. The MCP host
// read that as an error and never started again after an unclean exit left its socket behind.
test("lstatEntry: on Windows an entry lstat is refused is an AF_UNIX socket; elsewhere EACCES stays an error", () => {
  const eacces = () => { throw Object.assign(new Error("EACCES: permission denied, lstat"), { code: "EACCES" }) }
  expect(files.lstatEntry("C:\\state\\mcp\\mcp.sock", { platform: "win32", lstat: eacces })).toBe("socket")
  expect(() => files.lstatEntry("/state/mcp/mcp.sock", { platform: "linux", lstat: eacces })).toThrow("EACCES")
})

// Windows keeps a removed file's name until the last handle to it closes; creating that name meanwhile fails with EPERM
// (ERROR_ACCESS_DENIED). The accounts' refresh lock took a stale lock over that way and failed on the lane (run
// 41945381313): such a name is free again in a moment, like a lock someone else holds.
test("nameStillHeld: EPERM or EACCES on Windows; never elsewhere", () => {
  const err = (code: string) => Object.assign(new Error(code), { code })
  expect(files.nameStillHeld(err("EPERM"), "win32")).toBe(true)
  expect(files.nameStillHeld(err("EACCES"), "win32")).toBe(true)
  expect(files.nameStillHeld(err("EEXIST"), "win32")).toBe(false)
  expect(files.nameStillHeld(err("EPERM"), "linux")).toBe(false)
})

// A cloned repo's size came from `du`, which Windows does not have: every repo read 0 bytes there.
test("folderSize: the bytes of the files under a folder, by a walk on Windows and du elsewhere; links are not followed", () => {
  const dir = scratch()
  mkdirSync(join(dir, "repo", "sub"), { recursive: true })
  writeFileSync(join(dir, "repo", "a.bin"), Buffer.alloc(3000))
  writeFileSync(join(dir, "repo", "sub", "b.bin"), Buffer.alloc(5000))
  writeFileSync(join(dir, "outside.bin"), Buffer.alloc(100_000))
  symlinkSync(join(dir, "outside.bin"), join(dir, "repo", "link.bin"), "file")
  expect(files.folderSize(join(dir, "repo"), { platform: "win32" })).toBe(8000)
  const du = files.folderSize(join(dir, "repo"))
  if (process.platform !== "win32") {
    expect(du).toBeGreaterThanOrEqual(8000)
    expect(du).toBeLessThan(100_000)
  }
  expect(files.folderSize(join(dir, "missing"), { platform: "win32" })).toBe(0)
})
