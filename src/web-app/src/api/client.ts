// Cookie-only auth: same-origin fetches send the HttpOnly cmux_token cookie
// automatically (fetch defaults to credentials:"same-origin"). No token in JS.

export interface ReviewComment {
  id: string; repo: string; path: string; side: "RIGHT" | "LEFT"; anchorLine: number; anchorContext: string
  body: string; author: string; status: "open" | "submitted" | "resolved"; currentLine: number | null; outdated: boolean
}
export type GitRemoteStatus = {
  isRepo: boolean; hasRemote: boolean; branch: string | null; detachedSha: string | null
  upstream: string | null; ahead: number; behind: number
}
export type GitPushResult =
  | { status: "pushed" }
  | { status: "up_to_date" }
  | { status: "rejected_non_ff" }
  | { status: "auth_failed"; message: string }
  | { status: "error"; message: string }
export type GitPullResult =
  | { status: "clean" }
  | { status: "up_to_date" }
  | { status: "conflict"; files: string[] }
  | { status: "dirty"; files: string[] }
  | { status: "auth_failed"; message: string }
  | { status: "error"; message: string }
export type GitLocalBranch = { name: string; checkedOutAt: string | null }
export type GitBranchList = {
  inPlace: boolean
  repoRoot: string | null
  current: string | null
  detachedSha: string | null
  local: GitLocalBranch[]
  remote: string[]
}
export type GitSwitchResult =
  | { status: "switched"; branch: string }
  | { status: "clobber"; files: string[]; branch: string }
  | { status: "checked_out_elsewhere"; path: string }
  | { status: "merge_in_progress" }
  | { status: "invalid_name"; message: string }
  | { status: "error"; message: string }
export interface ForgeConnection {
  id: string; kind: "github" | "gitlab"; host: string; apiBase: string; label: string
  account: { login: string; name?: string; avatarUrl?: string }
  source: "pat" | "cli"; transport: "https" | "ssh"
  ssh?: { fingerprint: string; registered: boolean }; status: "ok" | "needs_reconnect"
}
export interface ForgeCliStatus { github: { available: boolean; login?: string }; gitlab: { available: boolean; login?: string } }
export interface RemoteRepo {
  connectionId: string; kind: "github" | "gitlab"; host: string; owner: string; name: string
  fullName: string; private: boolean; description?: string; defaultBranch: string; language?: string
  updatedAt?: string; cloneUrl: string; webUrl: string
}
export interface ClonedRepo { path: string; host: string; owner: string; name: string; fullName: string; sizeBytes: number }
export interface ForgeAddInput { kind: string; host?: string; apiBase?: string; token: string; source: "pat" | "cli"; transport?: "https" | "ssh" }
export interface FinishReadiness {
  base: string; branch: string
  ahead: number; behind: number
  dirtyFiles: string[]
  filesChanged: number; insertions: number; deletions: number
  hasRemote: boolean; baseHasUpstream: boolean; ghAvailable: boolean
  conflictPreflight: "clean" | "will_conflict" | "unknown"
  recommended: "merge" | "pr"
  nothingToLand: boolean
}
// In-app updater (GET /api/update/status). Mirrors the broker's UpdateStatus
// shape (src/core/update/checker.ts) plus `disabled` when MUX_UPDATE_CHECK=0.
export type UpdateMode = "binary" | "source" | "docker"
export type UpdateState =
  | "idle"
  | "checking"
  | "downloading"
  | "swapping"
  | "restart-required"
  | "failed"
