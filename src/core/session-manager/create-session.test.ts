import { expect, test } from "bun:test"
import { Registry } from "./registry"
import { WorkspaceService } from "../workspace/service"
import { createSession, type CreateSessionDeps } from "./create-session"

function setup(opts: { projectsChanged?: boolean; vanish?: boolean } = {}) {
  const registry = new Registry()
  const service = new WorkspaceService(registry.workspaces, {
    archiveSession: async () => {},
    resumeSession: async () => {},
    closeTerminal: async () => {},
    closeTerminalScope: async () => {},
    stopDisplay: async () => {},
  }, registry.db)
  const frames: any[] = []
  const spawns: any[] = []
  let menus = 0
  const deps: CreateSessionDeps = {
    spawn: async (a) => {
      spawns.push(a)
      const row = registry.register({ name: a.requestedName ?? "spawned", workdir: a.workdir, pid: 0, agent: a.agent })
      if (opts.vanish) registry.unregister(row.id)
      return { name: row.name, session_id: row.id }
    },
    getSession: (id) => registry.get(id),
    workspaceOf: (id) => (registry.db.query("SELECT workspace_id FROM sessions WHERE id = ?").get(id) as { workspace_id: string | null } | null)?.workspace_id || undefined,
    workspaceExists: (id) => !!registry.workspaces.getById(id),
    joinWorkspace: (w, s, v) => { service.addChatSession(w, s, v) },
    createWorkspace: (e) => ({ id: service.createForSession({ sessionId: e.id, name: e.name, workdir: e.workdir }).id, projectsChanged: !!opts.projectsChanged }),
    workspaceDto: (id) => (registry.workspaces.getById(id) ? ({ id } as never) : undefined),
    broadcast: (f) => { frames.push(f) },
    broadcastProjects: () => { frames.push({ type: "projects_changed" }) },
    refreshTelegramMenu: async () => { menus++ },
    sessionFrame: (e) => ({ id: e.id, name: e.name, full: true }),
    sessionEffort: () => "high",
  }
  const workspaceIdOf = (id: string) => deps.workspaceOf(id)
  return { registry, deps, frames, spawns, menus: () => menus, workspaceIdOf }
}

test("a session without a workspace gets its own: workspace_added, then the full session_added", async () => {
  const t = setup()
  const created = await createSession(t.deps, { workdir: "/w", name: "Kid", agent: "codex", model: "gpt-6", permissionMode: "auto" })
  expect(t.spawns).toEqual([{ workdir: "/w", requestedName: "Kid", agent: "codex", model: "gpt-6", reasoningLevel: undefined, permissionMode: "auto", account: undefined, worktree: undefined, baseBranch: undefined, inheritFromSessionId: undefined }])
  const wsId = t.workspaceIdOf(created.id)!
  expect(wsId).toBeTruthy()
  expect(t.registry.workspaces.getById(wsId)?.primary_session_id).toBe(created.id)
  expect(t.registry.workspaces.chatSessionIds(wsId)).toEqual([created.id])
  expect(t.frames).toEqual([
    { type: "workspace_added", workspace: { id: wsId } },
    { type: "session_added", session: { id: created.id, name: "Kid", full: true } },
  ])
  expect(t.menus()).toBe(1)
  expect(created).toEqual({ id: created.id, name: "Kid", workdir: "/w", agent: "codex", model: undefined, reasoningLevel: "high", permissionMode: undefined, repo_root: undefined, session_branch: undefined })
})

test("workspaceId joins that workspace as another chat (workspace_changed), with no new workspace", async () => {
  const t = setup()
  const first = await createSession(t.deps, { workdir: "/w", name: "One" })
  const wsId = t.workspaceIdOf(first.id)!
  t.frames.length = 0
  const second = await createSession(t.deps, { workdir: "/w", name: "Two", workspaceId: wsId })
  expect(t.workspaceIdOf(second.id)).toBe(wsId)
  expect(t.registry.workspaces.chatSessionIds(wsId)).toEqual([first.id, second.id])
  expect(t.frames.map((f) => f.type)).toEqual(["workspace_changed", "session_added"])
})

test("inheritFrom joins the source session's workspace", async () => {
  const t = setup()
  const first = await createSession(t.deps, { workdir: "/w", name: "One" })
  const second = await createSession(t.deps, { workdir: "/w", name: "Two", inheritFrom: first.id })
  expect(t.workspaceIdOf(second.id)).toBe(t.workspaceIdOf(first.id))
  expect(t.spawns[1].inheritFromSessionId).toBe(first.id)
})

test("a workspaceId that no longer exists gets the session a workspace of its own", async () => {
  const t = setup()
  const created = await createSession(t.deps, { workdir: "/w", name: "Kid", workspaceId: "gone" })
  expect(t.workspaceIdOf(created.id)).toBeTruthy()
  expect(t.workspaceIdOf(created.id)).not.toBe("gone")
  expect(t.frames.map((f) => f.type)).toEqual(["workspace_added", "session_added"])
})

test("a new project is announced before the workspace that points at it", async () => {
  const t = setup({ projectsChanged: true })
  await createSession(t.deps, { workdir: "/w", name: "Kid" })
  expect(t.frames.map((f) => f.type)).toEqual(["projects_changed", "workspace_added", "session_added"])
})

test("a session that is gone by the time the spawn returns gets no workspace and no session_added", async () => {
  const t = setup({ vanish: true })
  const created = await createSession(t.deps, { workdir: "/w", name: "Kid" })
  expect(t.frames).toEqual([])
  expect(t.menus()).toBe(1)
  expect(created).toEqual({ id: created.id, name: "Kid", workdir: "/w", agent: "claude", model: undefined, reasoningLevel: undefined, permissionMode: undefined, repo_root: undefined, session_branch: undefined })
})
