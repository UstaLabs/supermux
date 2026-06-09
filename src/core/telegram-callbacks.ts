export type CallbackAction = {
  kind: "switch" | "kill" | "rename" | "mute" | "unmute"
  sessionId: string
}

export function parseCallback(data: string): CallbackAction | null {
  const colonIdx = data.indexOf(":")
  if (colonIdx < 0) return null
  const kind = data.slice(0, colonIdx) as CallbackAction["kind"]
  const sessionId = data.slice(colonIdx + 1)
  if (!["switch", "kill", "rename", "mute", "unmute"].includes(kind)) return null
  return { kind, sessionId }
}
