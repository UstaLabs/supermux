import type { ForgeConnection, RemoteRepo } from "@/api/client"

export interface OmniLocal { kind: "local"; label: string; path: string }
export interface OmniCloud { kind: "cloud"; label: string; connectionId: string; repo: RemoteRepo }
export interface OmniCreate { kind: "create"; label: string; createTarget: "local" | string; connection?: ForgeConnection }
export type OmniOption = OmniLocal | OmniCloud | OmniCreate

const VALID_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]*$/

export function buildOmniboxOptions(input: {
  query: string
  localProjects: { label: string; path: string }[]
  cloudRepos: RemoteRepo[]
  connections: ForgeConnection[]
}): OmniOption[] {
  const q = input.query.trim()
  const ql = q.toLowerCase()
  const local: OmniLocal[] = input.localProjects
    .filter((p) => !q || p.label.toLowerCase().includes(ql) || p.path.toLowerCase().includes(ql))
    .map((p) => ({ kind: "local", label: p.label, path: p.path }))
  const cloud: OmniCloud[] = input.cloudRepos.map((r) => ({ kind: "cloud", label: r.fullName, connectionId: r.connectionId, repo: r }))

  const exact = !!q && (
    local.some((o) => o.label.toLowerCase() === ql) ||
    cloud.some((o) => o.repo.name.toLowerCase() === ql || o.repo.fullName.toLowerCase() === ql)
  )
  const creates: OmniCreate[] = (q && VALID_NAME.test(q) && !exact)
    ? [
        { kind: "create", label: `Create locally — ${q}`, createTarget: "local" },
        ...input.connections.map((c) => ({ kind: "create" as const, label: `Create on ${c.host} — ${c.account.login}/${q}`, createTarget: c.id, connection: c })),
      ]
    : []

  return [...local, ...cloud, ...creates]
}