export interface UpdateStatusDTO {
  current: string
  commit: string
  latest: string | null
  updateAvailable: boolean
  notesUrl: string | null
  mode: UpdateMode
  state: UpdateState
  lastChecked: number | null
  lastError: string | null
  disabled?: boolean
}
async function request(method: string, path: string, body?: unknown): Promise<any> {
  const res = await fetch(path, {
    method,
    headers: { "content-type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    let detail = ""
    try {
      const text = await res.text()
      if (text) {
        try {
          const parsed = JSON.parse(text)
          detail = String(parsed.error ?? parsed.message ?? text)
        } catch {
          detail = text
        }
      }
    } catch {
      // Keep the original status-only error if the response body is unreadable.
    }
    throw new Error(detail ? `${method} ${path} → ${res.status}: ${detail}` : `${method} ${path} → ${res.status}`)
  }
  if (res.status === 204) return null
  return res.json()
}

export interface AppConfig {
  voiceCleanupModel?: string
  whisperLang?: string
  [key: string]: unknown
}

export const api = {
  listDevices: () => request("GET", "/devices"),
  addDevice:   (name: string) => request("POST", "/devices", { name }),
  revokeDevice: (name: string) => request("DELETE", `/devices/${encodeURIComponent(name)}`),
  getUsage:     () => request("GET", "/usage"),
  listProjects: () => request("GET", "/projects") as Promise<{ projects: { path: string }[] }>,
  listModels: (agent: string) =>
    request("GET", `/models?agent=${encodeURIComponent(agent)}`) as Promise<{
      models: { id: string; displayName: string }[]
    }>,
  validatePath: (path: string) =>
    request("POST", "/paths/validate", { path }) as Promise<{ ok: boolean; path?: string; error?: string }>,
  getRepoInfo: (path: string, opts?: { fetch?: boolean }) =>
    request("GET", `/repos/info?path=${encodeURIComponent(path)}${opts?.fetch ? "&fetch=1" : ""}`) as Promise<{
      isGitRepo: boolean; eligible: boolean; repoRoot?: string; currentBranch?: string
      branches?: { local: string[]; remote: string[] }
    }>,
  previewCommands: (agent: string, workdir: string) =>
    request("GET", `/commands/preview?agent=${encodeURIComponent(agent)}&workdir=${encodeURIComponent(workdir)}`) as Promise<{
      commands: Array<{ id: string; family: string; name: string; sigil: string; description?: string; insertText?: string }>
      resolved: boolean
    }>,
  listSessions: () => request("GET", "/sessions") as Promise<Array<{
    id: string
    name: string
    role?: "personal_assistant" | "worker"
    isDefault?: boolean
  }>>,
  createSession: (args: { name?: string; workdir: string; agent?: string; model?: string; worktree?: boolean; baseBranch?: string }) =>
    request("POST", "/sessions", args),
  getSessionMessages: (id: string) =>
    request("GET", `/sessions/${encodeURIComponent(id)}/messages`),
  killSession: (id: string) =>
    request("DELETE", `/sessions/${encodeURIComponent(id)}`),
  renameSession: (id: string, newName: string) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/rename`, { name: newName }),
  toggleMute: (id: string, muted: boolean) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/mute`, { muted }),
  interrupt: (id: string) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/interrupt`, {}),
  finishReadiness: (id: string) =>
    request("GET", `/sessions/${encodeURIComponent(id)}/finish/readiness`) as Promise<FinishReadiness | { error: string }>,
  finish: (id: string, body?: { action?: "merge"|"pr"|"keep"|"discard"; skipVerify?: boolean; commitFirst?: boolean; commitMessage?: string; draft?: boolean; prRequiresGreen?: boolean; prTitle?: string; prBody?: string }) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/finish`, body ?? {}) as Promise<import("@/stores/finishJob").FinishJob | { error: string }>,
  gitStatus: (id: string) =>
    request("GET", `/sessions/${encodeURIComponent(id)}/git/status`) as Promise<GitRemoteStatus>,
  gitFetch: (id: string) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/git/fetch`, {}) as Promise<{ ok: boolean; error?: string }>,
  gitPublish: (id: string) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/git/publish`, {}) as Promise<GitPushResult>,
  gitPush: (id: string) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/git/push`, {}) as Promise<GitPushResult>,
  gitPull: (id: string) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/git/pull`, {}) as Promise<GitPullResult>,
  gitBranches: (id: string) =>
    request("GET", `/sessions/${encodeURIComponent(id)}/git/branches`) as Promise<GitBranchList>,
  gitSwitch: (id: string, name: string, create?: boolean) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/git/switch`, { name, create: !!create }) as Promise<GitSwitchResult>,
  sendMessage: (id: string, text: string) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/message`, { text }) as Promise<{ ok: boolean; reason?: string }>,
  verifySuggest: (id: string) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/verify/suggest`, {}) as Promise<{ content: string; source: string }>,
  verifySave: (id: string, content: string) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/verify/save`, { content }) as Promise<{ ok: boolean; reason?: string }>,
  listProxies: () => request("GET", "/proxies"),
  listDisplays: () => request("GET", "/displays"),
  startDisplay: (args: { sessionName?: string; provider?: string; width?: number; height?: number }) =>
    request("POST", "/displays", args),
  stopDisplay: (id: string) =>
    request("DELETE", `/displays/${encodeURIComponent(id)}`),
  createProxy: (args: { sessionName: string; port: number; domain?: string }) =>
    request("POST", "/proxies", args),
  removeProxy: (domain: string) =>
    request("DELETE", `/proxies/${encodeURIComponent(domain)}`),
  setProxyPublic: (domain: string, isPublic: boolean) =>
    request("PATCH", `/proxies/${encodeURIComponent(domain)}`, { isPublic }),
  listArchivedSessions: () => request("GET", "/archived-sessions"),
  resumeSession: (id: string) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/resume`),
  fsListDir: (sessionId: string, path: string) =>
    request("GET", `/sessions/${encodeURIComponent(sessionId)}/fs?path=${encodeURIComponent(path)}`),
  fsReadFile: async (sessionId: string, path: string): Promise<string> => {
    const res = await fetch(`/sessions/${encodeURIComponent(sessionId)}/fs/read?path=${encodeURIComponent(path)}`)
    if (!res.ok) throw new Error(`read ${path} → ${res.status}`)
    return res.text()
  },
  fsWriteFile: async (sessionId: string, path: string, content: string): Promise<any> => {
    const res = await fetch(`/sessions/${encodeURIComponent(sessionId)}/fs/write?path=${encodeURIComponent(path)}`, {
      method: "PUT",
      headers: { "content-type": "text/plain" },
      body: content,
    })
    if (!res.ok) throw new Error(`write ${path} → ${res.status}`)
    return res.json()
  },
  fsSearch: (sessionId: string, q: string) =>
    request("GET", `/sessions/${encodeURIComponent(sessionId)}/fs/search?q=${encodeURIComponent(q)}`),
  fsDiff: (sessionId: string) =>
    request("GET", `/sessions/${encodeURIComponent(sessionId)}/fs/diff`) as Promise<{ repos: import("@/composables/useEditor").RepoDiff[]; comments: ReviewComment[] }>,
  reviewAddComment: (id: string, c: { repo: string; path: string; side: "RIGHT" | "LEFT"; anchorLine: number; anchorContext: string; body: string; diffHunkHeader?: string }) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/review/comments`, c) as Promise<ReviewComment>,
  reviewUpdateComment: (id: string, commentId: string, patch: { status?: string; body?: string; resolvedBy?: string }) =>
    request("PATCH", `/sessions/${encodeURIComponent(id)}/review/comments/${encodeURIComponent(commentId)}`, patch),
  reviewDeleteComment: (id: string, commentId: string) =>
    request("DELETE", `/sessions/${encodeURIComponent(id)}/review/comments/${encodeURIComponent(commentId)}`),
  reviewSubmit: (id: string) =>
    request("POST", `/sessions/${encodeURIComponent(id)}/review/submit`, {}) as Promise<{ ok: boolean; delivered: number; reason?: string }>,
  getCuratorSettings: () => request("GET", "/settings/curator"),
  saveCuratorSettings: (cfg: { enabled: boolean; hour: number; minute: number }) =>
    request("PUT", "/settings/curator", cfg),
  runCuratorNow: () => request("POST", "/settings/curator/run-now"),
  // Onboarding wizard
  getAgentStatuses: () => request("GET", "/agents/status"),
  startAgentLogin: (kind: string) => request("POST", `/agents/${encodeURIComponent(kind)}/login`),
  getAgentLogin: (kind: string) => request("GET", `/agents/${encodeURIComponent(kind)}/login`),
  cancelAgentLogin: (kind: string) => request("POST", `/agents/${encodeURIComponent(kind)}/login/cancel`),
  sendAgentLoginCode: (kind: string, code: string) => request("POST", `/agents/${encodeURIComponent(kind)}/login/code`, { code }),
  getOpenCodeProviders: () => request("GET", "/opencode/providers") as Promise<Array<{ id: string; configured: boolean; methods: Array<{ type: string; label: string; index: number }> }>>,
  setOpenCodeKey: (providerId: string, key: string) => request("POST", "/opencode/auth/key", { providerId, key }),
  startOpenCodeOAuth: (providerId: string, method: number) => request("POST", "/opencode/auth/oauth/start", { providerId, method }) as Promise<{ url: string; instructions?: string }>,
  finishOpenCodeOAuth: (providerId: string, method: number, code: string) => request("POST", "/opencode/auth/oauth/finish", { providerId, method, code }),
  getAppConfig: () => request("GET", "/settings/config") as Promise<AppConfig>,
  saveAppConfig: (patch: Partial<AppConfig>) => request("PUT", "/settings/config", patch),
  claimPair: (name = "setup") => request("POST", "/pair/claim", { name }),
  getSoul: async (): Promise<string> => { const r = await fetch("/settings/soul"); if (!r.ok) throw new Error(`GET /settings/soul → ${r.status}`); return r.text() },
  saveSoul: async (content: string): Promise<void> => { const r = await fetch("/settings/soul", { method: "PUT", headers: { "content-type": "text/plain" }, body: content }); if (!r.ok) throw new Error(`PUT /settings/soul → ${r.status}`) },
  getExposure: () => request("GET", "/settings/exposure"),
  validateExposure: () => request("POST", "/settings/exposure/validate"),
  // Client-side logs (from main)
  getEditorSettings: () =>
    request("GET", "/settings/editor") as Promise<{
      lsp: {
        servers: Array<{
          id: string
          label: string
          extensions: string[]
          enabled: boolean
          state: "ready" | "missing" | "prereq-missing"
          installLabel: string | null
          installable: boolean
          requires: string | null
          custom: boolean
          command?: string | null
        }>
      }
    }>,
  addCustomEditorLsp: (body: {
    id: string
    label: string
    command: string
    args?: string | string[]
    extensions: string | string[]
    languageId?: string
    installCmd?: string
  }) =>
    request("POST", "/settings/editor/lsp/custom", body) as Promise<{
      ok: boolean
      error?: string
      lsp?: { servers: unknown[] }
    }>,
  removeCustomEditorLsp: (serverId: string) =>
    request("DELETE", `/settings/editor/lsp/custom/${encodeURIComponent(serverId)}`) as Promise<{
      ok: boolean
      error?: string
      lsp?: { servers: unknown[] }
    }>,
  saveEditorSettings: (patch: { lsp?: { servers?: Record<string, { enabled?: boolean }> } }) =>
    request("PUT", "/settings/editor", patch) as Promise<{
      lsp: { servers: Array<{ id: string; enabled: boolean; state: string }> }
    }>,
  installEditorLsp: (serverId: string) =>
    request("POST", `/settings/editor/lsp/${encodeURIComponent(serverId)}/install`) as Promise<{ ok: boolean; lines: string[] }>,
  postClientLogs: (body: { entries: unknown[]; meta?: Record<string, unknown> }) =>
    request("POST", "/client-logs", body),
  getClientLogs: (category?: string) =>
    request("GET", `/debug/client-logs${category ? `?category=${encodeURIComponent(category)}` : ""}`) as Promise<{
      entries: Array<{ ts: number; category: string; event: string; data?: Record<string, unknown>; device?: string; serverTs?: number }>
    }>,
  listPAs: () =>
    request("GET", "/api/pas") as Promise<{
      pas: Array<{
        id: string
        name: string
        workdir: string
        mute: boolean
        connected: boolean
        agent?: string
        model?: string
        role?: "personal_assistant" | "worker"
        isDefault?: boolean
        status?: string
      }>
    }>,
  createPA: (args: { name: string; agent?: string; model?: string; focusText?: string }) =>
    request("POST", "/api/pas", args),
  restartBroker: () => request("POST", "/system/restart"),
  listForges: () => request("GET", "/forge/connections") as Promise<{ connections: ForgeConnection[]; cli: ForgeCliStatus | null }>,
  addForge: (input: ForgeAddInput) => request("POST", "/forge/connections", input) as Promise<ForgeConnection>,
  importForge: (kind: string, transport?: "https" | "ssh") => request("POST", "/forge/connections/import", { kind, transport }) as Promise<ForgeConnection>,
  removeForge: (id: string) => request("DELETE", `/forge/connections/${encodeURIComponent(id)}`),
  searchForge: (query: string) => request("POST", "/forge/search", { query }) as Promise<{ repos: RemoteRepo[]; errors: { connectionId: string; code: string; message: string }[] }>,
  cloneForge: (connectionId: string, owner: string, name: string) => request("POST", "/forge/clone", { connectionId, owner, name }) as Promise<{ localPath: string }>,
  createForge: (input: { connectionId: string; name: string; owner?: string; private: boolean }) => request("POST", "/forge/create", input) as Promise<{ repo: RemoteRepo; localPath: string }>,
  createLocalRepo: (name: string) => request("POST", "/forge/create-local", { name }) as Promise<{ localPath: string }>,
  listClonedRepos: () => request("GET", "/forge/cloned") as Promise<{ repos: ClonedRepo[] }>,
  removeClonedRepo: (path: string) => request("DELETE", "/forge/cloned", { path }),
  pullClonedRepo: (path: string) => request("POST", "/forge/cloned/pull", { path }),
  getUpdateStatus: () => request("GET", "/api/update/status") as Promise<UpdateStatusDTO>,
  runUpdate: () =>
    request("POST", "/api/update/run", {}) as Promise<{ started: boolean } | { error: string; instruction?: string }>,
  // Web terminals (tmux-backed). Source of truth for the tab set is the broker.
  listTerminals: (session: string) =>
    request("GET", `/api/term/list?session=${encodeURIComponent(session)}`) as Promise<{ terminals: Array<{ id: string; createdAt: number }> }>,
  closeTerminal: (session: string, terminal: string) =>
    request("POST", "/api/term/close", { session, terminal }) as Promise<{ ok: boolean }>,
}
