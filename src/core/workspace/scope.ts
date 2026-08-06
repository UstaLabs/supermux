/**
 * The key TerminalManager uses to namespace a terminal.
 *
 * TerminalManager takes a `sessionName` argument, but it treats it as an opaque
 * string: a map key and a component of the tmux name. That lets a workspace own
 * terminals with no change to the manager at all — a workspace terminal keys on
 * "w:<workspaceId>".
 *
 * The prefix cannot collide with a real session name: session names are
 * human-readable titles and a leading "w:" is not a shape the namer produces.
 * Existing session terminals keep their exact key, so nothing is orphaned by
 * this change.
 */

const WORKSPACE_PREFIX = "w:"

export type TerminalScope =
  | { kind: "workspace"; id: string }
  | { kind: "session"; id: string }

export function workspaceScope(workspaceId: string): string {
  return WORKSPACE_PREFIX + workspaceId
}

export function parseScope(key: string): TerminalScope {
  return key.startsWith(WORKSPACE_PREFIX)
    ? { kind: "workspace", id: key.slice(WORKSPACE_PREFIX.length) }
    : { kind: "session", id: key }
}
