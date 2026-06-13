// src/core/forge/github.test.ts
import { test, expect, afterEach } from "bun:test"
import { githubAdapter as gh } from "./github"
import type { ForgeCredential } from "./types"

const c: ForgeCredential = {
  id: "github:github.com:ahmet", kind: "github", host: "github.com",
  apiBase: "https://api.github.com", label: "", account: { login: "ahmet" },
  source: "pat", transport: "https", status: "ok", token: "ghp_x",
}
const realFetch = globalThis.fetch
afterEach(() => { globalThis.fetch = realFetch })
function mock(handler: (url: string, init?: RequestInit) => { status?: number; body: unknown; headers?: Record<string, string> }) {
  globalThis.fetch = (async (url: any, init?: any) => {
    const r = handler(String(url), init)
    return new Response(JSON.stringify(r.body), { status: r.status ?? 200, headers: r.headers })
  }) as any
}

test("verify returns the account and sends a Bearer token", async () => {
  let seenAuth = ""
  mock((url, init) => { seenAuth = (init?.headers as any)?.Authorization ?? ""; return { body: { login: "ahmet", name: "Ahmet" } } })
  const acct = await gh.verify(c)
  expect(acct.login).toBe("ahmet")
  expect(seenAuth).toBe("Bearer ghp_x")
})

test("verify maps 401 to ForgeError(auth)", async () => {
  mock(() => ({ status: 401, body: { message: "Bad credentials" } }))
  await expect(gh.verify(c)).rejects.toMatchObject({ code: "auth" })
})

test("listRepos maps the REST shape to RemoteRepo", async () => {
  mock(() => ({ body: [{
    name: "supermux", full_name: "ahmet/supermux", private: true, description: "d",
    default_branch: "main", language: "TypeScript", updated_at: "2026-06-01T00:00:00Z",
    clone_url: "https://github.com/ahmet/supermux.git", html_url: "https://github.com/ahmet/supermux",
    owner: { login: "ahmet" },
  }] }))
  const repos = await gh.listRepos(c, {})
  expect(repos[0]).toMatchObject({
    connectionId: c.id, kind: "github", owner: "ahmet", name: "supermux",
    fullName: "ahmet/supermux", private: true, defaultBranch: "main",
    cloneUrl: "https://github.com/ahmet/supermux.git",
  })
})

test("createRepo posts to /user/repos for a self-owned repo", async () => {
  let seenUrl = "", seenBody: any = null
  mock((url, init) => { seenUrl = url; seenBody = JSON.parse(String(init?.body)); return { status: 201, body: {
    name: "new", full_name: "ahmet/new", private: true, default_branch: "main",
    clone_url: "https://github.com/ahmet/new.git", html_url: "https://github.com/ahmet/new", owner: { login: "ahmet" },
  } } })
  const repo = await gh.createRepo(c, { connectionId: c.id, name: "new", private: true })
  expect(seenUrl).toBe("https://api.github.com/user/repos")
  expect(seenBody).toMatchObject({ name: "new", private: true })
  expect(repo.fullName).toBe("ahmet/new")
})

test("sshRemoteUrl + gitUser", () => {
  expect(gh.gitUser()).toBe("x-access-token")
  expect(gh.sshRemoteUrl({ host: "github.com", owner: "ahmet", name: "supermux" } as any)).toBe("git@github.com:ahmet/supermux.git")
})
