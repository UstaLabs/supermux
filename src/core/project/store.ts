import { randomUUID } from "crypto"
import type { Database as Db } from "bun:sqlite"
import { type ProjectRecord, type ProjectRow, type ProjectLocationRecord, rowToProject } from "./types"

/**
 * All SQL for projects and project_locations. No cache — read on route calls and
 * DTO builds only. `db` is public so ProjectService can wrap several calls in one
 * transaction.
 */
export class ProjectStore {
  constructor(readonly db: Db) {}

  create(input: { id?: string; name: string; sort_order: number }): ProjectRecord {
    const id = input.id ?? randomUUID()
    this.db.run(
      "INSERT INTO projects (id, name, image_id, sort_order, created_at) VALUES (?, ?, NULL, ?, ?)",
      [id, input.name, input.sort_order, new Date().toISOString()],
    )
    return this.getById(id)!
  }

  getById(id: string): ProjectRecord | undefined {
    const r = this.db.query("SELECT * FROM projects WHERE id = ?").get(id) as ProjectRow | null
    return r ? rowToProject(r) : undefined
  }

  list(): ProjectRecord[] {
    return (this.db.query("SELECT * FROM projects ORDER BY sort_order ASC, name ASC, id ASC").all() as ProjectRow[])
      .map(rowToProject)
  }

  /** -1 on an empty table, so `maxSortOrder() + 1` is always the next slot. */
  maxSortOrder(): number {
    const r = this.db.query("SELECT max(sort_order) m FROM projects").get() as { m: number | null }
    return r.m ?? -1
  }

  rename(id: string, name: string): void {
    this.db.run("UPDATE projects SET name = ? WHERE id = ?", [name, id])
  }

  setImage(id: string, imageId: string | null): void {
    this.db.run("UPDATE projects SET image_id = ? WHERE id = ?", [imageId, id])
  }

  /** Position in `ids` becomes sort_order. Ids not listed keep their old value. */
  reorder(ids: string[]): void {
    this.db.transaction((xs: string[]) => {
      xs.forEach((id, i) => this.db.run("UPDATE projects SET sort_order = ? WHERE id = ?", [i, id]))
    })(ids)
  }

  /** Throws on a duplicate path (UNIQUE) or a missing project (FK). */
  addLocation(projectId: string, path: string, id: string = randomUUID()): ProjectLocationRecord {
    this.db.run("INSERT INTO project_locations (id, project_id, path) VALUES (?, ?, ?)", [id, projectId, path])
    return { id, project_id: projectId, path }
  }

  findLocationByPath(path: string): ProjectLocationRecord | undefined {
    return (this.db.query("SELECT * FROM project_locations WHERE path = ?").get(path) as ProjectLocationRecord | null)
      ?? undefined
  }

  getLocation(id: string): ProjectLocationRecord | undefined {
    return (this.db.query("SELECT * FROM project_locations WHERE id = ?").get(id) as ProjectLocationRecord | null)
      ?? undefined
  }

  listLocations(projectId: string): ProjectLocationRecord[] {
    return this.db.query("SELECT * FROM project_locations WHERE project_id = ? ORDER BY path ASC")
      .all(projectId) as ProjectLocationRecord[]
  }

  allLocations(): ProjectLocationRecord[] {
    return this.db.query("SELECT * FROM project_locations").all() as ProjectLocationRecord[]
  }

  moveLocation(locationId: string, projectId: string): void {
    this.db.run("UPDATE project_locations SET project_id = ? WHERE id = ?", [projectId, locationId])
  }
}
