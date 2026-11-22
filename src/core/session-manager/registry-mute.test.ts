// #163: the older mute paths tell the notification settings; the settings' own mirror of `mute` does not loop back.
import { expect, test } from "bun:test"
import { Registry } from "./registry"

test("setMuted reports to onMuted; mirrorMuted only updates the column", () => {
  const registry = new Registry()
  const s = registry.register({ name: "a", workdir: "/tmp", pid: 1 })
  const told: Array<[string, boolean]> = []
  registry.onMuted = (id, muted) => { told.push([id, muted]) }
  registry.setMuted(s.id, true)
  expect(registry.get(s.id)?.mute).toBe(true)
  registry.mirrorMuted(s.id, false)
  expect(registry.get(s.id)?.mute).toBe(false)
  expect(told).toEqual([[s.id, true]])
})
