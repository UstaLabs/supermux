import { expect, test } from "bun:test"
import { archivedProjects, filterByProject, projectLabel, type ArchivedLike } from "./archived-projects"

const HOME = "/home/ahmet"

function s(workdir: string, opts: Partial<ArchivedLike> = {}): ArchivedLike {
  return { workdir, ...opts }
}

// --- projectLabel ---

test("projectLabel shows parent/leaf with ellipsis for nested home paths", () => {
  expect(projectLabel("/home/ahmet/projects/kurbanhane", HOME)).toBe("…/projects/kurbanhane")
})

test("projectLabel shows ~/leaf when the project sits directly in home", () => {
  expect(projectLabel("/home/ahmet/foo", HOME)).toBe("~/foo")
})

test("projectLabel shows ~ for the home directory itself", () => {
  expect(projectLabel("/home/ahmet", HOME)).toBe("~")
})

test("projectLabel keeps parent/leaf for a shallow non-home path", () => {
  expect(projectLabel("/srv/acme", HOME)).toBe("srv/acme")
})

test("projectLabel adds ellipsis for a deep non-home path", () => {
  expect(projectLabel("/srv/www/acme", HOME)).toBe("…/www/acme")
})

test("projectLabel returns a single-segment path unchanged", () => {
  expect(projectLabel("/acme", HOME)).toBe("/acme")
})

// --- archivedProjects ---

test("archivedProjects dedupes by project and counts sessions", () => {
  const result = archivedProjects([
    s("/home/ahmet/projects/foo", { killed_at: "2026-06-01T00:00:00Z" }),
    s("/home/ahmet/projects/foo", { killed_at: "2026-06-02T00:00:00Z" }),
  ], HOME)
  expect(result).toEqual([{ key: "/home/ahmet/projects/foo", label: "…/projects/foo", count: 2 }])
})

test("archivedProjects groups a worktree session under its repo_root", () => {
  const result = archivedProjects([
    s("/home/ahmet/.mux/worktrees/x/abc", { repo_root: "/home/ahmet/projects/foo", killed_at: "2026-06-01T00:00:00Z" }),
    s("/home/ahmet/projects/foo", { killed_at: "2026-06-02T00:00:00Z" }),
  ], HOME)
  expect(result).toEqual([{ key: "/home/ahmet/projects/foo", label: "…/projects/foo", count: 2 }])
})

test("archivedProjects orders most-recently-archived first", () => {
  const result = archivedProjects([
    s("/home/ahmet/projects/old", { killed_at: "2026-06-01T00:00:00Z" }),
    s("/home/ahmet/projects/new", { killed_at: "2026-06-10T00:00:00Z" }),
  ], HOME)
  expect(result.map((p) => p.label)).toEqual(["…/projects/new", "…/projects/old"])
})

test("archivedProjects returns [] for no sessions", () => {
  expect(archivedProjects([], HOME)).toEqual([])
})

// --- filterByProject ---

test("filterByProject returns only sessions in the given project", () => {
  const sessions = [s("/home/ahmet/projects/foo"), s("/home/ahmet/projects/bar")]
  expect(filterByProject(sessions, "/home/ahmet/projects/foo", HOME)).toEqual([
    s("/home/ahmet/projects/foo"),
  ])
})

test("filterByProject matches a worktree session by repo_root", () => {
  const wt = s("/home/ahmet/.mux/worktrees/x/abc", { repo_root: "/home/ahmet/projects/foo" })
  expect(filterByProject([wt], "/home/ahmet/projects/foo", HOME)).toEqual([wt])
})

test("filterByProject returns all sessions when key is null", () => {
  const sessions = [s("/home/ahmet/projects/foo"), s("/home/ahmet/projects/bar")]
  expect(filterByProject(sessions, null, HOME)).toEqual(sessions)
})
