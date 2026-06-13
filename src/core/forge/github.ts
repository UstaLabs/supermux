// src/core/forge/github.ts
import { ForgeError, type CreateRepoInput, type ForgeAdapter, type ForgeCredential, type RemoteRepo } from "./types"

function apiBaseFor(host: string): string {
  return host === "github.com" ? "https://api.github.com" : `https://${host}/api/v3`
}

async function call(c: ForgeCredential, path: string, init?: RequestInit): Promise<any> {
  let res: Response
  try {
    res = await fetch(`${c.apiBase}${path}`, {
      ...init,
      headers: { Authorization: `Bearer ${c.token}`, Accept: "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28", ...(init?.headers ?? {}) },
    })
  } catch (e) { throw new ForgeError("network", String(e)) }
  if (res.ok) return res.status === 204 ? null : res.json()
  const text = await res.text().catch(() => "")
  if (res.status === 401) throw new ForgeError("auth", text || "unauthorized", 401)
  if (res.status === 403 && res.headers.get("x-ratelimit-remaining") === "0") throw new ForgeError("rate_limited", text, 403)
  if (res.status === 403) throw new ForgeError("scope_missing", text, 403)
  if (res.status === 404) throw new ForgeError("not_found", text, 404)
  if (res.status === 422) throw new ForgeError("conflict", text, 422)
  throw new ForgeError("unknown", `GitHub ${res.status}: ${text}`, res.status)
}

function toRepo(c: ForgeCredential, j: any): RemoteRepo {
  return {
    connectionId: c.id, kind: "github", host: c.host,
    owner: j.owner?.login ?? j.full_name.split("/")[0], name: j.name, fullName: j.full_name,
    private: !!j.private, description: j.description ?? undefined, defaultBranch: j.default_branch ?? "main",
    language: j.language ?? undefined, updatedAt: j.updated_at ?? undefined,
    cloneUrl: j.clone_url, webUrl: j.html_url,
  }
}

export const githubAdapter: ForgeAdapter = {
  kind: "github",
  apiBaseFor,
  async verify(c) {
    const u = await call(c, "/user")
    return { login: u.login, name: u.name ?? undefined, avatarUrl: u.avatar_url ?? undefined }
  },
  async listRepos(c, { query, perPage = 50 }) {
    if (query) {
      const q = encodeURIComponent(`${query} user:${c.account.login}`)
      const j = await call(c, `/search/repositories?q=${q}&per_page=${perPage}`)
      return (j.items ?? []).map((r: any) => toRepo(c, r))
    }
    const j = await call(c, `/user/repos?per_page=${perPage}&sort=updated&affiliation=owner,collaborator,organization_member`)
    return (j as any[]).map((r) => toRepo(c, r))
  },
  async createRepo(c, input) {
    const body = JSON.stringify({ name: input.name, private: input.private, description: input.description, auto_init: input.initReadme ?? false })
    const path = input.owner && input.owner !== c.account.login ? `/orgs/${input.owner}/repos` : "/user/repos"
    return toRepo(c, await call(c, path, { method: "POST", body }))
  },
  gitUser: () => "x-access-token",
  async registerSshKey(c, publicKey, title) {
    const j = await call(c, "/user/keys", { method: "POST", body: JSON.stringify({ title, key: publicKey }) })
    return { id: String(j.id) }
  },
  sshRemoteUrl: (repo) => `git@${repo.host}:${repo.owner}/${repo.name}.git`,
  async hostKeys(c) {
    const j = await call(c, "/meta")
    return (j.ssh_keys ?? []).map((k: string) => `${c.host} ${k}`)
  },
}
