export type ProjectLocationRecord = { id: string; project_id: string; path: string }
export type ProjectRecord = {
  id: string; name: string; image_id?: string; sort_order: number; created_at: string
}
/** Wire shape (plan "Wire contract"). image_id is omitted, not null, when unset. */
export type ProjectDto = {
  id: string; name: string; image_id?: string; sort_order: number; created_at: string
  locations: Array<{ id: string; path: string }>
}
export type ProjectRow = { id: string; name: string; image_id: string | null; sort_order: number; created_at: string }

export function rowToProject(r: ProjectRow): ProjectRecord {
  const p: ProjectRecord = { id: r.id, name: r.name, sort_order: r.sort_order, created_at: r.created_at }
  if (r.image_id) p.image_id = r.image_id
  return p
}

export function projectDto(p: ProjectRecord, locations: ProjectLocationRecord[]): ProjectDto {
  const dto: ProjectDto = {
    id: p.id, name: p.name, sort_order: p.sort_order, created_at: p.created_at,
    locations: locations.map((l) => ({ id: l.id, path: l.path })),
  }
  if (p.image_id) dto.image_id = p.image_id
  return dto
}
