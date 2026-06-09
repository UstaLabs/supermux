// scripts/dump-ws-frames.ts
// Emits one canonical JSON example per ServerFrame variant. Run after any
// protocol change; the Kotlin ContractTest then proves the Kotlin types still
// parse them. Output dir is passed so it can match wherever the JVM test loads.
import { mkdirSync, writeFileSync } from "fs"
import { join } from "path"
const repoRoot = join(import.meta.dir, "..")
const dir = process.argv[2] ?? join(repoRoot, "apps/shared/src/jvmTest/resources/frames")
mkdirSync(dir, { recursive: true })
const frames: Record<string, unknown> = {
  // sessions array mirrors SessionSnapshot in src/channels/web/index.ts (name/workdir/mute/connected/agent/model — NO id, NO status)
  snapshot: {
    type: "snapshot",
    sessions: [
      { name: "editor", workdir: "/home/user/projects/project-api", mute: false, connected: true, agent: "claude", model: "opus-4.8" },
    ],
  },
  // session_added mirrors broadcastToAll call at main.ts line ~1093 (most complete call site)
  session_added: {
    type: "session_added",
    session: { id: "550e8400-e29b-41d4-a716-446655440000", name: "editor", workdir: "/home/user/project", mute: false, connected: true, agent: "claude", model: "claude-opus-4-5" },
  },
  // session_removed mirrors main.ts line ~572: { type: "session_removed", id: s.id }
  session_removed: { type: "session_removed", id: "550e8400-e29b-41d4-a716-446655440000" },
  // agent_state mirrors main.ts line ~1504: { type, session, phase, tool, since, workingSince }
  agent_state: { type: "agent_state", session: "550e8400-e29b-41d4-a716-446655440000", phase: "working", tool: "Bash", since: 1717200000000, workingSince: 1717200005000 },
  // agent_error mirrors main.ts line ~398: { type, session: sessionName, errorType, errorMessage }
  agent_error: { type: "agent_error", session: "editor", errorType: "StopFailure", errorMessage: "boom" },
  // message_append mirrors broker chat protocol: LogEntry shape
  message_append: {
    type: "message_append",
    session: "550e8400-e29b-41d4-a716-446655440000",
    entry: { id: "in:1", ts: "2026-06-01T00:00:00Z", direction: "inbound", channel: "web", chat_id: "web", message_id: "m1", text: "hi" },
  },
  // activity_append mirrors broker chat protocol: ActivityEvent shape
  activity_append: {
    type: "activity_append",
    session: "550e8400-e29b-41d4-a716-446655440000",
    event: { ts: "2026-06-01T00:00:00Z", kind: "tool", tool: "Bash", title: "Bash: ls", phase: "started", seq: 1, callId: "c1" },
  },
  // commands_changed mirrors broker slash-command protocol: SlashCommand shape
  commands_changed: {
    type: "commands_changed",
    session: "550e8400-e29b-41d4-a716-446655440000",
    resolved: true,
    commands: [
      { id: "cmd-agent-1", family: "agent", name: "compact", sigil: "/", description: "Compact conversation history", insertText: "/compact " },
      { id: "cmd-ctrl-1", family: "control", name: "mute", sigil: "/", description: "Mute this session", action: { kind: "mute", muted: false } },
    ],
  },
}
for (const [name, f] of Object.entries(frames)) writeFileSync(`${dir}/${name}.json`, JSON.stringify(f, null, 2))
console.log(`wrote ${Object.keys(frames).length} fixtures to ${dir}`)
