import { describe, expect, it } from "bun:test"
import { buildProjectOptions } from "./project-options"

describe("buildProjectOptions", () => {
  it("uses shared workdir formatting for project labels", () => {
    expect(buildProjectOptions(
      [{ path: "/home/user/projects/project-api" }],
      "/home/user",
    )).toEqual([
      { path: "/home/user/projects/project-api", label: "…/projects/project-api" },
    ])
  })

  it("keeps absolute paths available for selection", () => {
    expect(buildProjectOptions(
      [{ path: "/opt/supermux" }],
      "/home/user",
    )).toEqual([
      { path: "/opt/supermux", label: "opt/supermux" },
    ])
  })
})
