import { deriveName, ensureUnique } from "../../session-manager/naming"
import { buildClaudeSpawnSpec } from "../../session-manager/spawn-command"
import { preAcceptTrust } from "../../session-manager/trust"
import { sendChannelConsentEnter } from "../../session-manager/post-spawn-keys"
import { getSessionBackend } from "../../runtime"
import { captureBaseCommits } from "../../session-manager/spawn-helper"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../../session-manager/spawn-helper"
import { randomUUID } from "crypto"
import { AgentKind } from "../../../shared/agents"

export async function spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const backend = getSessionBackend()
  const base = args.requestedName ?? deriveName(args.workdir)
  // PA spawns keep the exact requested name: the row may already exist
  // (supervisor respawn with a persisted id), so uniquifying would rename the
  // PA to "<name>-2". User sessions resolve a window name unique against BOTH
  // taken display names AND existing tmux window names. Worker windows are
  // named after the repo base (e.g. "supermux"); a session that later renames
  // its DISPLAY name keeps its original window name, so the base would
  // otherwise look "free" as a display name and collide with that still-live
  // window. The old path then ran `kill-window -t mux:<name>`, which killed
  // the existing same-named (live!) session — i.e. creating a new session
  // silently killed the previously-active one on the same repo. Uniquifying
  // against live window names means we never collide, so we never need to
  // (and never do) kill a sibling's window.
  let name: string
  if (args.pa) {
    name = base
  } else {
    // takenNames() MUST be read AFTER the backend.list await: a concurrent
    // spawn can reserve a name during that await, and a stale set would let
    // both spawns pick the same name.
    const existingWindows = (await backend.list(deps.tmuxSession)).map(target => target.name)
    name = ensureUnique(base, new Set([...deps.registry.takenNames(), ...existingWindows]))
  }
  const id = args.id ?? randomUUID()
  const claudeSessionId = randomUUID()
  if (!args.pa) deps.registry.reserveName(name)
  preAcceptTrust(args.workdir)
  try {
    await deps.bind(id)
    // The PA row is registered BEFORE the window exists (mirror of the old
    // spawnPA order); its window id + fresh claude session id follow create.
    if (args.pa && !args.pa.skipRegister) {
      deps.registry.registerPA({
        id,
        name,
        agent: AgentKind.Claude,
        workdir: args.workdir,
        model: args.model,
        reasoningLevel: args.reasoningLevel,
        pid: process.pid,
        is_default: deps.registry.listPAs().length === 0,
      })
    }
    const spec = buildClaudeSpawnSpec({
      name, model: args.model, effort: args.effort, sessionId: id, claudeSessionId, workdir: args.workdir,
      ...(args.pa ? { sessionRole: "personal_assistant" as const } : { rpcMcpConfig: args.rpcMcpConfig }),
    })
    const target = await backend.create({
      group: deps.tmuxSession,
      name,
      cwd: args.workdir,
      ...spec,
      cols: 80,
      rows: 24,
    })
    if (args.pa) {
      deps.registry.sessions.setTmuxWindowId(id, target.id)
      // The row exists either way (registerPA above, or skipRegister with a
      // live row) — write the fresh claude session id directly.
      deps.registry.sessions.setAgentSessionId(id, claudeSessionId)
      void sendChannelConsentEnter(target.id, { backend })
    } else {
      // The row is born HERE, synchronously — not in onRegister. The shim's
      // register frame later ATTACHES to this row (main.ts onRegister).
      // connected:false — the socket layer flips it when the shim joins.
      deps.registry.register({
        id,
        name,
        agent: AgentKind.Claude,
        workdir: args.workdir,
        tmux_target: `${deps.tmuxSession}:${name}`,
        tmux_window_id: target.id,
        pid: target.pid ?? process.pid,
        agent_session_id: claudeSessionId,
        internal: args.internal,
        connected: false,
        base_commits: captureBaseCommits(args.workdir),
      })
      await sendChannelConsentEnter(target.id, { backend })
    }
    return { name, session_id: id, model: args.model, pid: target.pid ?? process.pid }
  } catch (err) {
    // Free the reserved name AND any row so a retry can reclaim the name.
    // Never on the PA path: the name was not reserved, and deleting a
    // pre-existing PA row on a failed respawn would destroy the PA.
    if (!args.pa) {
      deps.registry.releaseName(name)
      if (deps.registry.get(id)) deps.registry.sessions.deleteById(id)
    }
    throw err
  }
}
