// src/core/forge/gitlab.test.ts
import { test, expect, afterEach } from "bun:test"
import { gitlabAdapter as gl } from "./gitlab"
import type { ForgeCredential } from "./types"

const c: ForgeCredential = {
  id: "gitlab:gitlab.com:ahmet", kind: "gitlab", host: "gitlab.com",
  apiBase: "https://gitlab.com/api/v4", label: "", account: { login: "ahmet" },
  source: "pat", transport: "https", status: "ok", token: "glpat_x",
}
const realFetch = globalThis.fetch
afterEach(() => { globalThis.fetch = realFetch })
function mock(handler: (url: string, init?: RequestInit) => { status?: number; body: unknown; headers?: Record<string, string> }) {
  globalThis.fetch = (async (url: any, init?: any) => {
    const r = handler(String(url), init)
    return new Response(JSON.stringify(r.body), { status: r.status ?? 200, headers: r.headers })
  }) as any
}

test("verify sends PRIVATE-TOKEN and returns the account", async () => {
  let hdr = ""
  mock((_u, init) => { hdr = (init?.headers as any)?.["PRIVATE-TOKEN"] ?? ""; return { body: { username: "ahmet", name: "Ahmet" } } })
  const a = await gl.verify(c)
  expect(a.login).toBe("ahmet")
  expect(hdr).toBe("glpat_x")
})

test("listRepos maps path_with_namespace + visibility", async () => {
  mock(() => ({ body: [{
    name: "web", path_with_namespace: "acme/web", visibility: "private", description: "d",
    default_branch: "main", last_activity_at: "2026-06-01T00:00:00Z",
    http_url_to_repo: "https://gitlab.com/acme/web.git", web_url: "https://gitlab.com/acme/web",
  }] }))
  const repos = await gl.listRepos(c, {})
  expect(repos[0]).toMatchObject({ owner: "acme", name: "web", fullName: "acme/web", private: true, defaultBranch: "main",
    cloneUrl: "https://gitlab.com/acme/web.git" })
})

test("createRepo posts to /projects", async () => {
  let url = "", body: any = null
  mock((u, init) => { url = u; body = JSON.parse(String(init?.body)); return { status: 201, body: {
    name: "new", path_with_namespace: "ahmet/new", visibility: "private", default_branch: "main",
    http_url_to_repo: "https://gitlab.com/ahmet/new.git", web_url: "https://gitlab.com/ahmet/new" } } })
  const repo = await gl.createRepo(c, { connectionId: c.id, name: "new", private: true })
  expect(url).toBe("https://gitlab.com/api/v4/projects")
  expect(body).toMatchObject({ name: "new", visibility: "private" })
  expect(repo.fullName).toBe("ahmet/new")
})

test("sshRemoteUrl + gitUser", () => {
  expect(gl.gitUser()).toBe("oauth2")
  expect(gl.sshRemoteUrl({ host: "gitlab.com", owner: "acme", name: "web" } as any)).toBe("git@gitlab.com:acme/web.git")
})
