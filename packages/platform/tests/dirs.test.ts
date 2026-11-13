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

// #29 env-dirs: feature code read XDG_CONFIG_HOME, XDG_DATA_HOME, XDG_RUNTIME_DIR and APPDATA itself, in one letter case,
// and applied a default of its own here and there. The platform layer reads them, as Windows does: in any letter case.
test("baseDirs are the XDG and %APPDATA% variables as set now, any letter case on Windows, undefined when unset or empty", () => {
  const env = { XDG_CONFIG_HOME: "/cfg", XDG_DATA_HOME: "", XDG_RUNTIME_DIR: "/run/user/1000", APPDATA: "/ad" }
  expect(dirs.baseDirs({ platform: "linux", env })).toEqual({ xdgConfigHome: "/cfg", xdgRuntimeDir: "/run/user/1000", appData: "/ad" })
  expect(dirs.baseDirs({ platform: "win32", env: { AppData: "C:\\Users\\ada\\AppData\\Roaming" } })).toEqual({ appData: "C:\\Users\\ada\\AppData\\Roaming" })
  expect(dirs.baseDirs({ platform: "linux", env: { appdata: "/x" } })).toEqual({})
})

test("xdgConfigHome is $XDG_CONFIG_HOME, else the home's .config, on every OS (tools that follow XDG)", () => {
  expect(dirs.xdgConfigHome({ platform: "linux", env: { HOME: "/home/ada", XDG_CONFIG_HOME: "/cfg" } })).toBe("/cfg")
  expect(dirs.xdgConfigHome({ platform: "darwin", env: { HOME: "/Users/ada" } })).toBe("/Users/ada/.config")
  expect(dirs.xdgConfigHome({ platform: "win32", env: { USERPROFILE: "C:\\Users\\ada" } })).toBe("C:\\Users\\ada\\.config")
})

test("userConfig is %APPDATA% on Windows (else the home's AppData\\Roaming) and the XDG config folder elsewhere", () => {
  expect(dirs.userConfig({ platform: "win32", env: { USERPROFILE: "C:\\Users\\ada", APPDATA: "D:\\Roaming" } })).toBe("D:\\Roaming")
  expect(dirs.userConfig({ platform: "win32", env: { USERPROFILE: "C:\\Users\\ada" } })).toBe("C:\\Users\\ada\\AppData\\Roaming")
  expect(dirs.userConfig({ platform: "linux", env: { HOME: "/home/ada", XDG_CONFIG_HOME: "/cfg", APPDATA: "/ad" } })).toBe("/cfg")
  expect(dirs.userConfig({ platform: "darwin", env: { HOME: "/Users/ada" } })).toBe("/Users/ada/.config")
})
