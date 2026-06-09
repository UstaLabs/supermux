import { describe, expect, it } from "bun:test"
import { formatWorkdir } from "./format-workdir"

describe("formatWorkdir", () => {
  it("tilde-prefixes paths under homeDir", () => {
    expect(formatWorkdir("/home/user/projects/myapp", "/home/user")).toBe("~/projects/myapp")
    expect(formatWorkdir("/home/user", "/home/user")).toBe("~")
  })

  it("infers home from path when homeDir omitted", () => {
    expect(formatWorkdir("/home/user/projects/myapp")).toBe("~/projects/myapp")
  })

  it("shortens paths outside home", () => {
    expect(formatWorkdir("/var/log/syslog", "/home/user")).toBe(".../log/syslog")
  })
})
