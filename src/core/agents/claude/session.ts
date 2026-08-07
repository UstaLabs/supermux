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
  const backend = deps.sessionBackend ?? getSessionBackend()
  const base = args.requestedName ?? deriveName(args.workdir)
  // Resolve a window name unique against BOTH taken display names AND existing
  // tmux window names. Worker windows are named after the repo base (e.g.
  // "supermux"); a session that later renames its DISPLAY name keeps its
  // original window name, so the base would otherwise look "free" as a display
  // name and collide with that still-live window. The old path then ran
  // `kill-window -t mux:<name>`, which killed the existing same-named (live!)
  // session — i.e. creating a new session silently killed the previously-active
  // one on the same repo. Uniquifying against live window names means we never
  // collide, so we never need to (and never do) kill a sibling's window.
  const existingWindows = (await backend.list(deps.tmuxSession)).map(target => target.name)
  const name = ensureUnique(base, new Set([...deps.registry.takenNames(), ...existingWindows]))
  const id = randomUUID()
  const claudeSessionId = randomUUID()
  deps.registry.reserveName(name)
  preAcceptTrust(args.workdir)
  try {
    await deps.bind(id)
    const spec = buildClaudeSpawnSpec({ name, model: args.model, effort: args.effort, sessionId: id, claudeSessionId, workdir: args.workdir, rpcMcpConfig: args.rpcMcpConfig })
    const target = await backend.create({
      group: deps.tmuxSession,
      name,
      cwd: args.workdir,
      ...spec,
      cols: 80,
      rows: 24,
    })
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
    await (deps.postSpawnReady ?? ((targetId) => sendChannelConsentEnter(targetId, { backend })))(target.id)
  } catch (err) {
    // Free the reserved name AND any row so a retry can reclaim the name.
    deps.registry.releaseName(name)
    if (deps.registry.get(id)) deps.registry.sessions.deleteById(id)
    throw err
  }
  return { name, session_id: id, model: args.model }
}
