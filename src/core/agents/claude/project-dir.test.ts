import { expect, test } from "bun:test"
import { claudeProjectDirName } from "./project-dir"

// Claude Code keeps a working directory's transcripts in <config>/projects/<this>: every character but A-Z, a-z and
// 0-9 made "-". Checked against ~1900 of Claude's own folders on a broker host: none holds anything else, and
// ".../workspace/Supermux PM" is "...-workspace-Supermux-PM". The search index replaced only / and ., so a workdir
// with a space (every Agent named with one), or any Windows path, pointed at a transcript that was not there.
test("a working directory becomes Claude's project folder name, every non-alphanumeric character a dash", () => {
  expect(claudeProjectDirName("/home/ahmet/.mux/workspace/Supermux PM")).toBe("-home-ahmet--mux-workspace-Supermux-PM")
  expect(claudeProjectDirName("/home/u/projects/my_app")).toBe("-home-u-projects-my-app")
  expect(claudeProjectDirName("C:\\Users\\me\\proj")).toBe("C--Users-me-proj")
  expect(claudeProjectDirName("/home/u/app/")).toBe("-home-u-app")
})
