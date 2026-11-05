import { expect, test } from "bun:test"
import { homedir } from "node:os"
import { dirs } from "@supermux/platform"

// Q2: the runtime's rule (what os.homedir() answers in Node and Bun, and Java's user.home): %USERPROFILE% on Windows,
// $HOME elsewhere. Read from the environment at the call: Bun's os.homedir() misses a HOME set after start on Linux and
// macOS, which test isolation and overrides rely on.
test("home is %USERPROFILE% on Windows and $HOME elsewhere, read from the environment given", () => {
  const env = { HOME: "C:\\msys64\\home\\ada", USERPROFILE: "C:\\Users\\ada" }
  expect(dirs.home({ platform: "win32", env })).toBe("C:\\Users\\ada")
  expect(dirs.home({ platform: "win32", env: { UserProfile: "C:\\Users\\ada" } })).toBe("C:\\Users\\ada")
  expect(dirs.home({ platform: "linux", env: { HOME: "/home/ada", USERPROFILE: "/x" } })).toBe("/home/ada")
})

test("an unset or empty home is the runtime's own answer", () => {
  expect(dirs.home({ platform: "linux", env: { HOME: "" } })).toBe(homedir())
  expect(dirs.home({ platform: "darwin", env: {} })).toBe(homedir())
})

// Before Q2 the broker took $HOME first on Windows too. A POSIX-style shell there (MSYS2, Cygwin) sets one of its own.
test("posixShellHome is a $HOME on Windows other than %USERPROFILE%; there is none elsewhere", () => {
  expect(dirs.posixShellHome({ platform: "win32", env: { HOME: "C:\\msys64\\home\\ada", USERPROFILE: "C:\\Users\\ada" } })).toBe("C:\\msys64\\home\\ada")
  expect(dirs.posixShellHome({ platform: "win32", env: { HOME: "c:\\users\\ADA\\", USERPROFILE: "C:\\Users\\ada" } })).toBeUndefined()
  expect(dirs.posixShellHome({ platform: "win32", env: { USERPROFILE: "C:\\Users\\ada" } })).toBeUndefined()
  expect(dirs.posixShellHome({ platform: "linux", env: { HOME: "/home/ada" } })).toBeUndefined()
})

// On Windows an EMPTY %USERPROFILE% makes the runtime's own os.homedir() throw (ENOENT, uv_os_homedir; seen on the lane,
// run 41950063909), while an unset one does not. The home must still be answered: Windows' other home variables.
test("home on Windows falls back to %HOMEDRIVE%%HOMEPATH% when the runtime has no answer either", () => {
  const none = () => { throw Object.assign(new Error("ENOENT: no such file or directory, uv_os_homedir"), { code: "ENOENT" }) }
  const env = { USERPROFILE: "", HOMEDRIVE: "C:", HOMEPATH: "\\Users\\ada" }
  expect(dirs.home({ platform: "win32", env, homedir: none })).toBe("C:\\Users\\ada")
  expect(() => dirs.home({ platform: "linux", env: { HOME: "" }, homedir: none })).toThrow("uv_os_homedir")
})
