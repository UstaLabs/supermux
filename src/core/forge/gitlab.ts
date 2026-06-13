// src/core/forge/gitlab.ts
import { ForgeError, type ForgeAdapter, type ForgeCredential, type RemoteRepo } from "./types"

function apiBaseFor(host: string): string {
  return host === "gitlab.com" ? "https://gitlab.com/api/v4" : `https://${host}/api/v4`
}

async function call(c: ForgeCredential, path: string, init?: RequestInit): Promise<any> {
  let res: Response
  try {
    res = await fetch(`${c.apiBase}${path}`, {
      ...init,
      headers: { "PRIVATE-TOKEN": c.token, "Content-Type": "application/json", ...(init?.headers ?? {}) },
    })
  } catch (e) { throw new ForgeError("network", String(e)) }
  if (res.ok) return res.status === 204 ? null : res.json()
  const text = await res.text().catch(() => "")
  if (res.status === 401) throw new ForgeError("auth", text, 401)
  if (res.status === 429) throw new ForgeError("rate_limited", text, 429)
  if (res.status === 403 && (res.headers.get("ratelimit-remaining") === "0" || res.headers.has("retry-after"))) throw new ForgeError("rate_limited", text, 403)
  if (res.status === 403) throw new ForgeError("scope_missing", text, 403)
  if (res.status === 404) throw new ForgeError("not_found", text, 404)
  if (res.status === 400 && /taken|already exists/i.test(text)) throw new ForgeError("conflict", text, 400)
  throw new ForgeError("unknown", `GitLab ${res.status}: ${text}`, res.status)
}

function toRepo(c: ForgeCredential, j: any): RemoteRepo {
  const full = j.path_with_namespace as string
  const slash = full.lastIndexOf("/")
  return {
    connectionId: c.id, kind: "gitlab", host: c.host,
    owner: full.slice(0, slash), name: j.path ?? full.slice(slash + 1), fullName: full,
    private: j.visibility !== "public", description: j.description ?? undefined,
    defaultBranch: j.default_branch ?? "main", updatedAt: j.last_activity_at ?? undefined,
    cloneUrl: j.http_url_to_repo, webUrl: j.web_url,
  }
}

export const gitlabAdapter: ForgeAdapter = {
  kind: "gitlab",
  apiBaseFor,
  async verify(c) {
    const u = await call(c, "/user")
    return { login: u.username, name: u.name ?? undefined, avatarUrl: u.avatar_url ?? undefined }
  },
  async listRepos(c, { query, perPage = 50 }) {
    const search = query ? `&search=${encodeURIComponent(query)}` : ""
    const j = await call(c, `/projects?membership=true&order_by=last_activity_at&per_page=${perPage}${search}`)
    return (j as any[]).map((r) => toRepo(c, r))
  },
  async createRepo(c, input) {
    const body = JSON.stringify({ name: input.name, visibility: input.private ? "private" : "public",
      description: input.description, initialize_with_readme: input.initReadme ?? false })
    return toRepo(c, await call(c, "/projects", { method: "POST", body }))
  },
  gitUser: () => "oauth2",
  async registerSshKey(c, publicKey, title) {
    const j = await call(c, "/user/keys", { method: "POST", body: JSON.stringify({ title, key: publicKey }) })
    return { id: String(j.id) }
  },
  sshRemoteUrl: (repo) => `git@${repo.host}:${repo.owner}/${repo.name}.git`,
  async hostKeys() { return [] }, // GitLab has no public host-keys endpoint; service falls back to ssh-keyscan
}
