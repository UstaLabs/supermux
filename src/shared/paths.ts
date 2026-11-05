import { existsSync } from "fs"
import { join, relative, resolve } from "path"
import { localEndpoint } from "../core/local-endpoint"
import { home } from "./home"
import { dirs, paths } from "@supermux/platform"

/**
 * The mux home when MUX_HOME does not name one: `<home>/.mux`. On Windows the home is %USERPROFILE% (Q2), where the
 * broker took $HOME first before, and a POSIX-style shell sets a $HOME of its own: a .mux only under that one
 * ([shellHome]) is kept, with a [note] for the log, so nobody's state moves silently.
 */
export function defaultMuxHome(home: string, shellHome: string | undefined, exists: (path: string) => boolean): { path: string; note?: string } {
  const standard = join(home, ".mux")
  if (shellHome === undefined || exists(standard)) return { path: standard }
  const former = join(shellHome, ".mux")
  if (!exists(former)) return { path: standard }
  return { path: former, note: `using ${former}, the mux home under $HOME; supermux now keeps it at ${standard}: move it there, or set MUX_HOME` }
}

/** The mux home as the environment says now (MUX_HOME, else defaultMuxHome). */
export function muxHomeNow(): { path: string; note?: string } {
  return process.env.MUX_HOME !== undefined ? { path: process.env.MUX_HOME } : defaultMuxHome(home(), dirs.posixShellHome(), existsSync)
}

const muxHome = muxHomeNow()
export const MUX_HOME = muxHome.path
/** Set when MUX_HOME is a .mux kept under a POSIX shell's $HOME on Windows (defaultMuxHome): the broker logs it. */
export const MUX_HOME_NOTE = muxHome.note

/** Session inboxes, one JSONL file per session (inbox spec §1). Not INBOX_DIR: that one holds downloaded attachments. */
export const SESSION_INBOX_DIR = join(MUX_HOME, "session_inboxes")

/** One session's inbox file: `<dir>/<session id>.jsonl`. The store, the preamble and the shell read rules all name it through this. */
export function inboxFilePath(sessionId: string, dir: string = SESSION_INBOX_DIR): string {
  return join(dir, `${sessionId}.jsonl`)
}

/** An agent's working folder: `<mux home>/workspace/<name>`. */
export function agentWorkdir(name: string, muxHome: string = MUX_HOME): string {
  return join(muxHome, "workspace", name)
}

/**
 * Supermux's own folders, which are not user projects: the mux home itself (the home agent runs
 * there) and every agent's folder under it. Path-module based, so it holds for Windows paths too.
 */
export function isMuxInternalPath(path: string, muxHome: string = MUX_HOME): boolean {
  const p = resolve(path)
  if (relative(resolve(muxHome), p) === "") return true
  return paths.isStrictlyInside(p, agentWorkdir("", muxHome))
}

export const STATE_DIR = process.env.MUX_STATE_DIR ?? join(MUX_HOME, "state")

/**
 * A session name as ONE folder name: an agent's home is `<STATE_DIR>/agents/<kind>/<this>`. A `/` or
 * `\` in the name would nest the home or climb out of the agents folder (into ~/.codex, say), so each
 * becomes `-`, and "." / ".." get a `_` in front. An ordinary name maps to itself, so existing
 * sessions' homes don't move.
 */
export function homeDirName(name: string): string {
  const flat = name.replace(/[/\\]/g, "-")
  return flat === "." || flat === ".." ? `_${flat}` : flat
}

export const SOCKETS_DIR = process.env.MUX_SOCKETS_DIR ?? join(STATE_DIR, "sockets")
export const REGISTRY_FILE = join(STATE_DIR, "registry.json")
export const ENV_FILE = join(STATE_DIR, ".env")
export const PID_FILE = join(STATE_DIR, "broker.pid")
export const ACCESS_FILE = join(STATE_DIR, "access.json")
export const COMMANDS_FILE = join(STATE_DIR, "commands.json")
export const DEVICES_FILE = join(STATE_DIR, "devices.json")
export const HOST_KEY_FILE = join(STATE_DIR, "host-key")

export const INBOX_DIR = join(STATE_DIR, "inbox")

// Plugin host (see the plugin-host design spec).
// Plugins live at the MUX_HOME root (alongside domains/personal/state),
// NOT under state/, so they survive state resets and are shared across sessions.
export const PLUGINS_DIR = join(MUX_HOME, "plugins")
export const PLUGINS_FILE = join(MUX_HOME, "plugins.json")

export function socketPathForSession(sessionId: string): string {
  return localEndpoint(sessionId, { socketsDir: SOCKETS_DIR })
}
