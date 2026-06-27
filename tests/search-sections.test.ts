import { test, expect } from "bun:test"
import { splitSections } from "../src/core/search/sections"

test("splits markdown into heading/body sections, ignoring frontmatter", () => {
  const md = [
    "---", "description: infra notes", "tags: [a]", "---", "",
    "# infra", "intro line", "",
    "## TTS default (2026-01-01)", "edge tts is the default.", "",
    "## Deploy (2026-02-02)", "systemd unit mux.service.",
  ].join("\n")
  const secs = splitSections(md)
  expect(secs.map((s) => s.heading)).toEqual(["infra", "TTS default (2026-01-01)", "Deploy (2026-02-02)"])
  expect(secs[1].body).toContain("edge tts")
})

test("handles a file with no headings as one section", () => {
  const secs = splitSections("just a body, no headings")
  expect(secs.length).toBe(1)
  expect(secs[0].heading).toBe("")
  expect(secs[0].body).toContain("just a body")
})
