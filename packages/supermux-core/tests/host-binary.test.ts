import { afterEach, beforeEach, describe, expect, test } from "bun:test"
import { chmodSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readdirSync, rmSync, statSync, symlinkSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { nodeHostFs, resolveHostBinary, trustedHostBinary, type HostFs } from "../src/environment/host-binary.js"

type Entry = { type: "dir" | "file" | "link"; uid?: number; mode?: number; target?: string }

/** A little file system, by absolute path. Everything is root's, with a sensible mode, unless an entry says otherwise. */
function fakeFs(entries: Record<string, Entry>): HostFs {
  const find = (path: string): Entry => {
    const entry = entries[path]
    if (!entry) throw Object.assign(new Error(`ENOENT: ${path}`), { code: "ENOENT" })
    return entry
  }
  return {
    lstat(path) {
      const entry = find(path)
      return { uid: entry.uid ?? 0, mode: entry.mode ?? (entry.type === "link" ? 0o777 : 0o755), isFile: () => entry.type === "file", isDirectory: () => entry.type === "dir", isSymbolicLink: () => entry.type === "link" }
    },
    readlink(path) {
      const entry = find(path)
      if (entry.type !== "link") throw new Error(`EINVAL: ${path}`)
      return entry.target!
    },
    canExecute: path => ((find(path).mode ?? 0o755) & 0o111) !== 0,
  }
}

const USER = 1000
const dirs = (...paths: string[]): Record<string, Entry> => Object.fromEntries(paths.map(path => [path, { type: "dir" } as Entry]))
/** A plain system: /usr/bin/cat is a program. */
const SYSTEM: Record<string, Entry> = { ...dirs("/", "/usr", "/usr/bin"), "/usr/bin/cat": { type: "file" } }
const find = (entries: Record<string, Entry>, name = "cat", folders?: string[]) => resolveHostBinary(name, fakeFs(entries), folders)

describe("resolveHostBinary on a file system where ownership can be set", () => {
  test("a program in a system folder, behind nothing but root's folders, is found by the path it is named with", () => {
    expect(find(SYSTEM)).toBe("/usr/bin/cat")
    expect(find(SYSTEM, "head")).toBeUndefined()
  })

  test("this machine's shape: /usr/bin/cat is a link up and over (../lib/cargo/bin/coreutils/cat), root's all the way", () => {
    const entries = { ...dirs("/", "/usr", "/usr/bin", "/usr/lib", "/usr/lib/cargo", "/usr/lib/cargo/bin", "/usr/lib/cargo/bin/coreutils"),
      "/usr/bin/cat": { type: "link", target: "../lib/cargo/bin/coreutils/cat" } as Entry, "/usr/lib/cargo/bin/coreutils/cat": { type: "file" } as Entry }
    expect(find(entries)).toBe("/usr/bin/cat")
  })

  test("merged /usr: /bin is root's link to usr/bin, and an absolute link (busybox) is walked from /", () => {
    const merged = { ...SYSTEM, "/bin": { type: "link", target: "usr/bin" } as Entry }
    expect(find(merged, "cat", ["/bin"])).toBe("/bin/cat")
    const busybox = { ...dirs("/", "/usr", "/usr/bin", "/bin"), "/bin/busybox": { type: "file" } as Entry, "/usr/bin/head": { type: "link", target: "/bin/busybox" } as Entry }
    expect(find(busybox, "head")).toBe("/usr/bin/head")
  })

  test("NixOS: /run/current-system/sw/bin, through root's links into /nix/store, a sticky folder that group nixbld can write", () => {
    const nix: Record<string, Entry> = {
      ...dirs("/", "/run", "/nix"),
      "/nix/store": { type: "dir", mode: 0o1775 }, // root:nixbld, sticky: build users add entries, none can rename or remove root's
      "/run/current-system": { type: "link", target: "/nix/store/aaa-system" },
      "/nix/store/aaa-system": { type: "dir", mode: 0o555 },
      "/nix/store/aaa-system/sw": { type: "link", target: "/nix/store/bbb-path" },
      "/nix/store/bbb-path": { type: "dir", mode: 0o555 },
      "/nix/store/bbb-path/bin": { type: "dir", mode: 0o555 },
      "/nix/store/bbb-path/bin/cat": { type: "link", target: "/nix/store/ccc-coreutils/bin/cat" },
      "/nix/store/ccc-coreutils": { type: "dir", mode: 0o555 },
      "/nix/store/ccc-coreutils/bin": { type: "dir", mode: 0o555 },
      "/nix/store/ccc-coreutils/bin/cat": { type: "file", mode: 0o555 },
    }
    expect(find(nix)).toBe("/run/current-system/sw/bin/cat") // /usr/bin and /bin do not exist: the third folder answers
    // ... but the same store with a user's entry in it is no store: the sticky exception only covers root's entries.
    expect(find({ ...nix, "/nix/store/ccc-coreutils": { type: "dir", mode: 0o555, uid: USER } })).toBeUndefined()
    // ... and a single-user Nix, where the store belongs to the user, is refused.
    expect(find({ ...nix, "/nix/store": { type: "dir", mode: 0o1775, uid: USER } })).toBeUndefined()
  })

  test("the first system folder with a program wins", () => {
    expect(find({ ...SYSTEM, ...dirs("/bin"), "/bin/cat": { type: "file" } })).toBe("/usr/bin/cat")
    expect(find({ ...dirs("/", "/bin"), "/bin/cat": { type: "file" } })).toBe("/bin/cat")
  })

  test("a link only its owner can repoint is refused where it stands, before it is followed (the user's own profile-bin -> /usr/bin)", () => {
    const base = { ...SYSTEM, ...dirs("/home", "/home/u", "/home/u/.cache", "/opt") }
    // The demonstrated one: the user's link in the user's folders, passed as a folder to search.
    const owned = { ...base, "/home/u/.cache/profile-bin": { type: "link", target: "/usr/bin", uid: USER } as Entry }
    expect(find(owned, "cat", ["/home/u/.cache/profile-bin"])).toBeUndefined()
    // The same link inside a root-owned, closed folder: nothing else is wrong, so it is the link's owner that refuses it.
    const inRoot = { ...base, "/opt/profile-bin": { type: "link", target: "/usr/bin", uid: USER } as Entry }
    expect(find(inRoot, "cat", ["/opt/profile-bin"])).toBeUndefined()
    // Root's own link to the same place is fine.
    expect(find({ ...base, "/opt/profile-bin": { type: "link", target: "/usr/bin" } as Entry }, "cat", ["/opt/profile-bin"])).toBe("/opt/profile-bin/cat")
  })

  test("a folder on the way that group or others can write is refused (they could swap what is in it), a sticky one only for root's entries", () => {
    for (const mode of [0o775, 0o757, 0o777, 0o722, 0o702]) {
      expect(find({ ...SYSTEM, "/usr/bin": { type: "dir", mode } })).toBeUndefined()
      expect(find({ ...SYSTEM, "/usr": { type: "dir", mode } })).toBeUndefined()
      expect(find({ ...SYSTEM, "/": { type: "dir", mode } })).toBeUndefined()
    }
    // sticky and open: root's entries stay root's
    expect(find({ ...SYSTEM, "/usr/bin": { type: "dir", mode: 0o1777 } })).toBe("/usr/bin/cat")
    // ... a folder or a program that is not root's does not
    expect(find({ ...SYSTEM, "/usr/bin": { type: "dir", mode: 0o1777, uid: USER } })).toBeUndefined()
    expect(find({ ...SYSTEM, "/usr/bin/cat": { type: "file", uid: USER } })).toBeUndefined()
    expect(find({ ...SYSTEM, "/usr": { type: "dir", uid: USER } })).toBeUndefined()
    expect(find({ ...SYSTEM, "/": { type: "dir", uid: USER } })).toBeUndefined()
    // `/` is the one folder never checked as somebody's entry: a sticky one that is not root's must not pass on its stickiness.
    expect(find({ ...SYSTEM, "/": { type: "dir", uid: USER, mode: 0o1777 } })).toBeUndefined()
    expect(find({ ...SYSTEM, "/": { type: "dir", mode: 0o1777 } })).toBe("/usr/bin/cat")
  })

  test("a link that leads out of root's folders is refused, however it is spelled", () => {
    const out = { ...SYSTEM, ...dirs("/home", "/home/u", "/home/u/evil"), "/home/u": { type: "dir", uid: USER } as Entry, "/home/u/evil/cat": { type: "file" } as Entry }
    expect(find({ ...out, "/usr/bin/cat": { type: "link", target: "/home/u/evil/cat" } })).toBeUndefined() // absolute
    expect(find({ ...out, "/usr/bin/cat": { type: "link", target: "../../home/u/evil/cat" } })).toBeUndefined() // relative
    expect(find({ ...out, "/usr/bin/cat": { type: "link", target: "/usr/bin/../../home/u/evil/cat" } })).toBeUndefined() // through ..
    // A chain: the first link is fine, the one it leads to is not.
    expect(find({ ...out, "/usr/bin/cat": { type: "link", target: "second" }, "/usr/bin/second": { type: "link", target: "/home/u/evil/cat" } })).toBeUndefined()
  })

  test("'..' after a link is the folder above where the link leads, not a textual edit of the path", () => {
    // /usr/bin/cat -> ../../elsewhere/farm/../lib/cat, and /elsewhere/farm is a link to /trusted/dir:
    // the kernel ends in /trusted/lib/cat; editing the text would end in /elsewhere/lib/cat.
    const base = { ...dirs("/", "/usr", "/usr/bin", "/elsewhere", "/trusted", "/trusted/dir", "/trusted/lib"),
      "/usr/bin/cat": { type: "link", target: "../../elsewhere/farm/../lib/cat" } as Entry, "/elsewhere/farm": { type: "link", target: "/trusted/dir" } as Entry }
    expect(find({ ...base, "/trusted/lib/cat": { type: "file" } })).toBe("/usr/bin/cat")
    // The program the text would name is the user's: it must not be what is checked, or run.
    expect(find({ ...base, ...dirs("/elsewhere/lib"), "/elsewhere/lib/cat": { type: "file", uid: USER } })).toBeUndefined() // nothing at /trusted/lib/cat
    expect(find({ ...base, ...dirs("/elsewhere/lib"), "/elsewhere/lib/cat": { type: "file", uid: USER }, "/trusted/lib/cat": { type: "file" } })).toBe("/usr/bin/cat")
    // And a '..' that leaves the checked tree is walked there: the user's folder above a root-owned one is not root's.
    const escape = { ...dirs("/", "/usr", "/usr/bin", "/home", "/home/u", "/home/u/sub"), "/home/u": { type: "dir", uid: USER } as Entry,
      "/usr/bin/cat": { type: "link", target: "/home/u/sub/../cat" } as Entry, "/home/u/cat": { type: "file" } as Entry }
    expect(find(escape)).toBeUndefined()
  })

  test("loops, dangling links and too many links are refused; 40 links are fine, 41 are not", () => {
    expect(find({ ...SYSTEM, "/usr/bin/cat": { type: "link", target: "loop" }, "/usr/bin/loop": { type: "link", target: "cat" } })).toBeUndefined()
    expect(find({ ...SYSTEM, "/usr/bin/cat": { type: "link", target: "nowhere" } })).toBeUndefined()
    const chain = (length: number): Record<string, Entry> => {
      const entries: Record<string, Entry> = { ...dirs("/", "/usr", "/usr/bin"), "/usr/bin/real": { type: "file" } }
      for (let i = 0; i < length; i++) entries[`/usr/bin/${i === 0 ? "cat" : `l${i}`}`] = { type: "link", target: i === length - 1 ? "real" : `l${i + 1}` }
      return entries
    }
    expect(find(chain(40))).toBe("/usr/bin/cat")
    expect(find(chain(41))).toBeUndefined()
  })

  test("the end has to be a regular executable file that is root's and that group and others cannot write", () => {
    for (const mode of [0o775, 0o757, 0o722, 0o777, 0o2775]) expect(find({ ...SYSTEM, "/usr/bin/cat": { type: "file", mode } })).toBeUndefined()
    for (const mode of [0o644, 0o600, 0o444]) expect(find({ ...SYSTEM, "/usr/bin/cat": { type: "file", mode } })).toBeUndefined() // not executable
    expect(find({ ...SYSTEM, "/usr/bin/cat": { type: "file", mode: 0o555 } })).toBe("/usr/bin/cat")
    expect(find({ ...SYSTEM, "/usr/bin/cat": { type: "file", mode: 0o4755 } })).toBe("/usr/bin/cat") // setuid is not writable
    expect(find({ ...SYSTEM, "/usr/bin/cat": { type: "dir" } })).toBeUndefined()
    expect(find({ ...SYSTEM, "/usr/bin/cat": { type: "link", target: "." } })).toBeUndefined() // a link to a folder
    expect(find({ ...SYSTEM, "/usr/bin/cat": { type: "link", target: "/usr/bin/cat/.." } })).toBeUndefined()
  })

  test("only the system folders are searched, and only canonical absolute ones are accepted as the list", () => {
    const entries = { ...SYSTEM, ...dirs("/opt", "/opt/bin"), "/opt/bin/tool": { type: "file" } as Entry }
    expect(find(entries, "tool")).toBeUndefined() // /opt/bin is not a system folder
    expect(find(entries, "tool", ["/opt/bin"])).toBe("/opt/bin/tool")
    for (const folder of ["", ".", "usr/bin", "./usr/bin", "/usr/bin/", "/usr//bin", "/usr/x/../bin"]) expect(find(SYSTEM, "cat", [folder])).toBeUndefined()
  })

  test("a name is a bare file name", () => {
    for (const name of ["", ".", "..", "../cat", "usr/bin/cat", "/usr/bin/cat", "ca t", "cat\n", "c*t"]) expect(find(SYSTEM, name)).toBeUndefined()
  })
})

describe.skipIf(process.platform === "win32")("trustedHostBinary on this machine's real file system", () => {
  const me = process.getuid!()
  let dir: string
  beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "host-binary-")); chmodSync(dir, 0o755) })
  afterEach(() => { rmSync(dir, { recursive: true, force: true }) })

  /** Does this machine's own /usr/bin/<name> lead to a program that only root controls? (Then the answer must be found.) */
  const rootsOwn = (name: string): boolean => {
    try {
      return existsSync(`/usr/bin/${name}`) && ["/", "/usr", "/usr/bin"].every(path => lstatSync(path).uid === 0 && (lstatSync(path).mode & 0o022) === 0) && statSync(`/usr/bin/${name}`).uid === 0 && (statSync(`/usr/bin/${name}`).mode & 0o022) === 0
    } catch { return false }
  }

  test("/usr/bin still resolves: the system's own cat, head, tail and wc are found, by the path in a system folder", () => {
    for (const name of ["cat", "head", "tail", "wc"]) {
      const path = trustedHostBinary(name)
      expect(path === undefined || /^(\/usr\/bin|\/bin|\/run\/current-system\/sw\/bin)\//.test(path)).toBe(true)
      if (rootsOwn(name)) expect(path).toBe(`/usr/bin/${name}`)
    }
  })

  test.skipIf(me === 0)("a user-owned symlink to /usr/bin is refused, though it leads straight to the real program (the demonstrated profile-bin)", () => {
    const link = join(dir, "profile-bin")
    symlinkSync("/usr/bin", link)
    // Not vacuous: the real folder itself is accepted on a machine where root owns it.
    if (rootsOwn("cat")) expect(resolveHostBinary("cat", nodeHostFs, ["/usr/bin"])).toBe("/usr/bin/cat")
    expect(resolveHostBinary("cat", nodeHostFs, [link])).toBeUndefined()
    // A user-owned folder that holds a copy of the real program is refused as well.
    mkdirSync(join(dir, "copy"))
    chmodSync(join(dir, "copy"), 0o755)
    writeFileSync(join(dir, "copy", "cat"), "#!/bin/sh\n")
    chmodSync(join(dir, "copy", "cat"), 0o755)
    expect(resolveHostBinary("cat", nodeHostFs, [join(dir, "copy")])).toBeUndefined()
  })

  test("PATH is not consulted, even for a folder that only root controls: a program there is not found by its name", () => {
    // A real root-controlled folder outside the system bin folders (/usr/sbin, say) holding a program found nowhere in them.
    const outside = ["/usr/sbin", "/sbin", "/usr/local/bin", "/usr/local/sbin"].flatMap(folder => {
      try { return readdirSync(folder).map(name => [folder, name] as const) } catch { return [] }
    }).find(([folder, name]) => /^[A-Za-z0-9._+-]+$/.test(name) && !existsSync(`/usr/bin/${name}`) && !existsSync(`/bin/${name}`) && resolveHostBinary(name, nodeHostFs, [folder]) !== undefined)
    if (!outside) return // this machine has no such program: nothing to show
    const [folder, name] = outside
    const saved = process.env.PATH
    try {
      process.env.PATH = `${folder}:${saved ?? ""}`
      // The walk itself would accept it (so it is the system-folders-only rule that refuses it) ...
      expect(resolveHostBinary(name, nodeHostFs, [folder])).toBe(join(folder, name))
      // ... and PATH does not widen the search.
      expect(trustedHostBinary(name)).toBeUndefined()
    } finally { process.env.PATH = saved }
  })

  test("a folder planted on PATH is ignored: PATH is not consulted at all", () => {
    mkdirSync(join(dir, "planted"))
    chmodSync(join(dir, "planted"), 0o755)
    for (const name of ["cat", "only-on-path-xyz"]) {
      writeFileSync(join(dir, "planted", name), "#!/bin/sh\n")
      chmodSync(join(dir, "planted", name), 0o755)
    }
    const saved = process.env.PATH
    const before = trustedHostBinary("cat")
    try {
      process.env.PATH = `${join(dir, "planted")}:${saved ?? ""}`
      expect(trustedHostBinary("cat")).toBe(before)
      expect(before === undefined || !before.startsWith(dir)).toBe(true)
      expect(trustedHostBinary("only-on-path-xyz")).toBeUndefined()
      process.env.PATH = join(dir, "planted") // nothing but the planted folder
      expect(trustedHostBinary("cat")).toBe(before)
    } finally { process.env.PATH = saved }
  })
})
