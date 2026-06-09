import { test, expect } from "bun:test"
import { buildMenuEntries } from "../src/channels/telegram/menu"
import { Registry } from "../src/core/session-manager/registry"

test("base entries always present", () => {
  const r = new Registry()
  const entries = buildMenuEntries(r)
  const names = entries.map(e => e.command)
  expect(names).toContain("sessions")
  expect(names).toContain("active")
  expect(names).toContain("spawn")
})

test("/switch_to_<name> entry per non-active session", () => {
  const r = new Registry()
  r.register({ name: "ana", workdir: "/h", tmux_target: "t", pid: 1 })
  r.register({ name: "zoom",   workdir: "/z", tmux_target: "u", pid: 2 })
  const entries = buildMenuEntries(r)
  const cmds = entries.map(e => e.command)
  expect(cmds).toContain("switch_to_ana")
  expect(cmds).toContain("switch_to_zoom")
})

test("Telegram-incompatible names are sanitized to underscores", () => {
  const r = new Registry()
  r.register({ name: "foo-bar", workdir: "/x", tmux_target: "t", pid: 1 })
  const entries = buildMenuEntries(r)
  expect(entries.map(e => e.command)).toContain("switch_to_foo_bar")
})

test("buildMenuEntries reflects the registry after a session is unregistered", () => {
  const r = new Registry()
  r.register({ name: "ana", workdir: "/h", tmux_target: "t", pid: 1 })
  const zoom = r.register({ name: "zoom",   workdir: "/z", tmux_target: "u", pid: 2 })
  expect(buildMenuEntries(r).map(e => e.command)).toContain("switch_to_zoom")
  // After unregister, /switch_to_zoom should be gone — this guards against
  // the regression where /kill removed the session but the bot's autocomplete
  // still listed it.
  r.unregister(zoom.id)
  const after = buildMenuEntries(r).map(e => e.command)
  expect(after).not.toContain("switch_to_zoom")
  expect(after).toContain("switch_to_ana")
})
