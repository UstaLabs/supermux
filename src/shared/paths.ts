import { join } from "path"
import { home } from "./home"

export const MUX_HOME = process.env.MUX_HOME ?? join(home(), ".mux")

export const STATE_DIR = process.env.MUX_STATE_DIR ?? join(MUX_HOME, "state")

export const SOCKETS_DIR = process.env.MUX_SOCKETS_DIR ?? join(STATE_DIR, "sockets")
export const REGISTRY_FILE = join(STATE_DIR, "registry.json")
export const ENV_FILE = join(STATE_DIR, ".env")
export const PID_FILE = join(STATE_DIR, "broker.pid")
export const ACCESS_FILE = join(STATE_DIR, "access.json")
export const COMMANDS_FILE = join(STATE_DIR, "commands.json")
export const DEVICES_FILE = join(STATE_DIR, "devices.json")

export const INBOX_DIR = join(STATE_DIR, "inbox")

// Plugin host (see the plugin-host design spec).
// Plugins live at the MUX_HOME root (alongside domains/personal/state),
// NOT under state/, so they survive state resets and are shared across sessions.
export const PLUGINS_DIR = join(MUX_HOME, "plugins")
export const PLUGINS_FILE = join(MUX_HOME, "plugins.json")

export function socketPathForSession(sessionId: string): string {
  return join(SOCKETS_DIR, `${sessionId}.sock`)
}
