import { describe, expect, it } from "bun:test"
import { formatWorkdir } from "./format-workdir"

describe("formatWorkdir", () => {
  it("shows the last two segments for paths under homeDir", () => {
    expect(formatWorkdir("/home/user/projects/myapp", "/home/user")).toBe("…/projects/myapp")
    expect(formatWorkdir("/home/user", "/home/user")).toBe("~")
    expect(formatWorkdir("/home/user/foo", "/home/user")).toBe("~/foo")
  })

  it("infers home from path when homeDir omitted", () => {
    expect(formatWorkdir("/home/user/projects/myapp")).toBe("…/projects/myapp")
  })

  it("shortens paths outside home", () => {
    expect(formatWorkdir("/var/log/syslog", "/home/user")).toBe("…/log/syslog")
  })
})
