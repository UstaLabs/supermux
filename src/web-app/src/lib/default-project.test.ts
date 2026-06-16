import { expect, test } from "bun:test"
import { chooseDefaultProject } from "./default-project"

test("follows the most-recently-used project until the user engages", () => {
  expect(chooseDefaultProject({ current: "~", recent: [], picked: false, composing: false })).toBe("~")
  expect(chooseDefaultProject({ current: "~", recent: ["/first"], picked: false, composing: false })).toBe("/first")
  // Recency reshuffles before the user engages → keep following the freshest.
  expect(chooseDefaultProject({ current: "/first", recent: ["/second", "/first"], picked: false, composing: false })).toBe("/second")
})

test("preserves a project the user explicitly picked", () => {
  expect(chooseDefaultProject({ current: "/chosen", recent: ["/latest"], picked: true, composing: false })).toBe("/chosen")
})

test("stops following recency once the user starts composing", () => {
  // The bug: the user has typed a message (composing) but never tapped the
  // picker. A message arriving in another session reshuffles recency — the
  // selected project must NOT change under them, or they send to the wrong one.
  expect(chooseDefaultProject({ current: "/first", recent: ["/second", "/first"], picked: false, composing: true })).toBe("/first")
})
