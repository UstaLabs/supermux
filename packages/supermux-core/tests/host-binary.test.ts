import { afterEach, beforeEach, describe, expect, test } from "bun:test"
import { chmodSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { trustedHostBinary } from "../src/environment/host-binary.js"

// A test cannot make a root-owned file, so the user running it stands in for root: `trustedUid`.
const posix = process.platform !== "win32"
const me = posix ? process.getuid!() : 0

describe.skipIf(!posix)("trustedHostBinary", () => {
  let dir: string
  beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "host-binary-")); chmodSync(dir, 0o755) })
  afterEach(() => { rmSync(dir, { recursive: true, force: true }) })

  /** `<dir>/<folder>/<name>`, a folder of mode `folderMode` and a file of mode `fileMode`. */
  function program(folder: string, name: string, fileMode = 0o755, folderMode = 0o755): string {
    const base = join(dir, folder)
    mkdirSync(base, { recursive: true })
    chmodSync(base, folderMode)
    const path = join(base, name)
    writeFileSync(path, "#!/bin/sh\n")
    chmodSync(path, fileMode)
    return path
  }
  const find = (name: string, ...folders: string[]) => trustedHostBinary(name, { dirs: folders.map(f => join(dir, f)), trustedUid: me })

  test("a regular executable that only its owner can write, in a folder that is the same, is found by its path as named", () => {
    const path = program("bin", "cat")
    expect(find("cat", "bin")).toBe(path)
  })

  test("the first folder that holds a trusted one wins; an untrusted copy in an earlier folder is skipped", () => {
    program("planted", "cat", 0o777)
    const real = program("real", "cat")
    expect(find("cat", "planted", "real")).toBe(real)
    expect(find("cat", "missing", "real")).toBe(real)
  })

  test("anything an agent could have planted or swapped is refused", () => {
    // not owned by the trusted uid
    program("a", "cat")
    expect(trustedHostBinary("cat", { dirs: [join(dir, "a")], trustedUid: me + 1 })).toBeUndefined()
    // group- or other-writable file
    program("b", "cat", 0o775)
    program("c", "cat", 0o757)
    program("d", "cat", 0o722)
    expect(find("cat", "b")).toBeUndefined()
    expect(find("cat", "c")).toBeUndefined()
    expect(find("cat", "d")).toBeUndefined()
    // not executable
    program("e", "cat", 0o644)
    expect(find("cat", "e")).toBeUndefined()
    // a folder others or the group can write (they could replace the program)
    program("f", "cat", 0o755, 0o775)
    program("g", "cat", 0o755, 0o777)
    expect(find("cat", "f")).toBeUndefined()
    expect(find("cat", "g")).toBeUndefined()
    // not a regular file
    mkdirSync(join(dir, "h", "cat"), { recursive: true })
    expect(find("cat", "h")).toBeUndefined()
    // missing
    expect(find("cat", "nowhere")).toBeUndefined()
  })

  test("a link is judged by what it leads to: the file, the folder the link is in and the folder the file really lives in", () => {
    const target = program("targets", "real-cat")
    mkdirSync(join(dir, "links"), { recursive: true })
    chmodSync(join(dir, "links"), 0o755)
    symlinkSync(target, join(dir, "links", "cat"))
    expect(find("cat", "links")).toBe(join(dir, "links", "cat")) // as named, not the target
    // the target's folder can be written by others: the real program can be swapped
    chmodSync(join(dir, "targets"), 0o777)
    expect(find("cat", "links")).toBeUndefined()
    chmodSync(join(dir, "targets"), 0o755)
    expect(find("cat", "links")).toBe(join(dir, "links", "cat"))
    // the link's folder can be written by others: the link can be swapped
    chmodSync(join(dir, "links"), 0o777)
    expect(find("cat", "links")).toBeUndefined()
    chmodSync(join(dir, "links"), 0o755)
    // the target is not sealed
    chmodSync(target, 0o777)
    expect(find("cat", "links")).toBeUndefined()
  })

  test("only absolute, canonical folders count: a relative entry, an empty one and a non-canonical one are skipped", () => {
    program("bin", "cat")
    const path = join(dir, "bin")
    for (const folder of ["", ".", "bin", "./bin", `${dir}/bin/`, `${dir}/x/../bin`, `${dir}//bin`]) {
      expect(trustedHostBinary("cat", { dirs: [folder], trustedUid: me })).toBeUndefined()
    }
    // even with the current folder on the list, the entry that is not a canonical absolute path is passed over
    expect(trustedHostBinary("cat", { dirs: ["", ".", `${path}/`, path], trustedUid: me })).toBe(join(path, "cat"))
  })

  test("a name is a bare file name", () => {
    program("bin", "cat")
    for (const name of ["", ".", "..", "../cat", "bin/cat", "/bin/cat", "ca t", "cat\n"]) expect(find(name, "bin")).toBeUndefined()
  })

  test("by default the system folders come first and PATH after, and only what root owns is trusted", () => {
    // Whatever this machine has, the answer is an absolute path named like the program, or nothing at all.
    for (const name of ["cat", "head", "tail", "wc"]) {
      const path = trustedHostBinary(name)
      if (path !== undefined) expect(path).toMatch(new RegExp(`^/.*/${name}$`))
    }
    expect(trustedHostBinary("no-such-program-xyz")).toBeUndefined()
    // A user-owned folder on PATH never wins: with the test user standing in as nobody's root (uid 0 is not me), nothing here is root's.
    program("userbin", "cat")
    const saved = process.env.PATH
    process.env.PATH = `${join(dir, "userbin")}:${saved ?? ""}`
    try {
      const found = trustedHostBinary("cat")
      expect(found === undefined || !found.startsWith(dir)).toBe(true)
    } finally { process.env.PATH = saved }
  })
})
