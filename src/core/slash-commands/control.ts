import type { SlashCommand } from "./types"

const ctl = (name: string, description: string, action: SlashCommand["action"]): SlashCommand => ({
  id: `control:${name}`,
  family: "control",
  name,
  sigil: "/",
  description,
  action,
})

/** The session-management control commands for the current session. */
export function controlCommands(session: { muted: boolean }): SlashCommand[] {
  const mute = session.muted
    ? ctl("unmute", "Unmute this session", { kind: "mute", muted: false })
    : ctl("mute", "Mute notifications from this session", { kind: "mute", muted: true })
  return [
    ctl("spawn", "Start a new agent session", { kind: "spawn" }),
    ctl("model", "Switch this session's model", { kind: "model" }),
    ctl("rename", "Rename this session", { kind: "rename" }),
    mute,
    ctl("stop", "Stop the running agent (keeps the session)", { kind: "stop" }),
    ctl("kill", "Kill this session", { kind: "kill" }),
  ]
}
