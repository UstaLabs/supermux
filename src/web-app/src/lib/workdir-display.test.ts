import { describe, expect, it } from "bun:test"
import { toWorkdirRelativePath, workdirDisplay } from "./workdir-display"

describe("workdirDisplay", () => {
  it("uses the same group key for tilde and absolute home paths", () => {
    const a = workdirDisplay("~/projects/app", "/home/user")
    const b = workdirDisplay("/home/user/projects/app/", "/home/user")

    expect(a.key).toBe("/home/user/projects/app")
    expect(b.key).toBe("/home/user/projects/app")
    expect(a.label).toBe("~/projects/app")
    expect(b.label).toBe("~/projects/app")
  })

  it("repairs persisted home-prefixed tilde paths", () => {
    expect(workdirDisplay("/home/user/~/acme", "/home/user")).toEqual({
      key: "/home/user/acme",
      label: "~/acme",
    })
  })

  it("shortens paths outside home", () => {
    expect(workdirDisplay("/var/log/syslog", "/home/user")).toEqual({
      key: "/var/log/syslog",
      label: ".../log/syslog",
    })
  })
})

describe("toWorkdirRelativePath", () => {
  const workdir = "/home/user/projects/project-api"
  const home = "/home/user"

  it("passes through relative paths", () => {
    expect(toWorkdirRelativePath("src/main.ts", workdir, home)).toBe("src/main.ts")
    expect(toWorkdirRelativePath("./src/main.ts", workdir, home)).toBe("src/main.ts")
  })

  it("strips absolute paths under the workdir", () => {
    expect(toWorkdirRelativePath(`${workdir}/src/main.ts`, workdir, home)).toBe("src/main.ts")
  })

  it("strips home-relative paths under the workdir", () => {
    expect(toWorkdirRelativePath("~/projects/project-api/src/main.ts", workdir, home)).toBe("src/main.ts")
  })

  it("rejects paths outside the workdir", () => {
    expect(toWorkdirRelativePath("/etc/passwd", workdir, home)).toBeNull()
  })

  it("strips single-line suffix before resolving", () => {
    expect(toWorkdirRelativePath("src/a.ts:10", workdir, home)).toBe("src/a.ts")
  })

  it("strips range suffix before resolving absolute paths", () => {
    expect(toWorkdirRelativePath(`${workdir}/src/a.ts:10-20`, workdir, home)).toBe("src/a.ts")
  })
})
