import { test, expect } from "bun:test"
import { controlCommands } from "./control"

test("returns the control commands; mute toggles to unmute when muted", () => {
  const cmds = controlCommands({ muted: false })
  expect(cmds.map((c) => c.name)).toEqual(["spawn", "model", "rename", "mute", "stop", "kill"])
  expect(cmds.every((c) => c.family === "control")).toBe(true)
  expect(cmds.find((c) => c.name === "mute")!.action).toEqual({ kind: "mute", muted: true })
  expect(cmds.find((c) => c.name === "stop")!.action).toEqual({ kind: "stop" })

  const muted = controlCommands({ muted: true })
  const toggle = muted.find((c) => c.name === "unmute")!
  expect(toggle.action).toEqual({ kind: "mute", muted: false })
})
