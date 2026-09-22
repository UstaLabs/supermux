import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { ProjectStore } from "./store"
import { projectDto } from "./types"

function fresh() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return new ProjectStore(db)
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

test("create returns a record with a UUID id and no image", () => {
  const s = fresh()
  const p = s.create({ name: "App", sort_order: 0 })
  expect(p.id).toMatch(UUID_RE)
  expect(p.name).toBe("App")
  expect(p.image_id).toBeUndefined()
  expect(s.getById(p.id)).toEqual(p)
  expect(s.getById("nope")).toBeUndefined()
})

test("list orders by sort_order, then name, then id", () => {
  const s = fresh()
  s.create({ id: "c", name: "B", sort_order: 0 })
  s.create({ id: "b", name: "A", sort_order: 1 })
  s.create({ id: "a", name: "A", sort_order: 1 })
  s.create({ id: "d", name: "Z", sort_order: -1 })
  expect(s.list().map((p) => p.id)).toEqual(["d", "c", "a", "b"])
})

test("addLocation stores a location and a duplicate path throws", () => {
  const s = fresh()
  const p = s.create({ name: "App", sort_order: 0 })
  const l = s.addLocation(p.id, "/h/app")
  expect(l.id).toMatch(UUID_RE)
  expect(s.findLocationByPath("/h/app")).toEqual({ id: l.id, project_id: p.id, path: "/h/app" })
  expect(s.getLocation(l.id)).toEqual(l)
  expect(s.findLocationByPath("/h/other")).toBeUndefined()
  expect(() => s.addLocation(p.id, "/h/app")).toThrow()
})

test("listLocations orders by path; allLocations returns every row", () => {
  const s = fresh()
  const a = s.create({ name: "A", sort_order: 0 })
  const b = s.create({ name: "B", sort_order: 1 })
  s.addLocation(a.id, "/z")
  s.addLocation(a.id, "/m")
  s.addLocation(b.id, "/a")
  expect(s.listLocations(a.id).map((l) => l.path)).toEqual(["/m", "/z"])
  expect(s.allLocations().map((l) => l.path).sort()).toEqual(["/a", "/m", "/z"])
})

test("rename and setImage update the row", () => {
  const s = fresh()
  const p = s.create({ name: "A", sort_order: 0 })
  s.rename(p.id, "Renamed")
  s.setImage(p.id, "img.png")
  expect(s.getById(p.id)).toMatchObject({ name: "Renamed", image_id: "img.png" })
  s.setImage(p.id, null)
  expect(s.getById(p.id)!.image_id).toBeUndefined()
})

test("reorder assigns sort_order by index", () => {
  const s = fresh()
  const a = s.create({ name: "A", sort_order: 0 })
  const b = s.create({ name: "B", sort_order: 1 })
  const c = s.create({ name: "C", sort_order: 2 })
  s.reorder([c.id, a.id, b.id])
  expect(s.list().map((p) => [p.id, p.sort_order])).toEqual([[c.id, 0], [a.id, 1], [b.id, 2]])
})

test("moveLocation reassigns a location, keeping its id", () => {
  const s = fresh()
  const a = s.create({ name: "A", sort_order: 0 })
  const b = s.create({ name: "B", sort_order: 1 })
  const l = s.addLocation(a.id, "/x")
  s.moveLocation(l.id, b.id)
  expect(s.findLocationByPath("/x")).toEqual({ id: l.id, project_id: b.id, path: "/x" })
  expect(s.listLocations(a.id)).toEqual([])
})

test("maxSortOrder is -1 on empty, else the max", () => {
  const s = fresh()
  expect(s.maxSortOrder()).toBe(-1)
  s.create({ name: "A", sort_order: 4 })
  s.create({ name: "B", sort_order: 2 })
  expect(s.maxSortOrder()).toBe(4)
})

test("projectDto carries locations and omits an unset image_id", () => {
  const s = fresh()
  const p = s.create({ name: "A", sort_order: 0 })
  const l = s.addLocation(p.id, "/x")
  const dto = projectDto(p, s.listLocations(p.id))
  expect(dto).toEqual({ id: p.id, name: "A", sort_order: 0, created_at: p.created_at, locations: [{ id: l.id, path: "/x" }] })
  expect("image_id" in dto).toBe(false)
})
