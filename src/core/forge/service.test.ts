// src/core/forge/service.test.ts
import { test, expect, afterAll } from "bun:test"
import { mkdtempSync, rmSync, existsSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { ForgeStore } from "./store"
import { ForgeService } from "./service"
import type { ForgeAdapter, ForgeCredential, RemoteRepo } from "./types"

const work = mkdtempSync(join(tmpdir(), "forge-svc-"))
afterAll(() => rmSync(work, { recursive: true, force: true }))

function fakeAdapter(repos: RemoteRepo[]): ForgeAdapter {
  return {
    kind: "github", apiBaseFor: () => "https://api.github.com",
    verify: async (c) => c.account,
    listRepos: async (c, { query }) => repos.filter((r) => !query || r.name.includes(query)).map((r) => ({ ...r, connectionId: c.id })),
    createRepo: async (c, i) => ({ ...repos[0]!, connectionId: c.id, name: i.name, fullName: `${c.account.login}/${i.name}` }),
    gitUser: () => "x-access-token",
    registerSshKey: async () => ({ id: "1" }),
    sshRemoteUrl: (r) => `git@${r.host}:${r.owner}/${r.name}.git`,
    hostKeys: async () => [],
  }
}
function svc(repos: RemoteRepo[]) {
  const db = openDb(":memory:"); runMigrations(db, MIGRATIONS)
  const store = new ForgeStore(db)
  return new ForgeService(store, { projectsRoot: join(work, "projects"), sshRoot: join(work, "ssh") }, () => fakeAdapter(repos))
}
function cred(id: string): ForgeCredential {
  return { id, kind: "github", host: "github.com", apiBase: "https://api.github.com", label: "",
    account: { login: id.split(":")[2]! }, source: "pat", transport: "https", status: "ok", token: "t" }
}
const repo = (name: string): RemoteRepo => ({
  connectionId: "", kind: "github", host: "github.com", owner: "ahmet", name, fullName: `ahmet/${name}`,
  private: false, defaultBranch: "main", cloneUrl: `file:///dev/null`, webUrl: "" })

test("search fans out across connections, tags + dedupes", async () => {
  const s = svc([repo("supermux"), repo("kurbanhane")])
  s["store"].add(cred("github:github.com:a")); s["store"].add(cred("github:github.com:b"))
  const { repos, errors } = await s.search("super")
  expect(errors).toHaveLength(0)
  expect(repos.map((r) => `${r.connectionId}:${r.name}`).sort()).toEqual(["github:github.com:a:supermux", "github:github.com:b:supermux"])
})

test("a failing connection yields a soft error, not a thrown search", async () => {
  const s = svc([repo("x")])
  s["store"].add(cred("github:github.com:a"))
  s.setAdapterFactory(() => { throw new Error("boom") })
  const { repos, errors } = await s.search("x")
  expect(repos).toHaveLength(0)
  expect(errors[0]).toMatchObject({ connectionId: "github:github.com:a" })
})

test("removeConnection deletes the connection's ssh key dir", async () => {
  const s = svc([repo("x")])
  s["store"].add({ ...cred("github:github.com:a"), transport: "ssh" })
  await s.provisionSsh("github:github.com:a")
  const keyDir = join(work, "ssh", "github_github.com_a")
  expect(existsSync(keyDir)).toBe(true)
  s.removeConnection("github:github.com:a")
  expect(existsSync(keyDir)).toBe(false)
})

test("createLocal inits a git repo under the projects root", async () => {
  const s = svc([])
  const { localPath } = await s.createLocal("scratch")
  expect(existsSync(join(localPath, ".git"))).toBe(true)
  expect(localPath).toBe(join(work, "projects", "local", "scratch"))
})

test("listCloned reflects what createLocal made; removeCloned deletes it", async () => {
  const s = svc([])
  await s.createLocal("scratch")
  expect(s.listCloned().some((c) => c.name === "scratch")).toBe(true)
  const path = join(work, "projects", "local", "scratch")
  s.removeCloned(path)
  expect(existsSync(path)).toBe(false)
})

test("createLocal rejects unsafe names", async () => {
  const s = svc([])
  await expect(s.createLocal("../evil")).rejects.toMatchObject({ name: "ForgeError" })
  await expect(s.createLocal("a/b")).rejects.toMatchObject({ name: "ForgeError" })
  await expect(s.createLocal("")).rejects.toMatchObject({ name: "ForgeError" })
})

test("pullCloned rejects a path outside the projects root", () => {
  const s = svc([])
  expect(() => s.pullCloned("/etc")).toThrow()
})
