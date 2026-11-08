// Folders the OS gives a user. The home is the runtime's rule (Q2 of the platform-layer spec): what os.homedir()
// answers in Node and Bun, and Java's user.home on the desktop, %USERPROFILE% on Windows and $HOME elsewhere. It is read
// from the environment at the call, because Bun's os.homedir() misses a HOME set after start on Linux and macOS.
import { homedir } from "node:os"
import { flavor } from "./paths.js"
import { envValue, type CommandEnvironment } from "./proc.js"

export interface HomeOptions {
  /** The OS whose rule applies; the running one by default. */
  platform?: NodeJS.Platform
  /** Where the variables are read; process.env by default. */
  env?: CommandEnvironment
  /** The runtime's own answer (tests); os.homedir by default. */
  homedir?: () => string
}

/**
 * The user's home folder: %USERPROFILE% on Windows, $HOME elsewhere; the runtime's own answer when that is unset. On
 * Windows an empty %USERPROFILE% makes the runtime throw (uv_os_homedir), so its other home variables answer then.
 */
export function home(options: HomeOptions = {}): string {
  const platform = options.platform ?? process.platform
  const env = options.env ?? process.env
  const own = envValue(env, platform === "win32" ? "USERPROFILE" : "HOME", platform)
  if (own) return own
  try {
    return (options.homedir ?? homedir)()
  } catch (error) {
    const drive = envValue(env, "HOMEDRIVE", platform)
    const path = envValue(env, "HOMEPATH", platform)
    if (platform === "win32" && drive && path) return drive + path
    throw error
  }
}

/**
 * On Windows, a $HOME that is not %USERPROFILE%: a POSIX-style shell (MSYS2, Cygwin) sets one of its own, and supermux
 * took $HOME first there before Q2. Undefined elsewhere, where $HOME is the home.
 */
export function posixShellHome(options: HomeOptions = {}): string | undefined {
  const platform = options.platform ?? process.platform
  if (platform !== "win32") return undefined
  const env = options.env ?? process.env
  const shellHome = envValue(env, "HOME", platform)
  if (!shellHome) return undefined
  return flavor(platform).relative(home({ platform, env }), shellHome) === "" ? undefined : shellHome
}

/** The base-folder variables tools read, as set at the call; each undefined when unset or empty. */
export interface BaseDirs {
  /** $XDG_CONFIG_HOME */
  xdgConfigHome?: string
  /** $XDG_DATA_HOME */
  xdgDataHome?: string
  /** $XDG_RUNTIME_DIR: set by a Linux login session (systemd --user needs it) */
  xdgRuntimeDir?: string
  /** %APPDATA%: Windows' roaming application data */
  appData?: string
}

/**
 * The XDG and %APPDATA% variables as set at the call, read the way the OS reads them (any letter case on Windows), for
 * code that applies a tool's own defaults ([xdgConfigHome] and [userConfig] apply the common ones).
 */
export function baseDirs(options: HomeOptions = {}): BaseDirs {
  const platform = options.platform ?? process.platform
  const env = options.env ?? process.env
  const read = (name: string) => envValue(env, name, platform) || undefined
  const found: BaseDirs = {
    xdgConfigHome: read("XDG_CONFIG_HOME"),
    xdgDataHome: read("XDG_DATA_HOME"),
    xdgRuntimeDir: read("XDG_RUNTIME_DIR"),
    appData: read("APPDATA"),
  }
  return Object.fromEntries(Object.entries(found).filter(([, value]) => value !== undefined)) as BaseDirs
}

/** The XDG config folder: $XDG_CONFIG_HOME, else the home's .config. The rule tools that follow XDG keep on every OS. */
export function xdgConfigHome(options: HomeOptions = {}): string {
  const platform = options.platform ?? process.platform
  return baseDirs(options).xdgConfigHome ?? flavor(platform).join(home(options), ".config")
}

/**
 * Where a user's tools keep their settings (cursor-agent among them): %APPDATA% on Windows, else the home's
 * AppData\Roaming; the XDG config folder elsewhere ([xdgConfigHome]).
 */
export function userConfig(options: HomeOptions = {}): string {
  const platform = options.platform ?? process.platform
  if (platform !== "win32") return xdgConfigHome(options)
  return baseDirs(options).appData ?? flavor(platform).join(home(options), "AppData", "Roaming")
}
