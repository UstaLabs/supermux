import { test, expect } from "bun:test"
import { buildOmniboxOptions } from "./forge-omnibox"
import type { ForgeConnection, RemoteRepo } from "@/api/client"

const conn = (id: string): ForgeConnection => ({ id, kind: "github", host: "github.com", apiBase: "", label: id,
  account: { login: id.split(":")[2]! }, source: "pat", transport: "https", status: "ok" })
const repo = (n: string, cid: string): RemoteRepo => ({ connectionId: cid, kind: "github", host: "github.com",
  owner: "ahmet", name: n, fullName: `ahmet/${n}`, private: false, defaultBranch: "main", cloneUrl: "", webUrl: "" })

test("blends local + cloud results and tags cloud rows with their connection", () => {
  const opts = buildOmniboxOptions({
    query: "sup", localProjects: [{ label: "supermux", path: "/p/supermux" }],
    cloudRepos: [repo("supermux", "github:github.com:a"), repo("super-secret", "github:github.com:a")],
    connections: [conn("github:github.com:a")],
  })
  const local = opts.filter((o) => o.kind === "local")
  const cloud = opts.filter((o) => o.kind === "cloud")
  expect(local.map((o) => o.label)).toContain("supermux")
  expect(cloud.map((o: any) => o.repo.name).sort()).toEqual(["super-secret", "supermux"])
  expect((cloud[0] as any).connectionId).toBe("github:github.com:a")
})

test("offers create rows (local + per-connection) only when the query is a valid name with no exact match", () => {
  const opts = buildOmniboxOptions({ query: "billing", localProjects: [], cloudRepos: [], connections: [conn("github:github.com:a")] })
  const creates = opts.filter((o) => o.kind === "create")
  expect(creates.map((o: any) => o.createTarget)).toEqual(["local", "github:github.com:a"])
})

test("no create rows for an empty or unsafe query", () => {
  expect(buildOmniboxOptions({ query: "", localProjects: [], cloudRepos: [], connections: [] }).filter((o) => o.kind === "create")).toHaveLength(0)
  expect(buildOmniboxOptions({ query: "a/b", localProjects: [], cloudRepos: [], connections: [conn("x:y:z")] }).filter((o) => o.kind === "create")).toHaveLength(0)
})

test("suppresses create rows when the query exactly matches a local or cloud repo name", () => {
  const opts = buildOmniboxOptions({ query: "supermux", localProjects: [{ label: "supermux", path: "/p/supermux" }], cloudRepos: [], connections: [conn("x:y:z")] })
  expect(opts.filter((o) => o.kind === "create")).toHaveLength(0)
})
