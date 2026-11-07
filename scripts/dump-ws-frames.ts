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
  // Card #76: live text, as LiveReplies (src/channels/web/live-replies.ts) writes it to apps that sent
  // subscribe.streams. `at` is the offset of `text` in the stream; `kind` is only present for thinking.
  reply_stream: { type: "reply_stream", session: "550e8400-e29b-41d4-a716-446655440000", stream: "k3v9q2a8d1f0", at: 0, text: "Streaming replies show " },
  reply_stream_thinking: { type: "reply_stream", session: "550e8400-e29b-41d4-a716-446655440000", stream: "t7c1aa04be92", kind: "thinking", at: 12, text: " the table." },
  reply_stream_end: { type: "reply_stream_end", session: "550e8400-e29b-41d4-a716-446655440000", stream: "k3v9q2a8d1f0" },
  // The stored reply / thinking that takes the live text's place carries the same stream id.
  message_append_live: {
    type: "message_append",
    session: "550e8400-e29b-41d4-a716-446655440000",
    entry: { id: "out:web:9f1c", ts: "2026-11-05T09:30:00.000Z", direction: "outbound", channel: "web", chat_id: "web", op: "reply", text: "Streaming replies show the text as it is written.", stream: "k3v9q2a8d1f0" },
  },
  activity_append_live: {
    type: "activity_append",
    session: "550e8400-e29b-41d4-a716-446655440000",
    event: { ts: "2026-11-05T09:29:58.000Z", kind: "reasoning", title: "Thinking", detail: "Planning the table.", stream: "t7c1aa04be92", seq: 7 },
  },
  // On (re)subscribe with `streams`, the open streams of the chats in fullLogs.
  snapshot_live: {
    type: "snapshot",
    sessions: [],
    streams: { "550e8400-e29b-41d4-a716-446655440000": [{ stream: "k3v9q2a8d1f0", kind: "reply", text: "Streaming replies show " }] },
  },
}
for (const [name, f] of Object.entries(frames)) writeFileSync(`${dir}/${name}.json`, JSON.stringify(f, null, 2))
console.log(`wrote ${Object.keys(frames).length} fixtures to ${dir}`)
