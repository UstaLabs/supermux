import { describe, expect, test } from "bun:test"
import { claudeLoginSpawnCommand } from "./spawn-command"

describe("claudeLoginSpawnCommand", () => {
  test("uses BSD script syntax on macOS", () => {
    expect(claudeLoginSpawnCommand("darwin")).toEqual({
      cmd: "/bin/sh",
      args: [
        "-c",
        'cat | exec /usr/bin/script -q /dev/null /bin/sh -c "$1"',
        "supermux-claude-login",
        "stty cols 600; exec claude auth login",
      ],
      detached: true,
    })
  })

  test("uses util-linux script syntax on Linux", () => {
    expect(claudeLoginSpawnCommand("linux")).toEqual({
      cmd: "script",
      args: ["-qec", "stty cols 600; exec claude auth login", "/dev/null"],
    })
  })
})
